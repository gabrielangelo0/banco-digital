package com.estudo.banco.auth;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * Quem esta falando, nesta requisicao.
 *
 * Preenchido pelo filtro de sessao, nunca por parametro de requisicao. Era
 * exatamente isso que faltava antes: `accountId` chegava na query string, e o
 * cliente declarava quem era.
 */
@Component
@RequestScope
public class UsuarioAtual {

    private Long userId;
    private Long contaId;

    void definir(Long userId, Long contaId) {
        this.userId = userId;
        this.contaId = contaId;
    }

    public Long userId() {
        return userId;
    }

    public Long contaId() {
        return contaId;
    }

    public boolean autenticado() {
        return contaId != null;
    }
}
