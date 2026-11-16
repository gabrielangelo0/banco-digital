package com.estudo.banco.account;

import com.estudo.banco.error.SaldoInsuficienteException;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Conta corrente.
 *
 * `balance` e um saldo MATERIALIZADO: a fonte da verdade e o razao
 * (`entries`), e este campo existe para nao somar o historico inteiro a cada
 * leitura. O teste de reconciliacao garante que os dois nunca divergem.
 *
 * `version` habilita lock otimista. Sob o lock pessimista usado na
 * transferencia ele e inalcancavel na pratica - esta aqui de proposito, como
 * demonstracao da estrategia alternativa (ver docs/GUIA-FLUXO-BANCARIO.md).
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "holder_name", nullable = false)
    private String holderName;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * Dono da conta. Anulavel porque as contas semeadas nasceram antes de
     * existir usuario: elas ganham dono na subida, nao na migracao.
     */
    @Column(name = "owner_id")
    private Long ownerId;

    protected Account() {
    }

    public Account(String holderName, BigDecimal balance) {
        this.holderName = holderName;
        this.balance = balance;
    }

    public Account(String holderName, BigDecimal balance, Long ownerId) {
        this(holderName, balance);
        this.ownerId = ownerId;
    }

    /** Liga uma conta ja existente a um dono. */
    public void definirDono(Long ownerId) {
        this.ownerId = ownerId;
    }

    /** Debita, ou recusa se nao houver saldo. Nunca deixa a conta negativa. */
    public void debit(BigDecimal amount) {
        if (balance.compareTo(amount) < 0) {
            throw new SaldoInsuficienteException(id, balance, amount);
        }
        this.balance = balance.subtract(amount);
    }

    public void credit(BigDecimal amount) {
        this.balance = balance.add(amount);
    }

    public Long getId() {
        return id;
    }

    public String getHolderName() {
        return holderName;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public Long getVersion() {
        return version;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public Long getOwnerId() {
        return ownerId;
    }
}
