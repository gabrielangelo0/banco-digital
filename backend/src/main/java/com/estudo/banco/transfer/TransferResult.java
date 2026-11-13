package com.estudo.banco.transfer;

import java.math.BigDecimal;
import java.util.UUID;

public record TransferResult(
        UUID transferId,
        TransferStatus status,
        BigDecimal sourceBalance,
        BigDecimal targetBalance) {
}
