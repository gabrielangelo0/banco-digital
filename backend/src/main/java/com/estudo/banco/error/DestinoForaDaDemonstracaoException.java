package com.estudo.banco.error;

/**
 * Conta de demonstracao so transfere para conta de demonstracao.
 *
 * Sem esta regra o dinheiro escapa do par de contas semeadas e o reset
 * horario deixa de fechar: nao ha de onde tirar a diferenca de volta.
 */
public class DestinoForaDaDemonstracaoException extends RuntimeException {

    public DestinoForaDaDemonstracaoException() {
        super("Nesta demonstracao a transferencia so vai para a outra conta de demonstracao.");
    }
}
