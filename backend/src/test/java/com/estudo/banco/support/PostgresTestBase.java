package com.estudo.banco.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
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

    // Redis vive AQUI, e nao numa base separada, porque o contexto do Spring e
    // compartilhado por toda a suite: a sessao e um bean, entao todo teste que
    // levanta o contexto precisa de um Redis alcancavel. Uma segunda base
    // criaria um segundo contexto e dobraria o tempo da suite.
    @SuppressWarnings("rawtypes")
    protected static final GenericContainer REDIS =
            new GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
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

    /**
     * Insere o usuario se ainda nao houver, e devolve o id.
     *
     * Os hashes sao strings quaisquer: nenhum teste que usa isto autentica -
     * quem autentica passa pelo cadastro de verdade. O que importa aqui e a
     * ligacao `accounts.owner_id -> users.email`, que e por onde o e-mail de
     * uma conta e encontrado.
     */
    protected Long usuarioDe(String email) {
        Long existente = jdbc.query(
                "SELECT id FROM users WHERE lower(email) = lower(?)",
                rs -> rs.next() ? rs.getLong(1) : null, email);
        if (existente != null) {
            return existente;
        }
        return jdbc.queryForObject(
                "INSERT INTO users (email, password_hash, transaction_password_hash)"
                        + " VALUES (lower(?), 'hash-de-teste', 'hash-transacional-de-teste')"
                        + " RETURNING id",
                Long.class, email);
    }

    /**
     * Conta COM dono. `novaConta` cria conta orfa de proposito, e os testes de
     * e-mail precisam do caminho que leva ao endereco de quem se cadastrou.
     */
    protected Long novaContaComDono(String titular, String saldo, String email) {
        return jdbc.queryForObject(
                "INSERT INTO accounts (holder_name, balance, owner_id) VALUES (?, ?, ?)"
                        + " RETURNING id",
                Long.class, titular, new BigDecimal(saldo), usuarioDe(email));
    }

    protected BigDecimal saldoDe(Long contaId) {
        return jdbc.queryForObject(
                "SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, contaId);
    }
}
