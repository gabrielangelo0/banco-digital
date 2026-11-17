package com.estudo.banco.email;

import com.estudo.banco.auth.RegistrationService;
import com.estudo.banco.error.CadastroInvalidoException;
import com.estudo.banco.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * As boas-vindas nascem na mesma transacao da conta.
 *
 * E o que torna o fato e a mensagem indivisiveis: nao existe "boas-vindas a um
 * cadastro que nao houve".
 */
class BoasVindasTest extends PostgresTestBase {

    @Autowired
    RegistrationService cadastro;

    @Test
    void cadastroEnfileiraBoasVindas() {
        String email = "novo-" + UUID.randomUUID() + "@exemplo.com";

        Long conta = cadastro.register(email, "senha-de-acesso", "senha-transacional");
        Long usuario = jdbc.queryForObject(
                "SELECT owner_id FROM accounts WHERE id = ?", Long.class, conta);

        assertThat(jdbc.queryForObject(
                "SELECT to_address FROM email_messages WHERE idempotency_key = ?",
                String.class, "welcome/" + usuario)).isEqualTo(email);
    }

    @Test
    void cadastroRecusadoNaoDeixaEmailNaFila() {
        String email = "repetido-" + UUID.randomUUID() + "@exemplo.com";
        cadastro.register(email, "senha-de-acesso", "senha-transacional");
        long antes = jdbc.queryForObject("SELECT count(*) FROM email_messages", Long.class);

        assertThatThrownBy(() -> cadastro.register(email, "outra-senha", "outra-transacional"))
                .isInstanceOf(CadastroInvalidoException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_messages", Long.class))
                .isEqualTo(antes);
    }
}
