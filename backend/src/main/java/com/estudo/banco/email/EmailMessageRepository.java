package com.estudo.banco.email;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;

public interface EmailMessageRepository extends JpaRepository<EmailMessage, Long> {

    /**
     * A trava contra duplicata, consultada ANTES de inserir.
     *
     * O indice unico na coluna existe, mas como rede: deixar a violacao
     * acontecer dentro da transacao do drenador envenenaria a transacao
     * inteira, e os ate cem eventos daquele lote voltariam atras junto.
     */
    boolean existsByIdempotencyKey(String idempotencyKey);

    /**
     * O lote do entregador.
     *
     * Cinco por ciclo fica bem abaixo das dez requisicoes por segundo que o
     * Resend permite na conta inteira, entao nao e preciso controlar vazao.
     */
    List<EmailMessage> findTop5ByStatusAndNextAttemptAtBeforeOrderByIdAsc(
            EmailStatus status, OffsetDateTime agora);
}
