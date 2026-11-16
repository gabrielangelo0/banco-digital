package com.estudo.banco.error;

public class MuitasTentativasException extends RuntimeException {

    public MuitasTentativasException() {
        super("Tentativas demais. Tente de novo em alguns minutos.");
    }
}
