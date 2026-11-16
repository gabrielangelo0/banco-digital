package com.estudo.banco.notification;

import com.estudo.banco.auth.UsuarioAtual;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificacoes;
    private final UsuarioAtual usuarioAtual;

    public NotificationController(NotificationService notificacoes, UsuarioAtual usuarioAtual) {
        this.notificacoes = notificacoes;
        this.usuarioAtual = usuarioAtual;
    }

    /**
     * Sem `accountId` na query: a conta vem da sessao. Antes, qualquer um
     * assinava o fluxo de qualquer conta so trocando um numero na URL.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return notificacoes.subscribe(usuarioAtual.contaId());
    }
}
