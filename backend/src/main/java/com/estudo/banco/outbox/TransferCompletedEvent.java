package com.estudo.banco.outbox;

import java.math.BigDecimal;
import java.util.UUID;

public record TransferCompletedEvent(
        UUID transferId,
        Long sourceAccountId,
        Long targetAccountId,
        BigDecimal amount) {

    public static final String TYPE = "TRANSFER_COMPLETED";
}
