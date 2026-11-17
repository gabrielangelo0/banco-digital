package com.estudo.banco.email;

import com.estudo.banco.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A fila de e-mail.
 *
 * A trava contra duplicata e a consulta pela chave ANTES de inserir, e nao o
 * indice unico. O indice e a rede para duas entregas simultaneas: deixar uma
 * violacao de unicidade acontecer dentro da transacao do drenador envenenaria
 * a transacao inteira, e o lote de ate cem eventos voltaria atras junto.
 */
class EmailQueueTest extends PostgresTestBase {

    @Autowired
    EmailQueue fila;

    @Test
    void enfileiraMensagemPendente() {
        boolean gravou = fila.enfileirar(new EmailParaEnviar(
                "alguem@exemplo.com", "Assunto", "<p>oi</p>", "oi", "teste/" + UUID.randomUUID()));

        assertThat(gravou).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM email_messages WHERE to_address = 'alguem@exemplo.com'",
                String.class)).isEqualTo("PENDING");
    }

    @Test
    void mesmaChaveGravaUmaVezSo() {
        String chave = "teste/" + UUID.randomUUID();
        EmailParaEnviar m = new EmailParaEnviar("a@b.com", "A", "<p>a</p>", "a", chave);

        assertThat(fila.enfileirar(m)).isTrue();
        assertThat(fila.enfileirar(m)).isFalse();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM email_messages WHERE idempotency_key = ?",
                Integer.class, chave)).isEqualTo(1);
    }

    @Test
    void recusaDestinatarioVazio() {
        assertThatThrownBy(() -> fila.enfileirar(new EmailParaEnviar(
                "  ", "A", "<p>a</p>", "a", "teste/" + UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
