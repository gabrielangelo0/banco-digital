package com.estudo.banco.user;

import com.estudo.banco.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserSchemaTest extends PostgresTestBase {

    @Test
    void migracaoCriaColunaDeDonoAnulavelEPreservaAsContasSemeadas() {
        assertThat(jdbc.queryForObject(
                "select count(*) from information_schema.columns "
                        + "where table_name = 'accounts' and column_name = 'owner_id'",
                Integer.class)).isEqualTo(1);

        // Anulavel de proposito: as contas 1 e 2 ja existem e ganham dono em
        // tempo de execucao, para que nenhuma credencial entre no repositorio.
        assertThat(jdbc.queryForObject(
                "select is_nullable from information_schema.columns "
                        + "where table_name = 'accounts' and column_name = 'owner_id'",
                String.class)).isEqualTo("YES");

        assertThat(jdbc.queryForObject(
                "select count(*) from accounts where id in (1, 2)",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void emailEhUnicoIgnorandoCaixa() {
        String email = "joao-" + UUID.randomUUID() + "@exemplo.com";
        inserirUsuario(email);

        assertThatThrownBy(() -> inserirUsuario(email.toUpperCase()))
                .isInstanceOf(DataAccessException.class);
    }

    private void inserirUsuario(String email) {
        jdbc.update("insert into users (email, password_hash, transaction_password_hash) "
                + "values (?, ?, ?)", email, "nao-e-hash-de-verdade", "nao-e-hash-de-verdade");
    }
}
