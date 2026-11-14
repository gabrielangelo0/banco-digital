package com.estudo.banco.error;

public class TransferenciaInvalidaException extends RuntimeException {
    public TransferenciaInvalidaException(String motivo) {
        super(motivo);
    }
}
