package com.estudo.banco.error;

/**
 * Recusa de login.
 *
 * Uma mensagem so para todos os motivos. "Usuario nao encontrado" e "senha
 * errada" sao respostas diferentes para a mesma pergunta: existe conta com
 * este e-mail?
 */
public class CredenciaisInvalidasException extends RuntimeException {

    public CredenciaisInvalidasException() {
        super("E-mail ou senha incorretos.");
    }
}
