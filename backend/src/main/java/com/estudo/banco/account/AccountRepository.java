package com.estudo.banco.account;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    /**
     * Le a conta E trava a linha ate o fim da transacao (SELECT ... FOR UPDATE).
     *
     * Sem isso, duas transferencias simultaneas leem o mesmo saldo antigo e uma
     * sobrescreve a outra - o classico lost update, que cria dinheiro do nada.
     *
     * Quem chama DEVE travar as contas em ordem crescente de id. Ver
     * TransferService#execute.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);
}
