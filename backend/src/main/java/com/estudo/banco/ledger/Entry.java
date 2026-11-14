package com.estudo.banco.ledger;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Um lancamento do razao. Imutavel por construcao: o razao e append-only, e
 * corrigir um erro significa criar um lancamento de estorno, nunca editar o
 * historico.
 *
 * DEBIT guarda valor negativo e CREDIT guarda positivo, entao os dois
 * lancamentos de uma transferencia somam exatamente zero.
 */
@Entity
@Table(name = "entries")
public class Entry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    @Column(name = "transfer_id", nullable = false, updatable = false)
    private UUID transferId;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Direction direction;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Entry() {
    }

    private Entry(Long accountId, UUID transferId, BigDecimal amount, Direction direction) {
        this.accountId = accountId;
        this.transferId = transferId;
        this.amount = amount;
        this.direction = direction;
    }

    public static Entry debit(Long accountId, UUID transferId, BigDecimal amount) {
        return new Entry(accountId, transferId, amount.negate(), Direction.DEBIT);
    }

    public static Entry credit(Long accountId, UUID transferId, BigDecimal amount) {
        return new Entry(accountId, transferId, amount, Direction.CREDIT);
    }

    public Long getId() {
        return id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public UUID getTransferId() {
        return transferId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Direction getDirection() {
        return direction;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
