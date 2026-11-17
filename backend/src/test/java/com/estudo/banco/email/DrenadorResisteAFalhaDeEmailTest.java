package com.estudo.banco.email;

import com.estudo.banco.outbox.OutboxPublisher;
import com.estudo.banco.support.PostgresTestBase;
import com.estudo.banco.transfer.TransferCommand;
import com.estudo.banco.transfer.TransferResult;
import com.estudo.banco.transfer.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Um problema no e-mail nao pode travar a notificacao da tela.
 *
 * O drenador e UMA transacao sobre ate cem eventos. Se a escrita do e-mail
 * estourar ali dentro, o `published` dos cem volta atras e o SSE de todos eles
 * e reentregue a cada segundo, para sempre - que e justamente o que o desenho
 * se propoe a evitar. Comprovante perdido se reenfileira a mao; caixa de saida
 * travada, nao.
 */
class DrenadorResisteAFalhaDeEmailTest extends PostgresTestBase {

    @Autowired
    TransferService transfers;

    @Autowired
    OutboxPublisher publisher;

    @MockitoBean
    EmailQueue fila;

    @Test
    void falhaAoEnfileirarNaoImpedeOEventoDeSerPublicado() {
        when(fila.enfileirar(any())).thenThrow(new IllegalStateException("fila quebrada"));

        Long origem = novaContaComDono("Gabriel", "1000.00",
                "gabriel-" + UUID.randomUUID() + "@exemplo.com");
        Long destino = novaContaComDono("Mariana", "0.00",
                "mariana-" + UUID.randomUUID() + "@exemplo.com");

        TransferResult r = transfers.execute(new TransferCommand(
                origem, destino, new BigDecimal("7.00"), UUID.randomUUID().toString()));

        for (int volta = 0; volta < 50 && publisher.publishPending() > 0; volta++) {
            // drena ate esvaziar; ver ComprovanteNaTransferenciaTest
        }

        assertThat(jdbc.queryForObject(
                "SELECT bool_and(published) FROM outbox_events WHERE payload->>'transferId' = ?",
                Boolean.class, r.transferId().toString())).isTrue();
    }
}
