package com.estudo.banco.email;

import com.estudo.banco.support.PostgresTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A maquina de estados da fila.
 *
 * O {@code ResendClient} entra dublado de proposito: o que esta sob prova aqui
 * e o que a fila FAZ com cada resposta, nao o HTTP - esse e o
 * {@code ResendClientTest}.
 */
@TestPropertySource(properties = "banco.email.enabled=false")
class EmailSenderTest extends PostgresTestBase {

    @Autowired
    EmailSender sender;

    @Autowired
    EmailQueue fila;

    @MockitoBean
    ResendClient resend;

    /**
     * Tira do caminho o que outros testes deixaram pendente.
     *
     * O banco e compartilhado pela suite inteira e o entregador pega as cinco
     * mais antigas: sem isto ele trabalharia nas mensagens alheias, e os
     * `verifyNoInteractions` daqui falhariam por algo que este teste nao fez.
     * Adiar e mais honesto do que apagar - nada se perde, so sai do lote.
     */
    @BeforeEach
    void tirarAsAlheiasDoCaminho() {
        jdbc.update("UPDATE email_messages SET next_attempt_at = now() + interval '10 years'"
                + " WHERE status = 'PENDING'");
    }

    private String enfileirarUma() {
        String chave = "teste/" + UUID.randomUUID();
        fila.enfileirar(new EmailParaEnviar("a@b.com", "A", "<p>a</p>", "a", chave));
        return chave;
    }

    private Map<String, Object> linha(String chave) {
        return jdbc.queryForMap(
                "SELECT status, attempts, provider_message_id, sent_at, next_attempt_at"
                        + " FROM email_messages WHERE idempotency_key = ?", chave);
    }

    @Test
    void sucessoMarcaEnviadoEGuardaOIdDoProvedor() {
        String chave = enfileirarUma();
        when(resend.enviar(any())).thenReturn("id-do-resend");

        sender.enviarPendentes();

        assertThat(linha(chave)).containsEntry("status", "SENT")
                .containsEntry("provider_message_id", "id-do-resend");
        assertThat(linha(chave).get("sent_at")).isNotNull();
    }

    @Test
    void falhaTemporariaContaTentativaEAdia() {
        String chave = enfileirarUma();
        when(resend.enviar(any())).thenThrow(new FalhaTemporariaDoProvedor("500"));

        sender.enviarPendentes();

        assertThat(linha(chave)).containsEntry("status", "PENDING").containsEntry("attempts", 1);
        // Consulta tipada: `queryForMap` devolve java.sql.Timestamp, que nao
        // e um OffsetDateTime e nao aceita a conversao direta.
        assertThat(jdbc.queryForObject(
                "SELECT next_attempt_at FROM email_messages WHERE idempotency_key = ?",
                OffsetDateTime.class, chave)).isAfter(OffsetDateTime.now());
    }

    @Test
    void falhaDefinitivaDesisteNaHora() {
        String chave = enfileirarUma();
        when(resend.enviar(any())).thenThrow(new FalhaDefinitivaDoProvedor("403"));

        sender.enviarPendentes();

        assertThat(linha(chave)).containsEntry("status", "FAILED").containsEntry("attempts", 1);
    }

    @Test
    void desisteAoChegarNoTetoDeTentativas() {
        String chave = enfileirarUma();
        when(resend.enviar(any())).thenThrow(new FalhaTemporariaDoProvedor("500"));

        for (int i = 0; i < 7; i++) {
            jdbc.update("UPDATE email_messages SET next_attempt_at = now() - interval '1 hour'"
                    + " WHERE idempotency_key = ?", chave);
            sender.enviarPendentes();
        }

        assertThat(linha(chave)).containsEntry("status", "FAILED");
    }

    @Test
    void mensagemVelhaDemaisNaoEhEnviada() {
        String chave = enfileirarUma();
        jdbc.update("UPDATE email_messages SET created_at = now() - interval '48 hours'"
                + " WHERE idempotency_key = ?", chave);

        sender.enviarPendentes();

        // Ligar a chave pela primeira vez nao pode despejar comprovantes de dias atras.
        assertThat(linha(chave)).containsEntry("status", "FAILED");
        verifyNoInteractions(resend);
    }

    @Test
    void naoTentaAntesDaHoraMarcada() {
        String chave = enfileirarUma();
        jdbc.update("UPDATE email_messages SET next_attempt_at = now() + interval '1 hour'"
                + " WHERE idempotency_key = ?", chave);

        sender.enviarPendentes();

        verifyNoInteractions(resend);
        assertThat(linha(chave)).containsEntry("status", "PENDING");
    }
}
