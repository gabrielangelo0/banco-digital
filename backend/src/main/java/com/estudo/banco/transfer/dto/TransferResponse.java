package com.estudo.banco.transfer.dto;

import com.estudo.banco.transfer.TransferResult;
import com.estudo.banco.transfer.TransferStatus;

import java.math.BigDecimal;
import java.util.UUID;

public record TransferResponse(
        UUID transferId,
        TransferStatus status,
        BigDecimal sourceBalance,
        BigDecimal targetBalance) {

    public static TransferResponse from(TransferResult r) {
        return new TransferResponse(r.transferId(), r.status(), r.sourceBalance(), r.targetBalance());
    }
}
