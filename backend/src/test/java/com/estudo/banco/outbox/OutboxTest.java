package com.estudo.banco.outbox;

import com.estudo.banco.error.SaldoInsuficienteException;
import com.estudo.banco.support.PostgresTestBase;
import com.estudo.banco.transfer.TransferCommand;
import com.estudo.banco.transfer.TransferResult;
import com.estudo.banco.transfer.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Entre dois sistemas diferentes nao existe commit compartilhado. O outbox
 * resolve a escrita dupla gravando o evento no PROPRIO banco, dentro da mesma
 * transacao: ou os dois existem, ou nenhum.
 */
class OutboxTest extends PostgresTestBase {

    @Autowired
    TransferService transfers;

    @Autowired
    OutboxPublisher publisher;

    private int eventosDa(UUID transferId, Boolean publicado) {
        String sql = "SELECT count(*) FROM outbox_events WHERE payload->>'transferId' = ?"
                + (publicado == null ? "" : " AND published = " + publicado);
        return jdbc.queryForObject(sql, Integer.class, transferId.toString());
    }

    @Test
    void transferenciaGravaEventoNaMesmaTransacao() {
        Long origem = novaConta("Origem", "1000.00");
        Long destino = novaConta("Destino", "0.00");

        TransferResult r = transfers.execute(new TransferCommand(
                origem, destino, new BigDecimal("50.00"), UUID.randomUUID().toString()));

        assertThat(eventosDa(r.transferId(), false)).isEqualTo(1);
    }

    @Test
    void transferenciaQueFalhaNaoDeixaEvento() {
        Long origem = novaConta("Origem", "10.00");
        Long destino = novaConta("Destino", "0.00");
        long antes = jdbc.queryForObject("SELECT count(*) FROM outbox_events", Long.class);

        assertThatThrownBy(() -> transfers.execute(new TransferCommand(
                origem, destino, new BigDecimal("999.00"), UUID.randomUUID().toString())))
                .isInstanceOf(SaldoInsuficienteException.class);

        // O rollback leva o evento junto. Nao existe "avisei sobre uma
        // transferencia que nao aconteceu".
        long depois = jdbc.queryForObject("SELECT count(*) FROM outbox_events", Long.class);
        assertThat(depois).isEqualTo(antes);
    }

    @Test
    void publisherMarcaComoPublicado() {
        Long origem = novaConta("Origem", "1000.00");
        Long destino = novaConta("Destino", "0.00");

        TransferResult r = transfers.execute(new TransferCommand(
                origem, destino, new BigDecimal("30.00"), UUID.randomUUID().toString()));

        int publicados = publisher.publishPending();

        assertThat(publicados).isGreaterThanOrEqualTo(1);
        assertThat(eventosDa(r.transferId(), true)).isEqualTo(1);
        assertThat(eventosDa(r.transferId(), false)).isZero();
    }

    @Test
    void publisherNaoRepublicaOqueJaFoiPublicado() {
        Long origem = novaConta("Origem", "1000.00");
        Long destino = novaConta("Destino", "0.00");
        transfers.execute(new TransferCommand(
                origem, destino, new BigDecimal("30.00"), UUID.randomUUID().toString()));

        publisher.publishPending();
        int segundaRodada = publisher.publishPending();

        assertThat(segundaRodada).isZero();
    }
}
