package com.estudo.banco.error;

/**
 * Autenticado, mas sem provar que pode mover dinheiro.
 *
 * 403 e nao 401 de proposito: a sessao e valida: o que falta e autorizacao
 * para esta operacao em especifico.
 */
public class SenhaTransacionalInvalidaException extends RuntimeException {

    public SenhaTransacionalInvalidaException() {
        super("Senha transacional incorreta.");
    }
}
