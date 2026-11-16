package com.estudo.banco.transfer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Sem `sourceAccountId`: a origem e a conta da sessao.
 *
 * Enquanto a origem vinha no corpo, transferir da conta alheia era editar um
 * numero no JSON.
 */
public record TransferRequest(
        @NotNull Long targetAccountId,
        @NotNull BigDecimal amount,
        @NotBlank String transactionPassword) {
}
