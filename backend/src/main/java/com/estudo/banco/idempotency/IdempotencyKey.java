package com.estudo.banco.idempotency;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A chave de idempotencia consumida.
 *
 * A garantia mora na PRIMARY KEY desta tabela, nao num `if` do codigo: duas
 * requisicoes concorrentes com a mesma chave colidem aqui, no commit.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey implements Persistable<String> {

    @Id
    @Column(name = "key", nullable = false, updatable = false)
    private String chave;

    @Column(name = "transfer_id")
    private UUID transferId;

    /** A resposta original, devolvida tal e qual quando a chave se repete. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body", nullable = false)
    private String responseBody;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Transient
    private boolean novo = true;

    protected IdempotencyKey() {
    }

    public IdempotencyKey(String chave, UUID transferId, String responseBody) {
        this.chave = chave;
        this.transferId = transferId;
        this.responseBody = responseBody;
    }

    @Override
    public String getId() {
        return chave;
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

    public UUID getTransferId() {
        return transferId;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
