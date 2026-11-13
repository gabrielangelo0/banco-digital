package com.estudo.banco;

import com.estudo.banco.support.PostgresTestBase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaMigrationTest extends PostgresTestBase {

    @Test
    void migracaoCriaTodasAsTabelas() {
        var tabelas = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(tabelas).contains(
                "accounts", "transfers", "entries", "idempotency_keys", "outbox_events");
    }

    @Test
    void saldoNegativoEhRejeitadoPeloBanco() {
        // Ultima linha de defesa: mesmo que um bug passe pela validacao de
        // dominio, o banco se recusa a gravar uma conta com saldo negativo.
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO accounts (holder_name, balance) VALUES ('fulano', -1)"))
                .hasMessageContaining("accounts_balance_non_negative");
    }
}
