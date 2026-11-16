package com.estudo.banco.error;

/**
 * Recusa de cadastro.
 *
 * A mensagem e deliberadamente generica e igual para todos os motivos. Dizer
 * "este e-mail ja existe" transforma o formulario de cadastro num verificador
 * de quem tem conta aqui.
 */
public class CadastroInvalidoException extends RuntimeException {

    public CadastroInvalidoException() {
        super("Nao foi possivel concluir o cadastro com estes dados.");
    }
}
