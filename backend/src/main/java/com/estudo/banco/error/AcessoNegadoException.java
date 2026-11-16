package com.estudo.banco.error;

/** Autenticado, mas pedindo algo que nao e seu. */
public class AcessoNegadoException extends RuntimeException {

    public AcessoNegadoException() {
        super("Este recurso nao pertence a sua conta.");
    }
}
