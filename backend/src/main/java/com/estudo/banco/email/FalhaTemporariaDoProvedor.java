package com.estudo.banco.email;

/**
 * Falha que passa: vale tentar de novo mais tarde.
 *
 * Cobre 5xx, rede, e tambem o 429 - que e 4xx mas significa "voce esta indo
 * rapido demais", nao "este pedido esta errado". Classificar o 429 como
 * definitivo transformaria limite de vazao em comprovante perdido.
 */
public class FalhaTemporariaDoProvedor extends RuntimeException {

    public FalhaTemporariaDoProvedor(String mensagem) {
        super(mensagem);
    }

    public FalhaTemporariaDoProvedor(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
