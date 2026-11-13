package com.estudo.banco.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base de todos os testes de integracao.
 *
 * O container e estatico e iniciado uma unica vez por JVM: subir um Postgres
 * por classe de teste custaria minutos na suite inteira. O Spring tambem
 * reaproveita o contexto entre as subclasses, ja que a configuracao e identica.
 */
@SpringBootTest
// O publisher agendado roda de 1s em 1s em producao. Na maior parte da suite
// ele consumiria eventos no meio das asercoes sobre published = false, e o
// resultado passaria a depender do relogio - por isso vem desligado por padrao.
//
// Isto e @TestPropertySource, e NAO @DynamicPropertySource, de proposito:
// propriedades dinamicas tem precedencia maior e nao podem ser sobrescritas
// pela subclasse, entao OutboxScheduledTest nao conseguiria religar o
// agendador para testar justamente o caminho agendado.
@TestPropertySource(properties = "banco.outbox.auto-publish=false")
public abstract class PostgresTestBase {

    @SuppressWarnings("rawtypes")
    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    /**
     * Cria uma conta nova e devolve o id.
     *
     * O container e o schema sao compartilhados por toda a suite, entao nenhum
     * teste pode depender das contas semeadas pela migracao: um teste de
     * concorrencia que roda antes mudaria o saldo que outro espera encontrar.
     * Cada teste cria o que precisa.
     */
    protected Long novaConta(String titular, String saldo) {
        return jdbc.queryForObject(
                "INSERT INTO accounts (holder_name, balance) VALUES (?, ?) RETURNING id",
                Long.class, titular, new BigDecimal(saldo));
    }

    protected BigDecimal saldoDe(Long contaId) {
        return jdbc.queryForObject(
                "SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, contaId);
    }
}
