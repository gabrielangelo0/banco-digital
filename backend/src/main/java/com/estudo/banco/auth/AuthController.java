package com.estudo.banco.auth;

import com.estudo.banco.account.AccountRepository;
import com.estudo.banco.auth.dto.LoginRequest;
import com.estudo.banco.auth.dto.RegisterRequest;
import com.estudo.banco.auth.dto.RegisterResponse;
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

    public AuthController(RegistrationService registration,
                          AuthenticationService autenticacao,
                          SessionStore sessoes,
                          AccountRepository contas,
                          NotificationService notificacoes) {
        this.registration = registration;
        this.autenticacao = autenticacao;
        this.sessoes = sessoes;
        this.contas = contas;
        this.notificacoes = notificacoes;
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
        Long userId = autenticacao.autenticar(request.email(), request.password());
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
