package com.estudo.banco.transfer;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "transfers")
public class Transfer implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "source_account_id", nullable = false, updatable = false)
    private Long sourceAccountId;

    @Column(name = "target_account_id", nullable = false, updatable = false)
    private Long targetAccountId;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransferStatus status;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * O id e atribuido no codigo, nao gerado pelo banco. Sem este marcador o
     * Spring Data faria um SELECT antes de cada save para descobrir se a linha
     * ja existe - uma ida ao banco por transferencia, dentro da secao critica.
     */
    @Transient
    private boolean novo = true;

    protected Transfer() {
    }

    public Transfer(UUID id, Long sourceAccountId, Long targetAccountId,
                    BigDecimal amount, TransferStatus status) {
        this.id = id;
        this.sourceAccountId = sourceAccountId;
        this.targetAccountId = targetAccountId;
        this.amount = amount;
        this.status = status;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return novo;
    }

    @PostPersist
    @PostLoad
    void marcarPersistido() {
        this.novo = false;
    }

    public Long getSourceAccountId() {
        return sourceAccountId;
    }

    public Long getTargetAccountId() {
        return targetAccountId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
