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

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final RegistrationService registration;
    private final AuthenticationService autenticacao;
    private final SessionStore sessoes;
    private final AccountRepository contas;
    private final NotificationService notificacoes;
    private final AttemptLimiter tentativas;

    public AuthController(RegistrationService registration,
                          AuthenticationService autenticacao,
                          SessionStore sessoes,
                          AccountRepository contas,
                          NotificationService notificacoes,
                          AttemptLimiter tentativas) {
        this.registration = registration;
        this.autenticacao = autenticacao;
        this.sessoes = sessoes;
        this.contas = contas;
        this.notificacoes = notificacoes;
        this.tentativas = tentativas;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse registrar(@Valid @RequestBody RegisterRequest request) {
        Long contaId = registration.register(
                request.email(), request.password(), request.transactionPassword());
        return new RegisterResponse(contaId);
    }

    @PostMapping("/login")
    public ResponseEntity<Void> entrar(@Valid @RequestBody LoginRequest request,
                                       HttpServletRequest http) {
        String chave = "login:" + (request.email() == null ? "" : request.email().trim().toLowerCase());
        if (tentativas.bloqueado(chave)) {
            throw new MuitasTentativasException();
        }

        Long userId;
        try {
            userId = autenticacao.autenticar(request.email(), request.password());
        } catch (CredenciaisInvalidasException e) {
            tentativas.registrarFalha(chave);
            throw e;
        }
        tentativas.limpar(chave);

        String token = sessoes.create(userId);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        SessionCookie.build(token, http.isSecure()).toString())
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
                        SessionCookie.expirada(http.isSecure()).toString())
                .build();
    }

    private void encerrarStreams(Long userId) {
        contas.findByOwnerId(userId)
                .ifPresent(conta -> notificacoes.encerrarInscricoes(conta.getId()));
    }
}
