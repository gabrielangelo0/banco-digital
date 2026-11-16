package com.estudo.banco.auth;

import com.estudo.banco.account.AccountRepository;
import com.estudo.banco.auth.dto.LoginRequest;
import com.estudo.banco.auth.dto.RegisterRequest;
import com.estudo.banco.auth.dto.RegisterResponse;
import com.estudo.banco.error.CredenciaisInvalidasException;
import com.estudo.banco.error.MuitasTentativasException;
import com.estudo.banco.notification.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final RegistrationService registration;
    private final AuthenticationService autenticacao;
    private final SessionStore sessoes;
    private final AccountRepository contas;
    private final NotificationService notificacoes;
    private final AttemptLimiter tentativas;
    private final ClientIpResolver proxy;

    /**
     * Tetos por IP, maiores que o teto por identidade.
     *
     * Sem eles o bloqueio por identidade e contornado trocando o e-mail a
     * cada tentativa, e cada tentativa custa um Argon2id de 16 MB - por
     * desenho, inclusive para e-mail inexistente, que e como o tempo
     * constante foi implementado. O cadastro custa dois.
     */
    static final int TETO_IP_LOGIN = 10;
    static final int TETO_IP_CADASTRO = 5;

    public AuthController(RegistrationService registration,
                          AuthenticationService autenticacao,
                          SessionStore sessoes,
                          AccountRepository contas,
                          NotificationService notificacoes,
                          AttemptLimiter tentativas,
                          ClientIpResolver proxy) {
        this.registration = registration;
        this.autenticacao = autenticacao;
        this.sessoes = sessoes;
        this.contas = contas;
        this.notificacoes = notificacoes;
        this.tentativas = tentativas;
        this.proxy = proxy;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse registrar(@Valid @RequestBody RegisterRequest request,
                                      HttpServletRequest http) {
        // Aqui o evento contado e a propria chamada, nao a falha: o custo
        // esta em executar o cadastro, e nao existe "falha" para contar.
        chaveDoIp(http, "ip-cadastro:").ifPresent(chave -> {
            if (tentativas.bloqueado(chave, TETO_IP_CADASTRO)) {
                throw new MuitasTentativasException();
            }
            tentativas.registrar(chave);
        });

        Long contaId = registration.register(
                request.email(), request.password(), request.transactionPassword());
        return new RegisterResponse(contaId);
    }

    @PostMapping("/login")
    public ResponseEntity<Void> entrar(@Valid @RequestBody LoginRequest request,
                                       HttpServletRequest http) {
        String chave = "login:" + (request.email() == null ? "" : request.email().trim().toLowerCase());
        Optional<String> chaveIp = chaveDoIp(http, "ip-login:");
        if (tentativas.bloqueado(chave)
                || chaveIp.filter(c -> tentativas.bloqueado(c, TETO_IP_LOGIN)).isPresent()) {
            throw new MuitasTentativasException();
        }

        Long userId;
        try {
            userId = autenticacao.autenticar(request.email(), request.password());
        } catch (CredenciaisInvalidasException e) {
            tentativas.registrar(chave);
            chaveIp.ifPresent(tentativas::registrar);
            throw e;
        }
        // Limpa a identidade, nao o IP: quem acerta a propria senha nao
        // zera o contador do endereco de onde veio o ataque.
        tentativas.limpar(chave);

        String token = sessoes.create(userId);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        SessionCookie.build(token, proxy.ehHttps(http)).toString())
                .build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> sair(@CookieValue(value = SessionCookie.NOME, required = false) String token,
                                     HttpServletRequest http) {
        if (token != null) {
            // Encerrar as inscricoes ANTES de revogar: depois de revogar nao ha
            // mais como descobrir de quem era a sessao.
            sessoes.resolve(token).ifPresent(this::encerrarStreams);
            sessoes.revoke(token);
        }
        return limparCookie(http);
    }

    /** Sair de todos os dispositivos. */
    @DeleteMapping("/sessions")
    public ResponseEntity<Void> sairDeTodos(
            @CookieValue(value = SessionCookie.NOME, required = false) String token,
            HttpServletRequest http) {
        sessoes.resolve(token).ifPresent(userId -> {
            encerrarStreams(userId);
            sessoes.revokeAll(userId);
        });
        return limparCookie(http);
    }

    private ResponseEntity<Void> limparCookie(HttpServletRequest http) {
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE,
                        SessionCookie.expirada(proxy.ehHttps(http)).toString())
                .build();
    }

    private Optional<String> chaveDoIp(HttpServletRequest http, String prefixo) {
        return proxy.resolve(http).map(ip -> prefixo + ip);
    }

    private void encerrarStreams(Long userId) {
        contas.findByOwnerId(userId)
                .ifPresent(conta -> notificacoes.encerrarInscricoes(conta.getId()));
    }
}
