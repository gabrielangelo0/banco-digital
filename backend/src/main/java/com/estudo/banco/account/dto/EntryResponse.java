package com.estudo.banco.account.dto;

import com.estudo.banco.ledger.Direction;
import com.estudo.banco.ledger.Entry;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record EntryResponse(
        Long id,
        UUID transferId,
        BigDecimal amount,
        Direction direction,
        OffsetDateTime createdAt) {

    public static EntryResponse from(Entry entry) {
        return new EntryResponse(
                entry.getId(), entry.getTransferId(), entry.getAmount(),
                entry.getDirection(), entry.getCreatedAt());
    }
}
