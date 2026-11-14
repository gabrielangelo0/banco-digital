package com.estudo.banco.error;

public class ContaNaoEncontradaException extends RuntimeException {
    public ContaNaoEncontradaException(Long id) {
        super("Conta nao encontrada: " + id);
    }
}
