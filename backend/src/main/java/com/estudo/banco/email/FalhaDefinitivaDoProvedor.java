package com.estudo.banco.email;

/**
 * Falha que nao passa: insistir nao conserta.
 *
 * Dominio nao verificado, destinatario invalido, chave recusada. Cada nova
 * tentativa so gastaria reputacao do dominio que manda, entao a mensagem vai
 * direto para FAILED com o motivo guardado.
 */
public class FalhaDefinitivaDoProvedor extends RuntimeException {

    public FalhaDefinitivaDoProvedor(String mensagem) {
        super(mensagem);
    }
}
