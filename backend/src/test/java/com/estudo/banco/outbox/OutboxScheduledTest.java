package com.estudo.banco.outbox;

import com.estudo.banco.support.PostgresTestBase;
import com.estudo.banco.transfer.TransferCommand;
import com.estudo.banco.transfer.TransferResult;
import com.estudo.banco.transfer.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Exercita o caminho que roda DE VERDADE em producao: o agendador.
 *
 * O resto da suite chama publishPending() direto no bean, que e o proxy do
 * Spring - ali a transacao existe. Este teste nao chama nada: liga o agendador
 * e espera. Foi assim que se descobriu que o caminho agendado nao tinha
 * transacao nenhuma e o outbox nunca drenava.
 */
@TestPropertySource(properties = {
        "banco.outbox.auto-publish=true",
        "banco.outbox.poll-ms=200"
})
class OutboxScheduledTest extends PostgresTestBase {

    @Autowired
    TransferService transfers;

    @Test
    void agendadorMarcaOsEventosComoPublicados() {
        Long origem = novaConta("Origem", "1000.00");
        Long destino = novaConta("Destino", "0.00");

        TransferResult r = transfers.execute(new TransferCommand(
                origem, destino, new BigDecimal("40.00"), UUID.randomUUID().toString()));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Integer publicados = jdbc.queryForObject(
                    "SELECT count(*) FROM outbox_events "
                            + "WHERE payload->>'transferId' = ? AND published = true",
                    Integer.class, r.transferId().toString());
            assertThat(publicados).isEqualTo(1);
        });
    }
}
