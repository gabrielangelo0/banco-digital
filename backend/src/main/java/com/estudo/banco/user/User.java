package com.estudo.banco.user;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * Identidade de quem usa o banco.
 *
 * Guarda DOIS hashes, e isso e deliberado: `passwordHash` prova quem voce e,
 * `transactionPasswordHash` autoriza mover dinheiro. Autenticar nao e
 * autorizar - quem pegou uma sessao aberta ainda nao consegue transferir.
 *
 * Nenhum dos dois e reversivel, e nenhum dos dois aparece em log ou em
 * resposta de API.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Sempre gravado em minusculas. A unicidade no banco e por `lower(email)`,
     * entao gravar normalizado mantem consulta e restricao olhando para a
     * mesma coisa.
     */
    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "transaction_password_hash", nullable = false)
    private String transactionPasswordHash;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected User() {
    }

    public User(String email, String passwordHash, String transactionPasswordHash) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.transactionPasswordHash = transactionPasswordHash;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getTransactionPasswordHash() {
        return transactionPasswordHash;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
