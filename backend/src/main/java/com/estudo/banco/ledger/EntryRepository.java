package com.estudo.banco.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface EntryRepository extends JpaRepository<Entry, Long> {

    List<Entry> findByAccountIdOrderByCreatedAtDescIdDesc(Long accountId);

    List<Entry> findByTransferId(UUID transferId);

    /** Soma de todos os lancamentos de uma conta. Usada na reconciliacao. */
    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM Entry e WHERE e.accountId = :accountId")
    BigDecimal sumAmountByAccountId(@Param("accountId") Long accountId);
}
