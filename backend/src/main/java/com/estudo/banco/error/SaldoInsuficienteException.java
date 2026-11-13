package com.estudo.banco.error;

import java.math.BigDecimal;

public class SaldoInsuficienteException extends RuntimeException {
    public SaldoInsuficienteException(Long contaId, BigDecimal saldo, BigDecimal solicitado) {
        super("Saldo insuficiente na conta " + contaId
                + ": disponivel " + saldo + ", solicitado " + solicitado);
    }
}
