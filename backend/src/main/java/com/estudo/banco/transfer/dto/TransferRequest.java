package com.estudo.banco.transfer.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record TransferRequest(
        @NotNull Long sourceAccountId,
        @NotNull Long targetAccountId,
        @NotNull BigDecimal amount) {
}
