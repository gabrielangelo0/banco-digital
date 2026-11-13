package com.estudo.banco.transfer;

import com.estudo.banco.ledger.EntryRepository;
import com.estudo.banco.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Collections;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * As provas de concorrencia.
 *
 * Estes testes nao exercitam o caminho feliz - eles ATACAM as garantias:
 * disparam dezenas de threads ao mesmo tempo e afirmam que o dinheiro nao se
 * perde, nao se multiplica e que ninguem morre de deadlock.
 */
class TransferConcurrencyTest extends PostgresTestBase {

    private static final String SQLSTATE_DEADLOCK_DETECTED = "40P01";

    @Autowired
    TransferService transfers;

    @Autowired
    EntryRepository entries;

    // ------------------------------------------------------------------
    // 1. LOST UPDATE
    // ------------------------------------------------------------------

    @Test
    void cemTransferenciasSimultaneasNaoPerdemDinheiro() throws Exception {
        Long origem = novaConta("Origem", "1000.00");
        Long destino = novaConta("Destino", "0.00");

        int operacoes = 100;
        List<Throwable> falhas = dispararEmParalelo(operacoes, 16, () ->
                transfers.execute(new TransferCommand(
                        origem, destino, new BigDecimal("1.00"), UUID.randomUUID().toString())));

        assertThat(falhas).isEmpty();

        // Sem o SELECT ... FOR UPDATE, varias threads leriam o mesmo saldo
        // antigo e sobrescreveriam umas as outras: o saldo final ficaria ACIMA
        // de 900, com dinheiro criado do nada.
        assertThat(saldoDe(origem)).isEqualByComparingTo("900.00");
        assertThat(saldoDe(destino)).isEqualByComparingTo("100.00");
    }

    @Test
    void saldoBateComORazaoDepoisDaTempestade() throws Exception {
        Long origem = novaConta("Origem", "1000.00");
        Long destino = novaConta("Destino", "0.00");

        dispararEmParalelo(60, 12, () ->
                transfers.execute(new TransferCommand(
                        origem, destino, new BigDecimal("2.00"), UUID.randomUUID().toString())));

        // Reconciliacao sob concorrencia: o saldo materializado continua
        // concordando com a soma do razao.
        assertThat(saldoDe(origem))
                .isEqualByComparingTo(new BigDecimal("1000.00").add(entries.sumAmountByAccountId(origem)));
        assertThat(saldoDe(destino))
                .isEqualByComparingTo(new BigDecimal("0.00").add(entries.sumAmountByAccountId(destino)));
    }

    // ------------------------------------------------------------------
    // 2. DEADLOCK
    // ------------------------------------------------------------------

    @Test
    void transferenciasCruzadasNaoCausamDeadlock() throws Exception {
        Long contaA = novaConta("A", "1000.00");
        Long contaB = novaConta("B", "1000.00");

        List<Callable<Void>> tarefas = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            tarefas.add(() -> {
                transfers.execute(new TransferCommand(
                        contaA, contaB, new BigDecimal("1.00"), UUID.randomUUID().toString()));
                return null;
            });
            tarefas.add(() -> {
                transfers.execute(new TransferCommand(
                        contaB, contaA, new BigDecimal("1.00"), UUID.randomUUID().toString()));
                return null;
            });
        }

        List<Throwable> falhas = dispararTarefas(tarefas, 16);

        // Com a ordenacao por id, o ciclo de espera nao pode se formar.
        assertThat(falhas).isEmpty();

        // Conservacao: 50 idas e 50 voltas de 1.00 se anulam.
        assertThat(saldoDe(contaA)).isEqualByComparingTo("1000.00");
        assertThat(saldoDe(contaB)).isEqualByComparingTo("1000.00");
    }

    /**
     * A prova de que a ORDEM e o que resolve, e nao sorte.
     *
     * Duas conexoes, cada uma travando sua primeira conta, uma barreira para
     * garantir que as duas ja seguram um lock, e so entao cada uma pede o
     * segundo. Travando em ordem OPOSTA o ciclo e inevitavel e o Postgres mata
     * uma das transacoes. Travando em ordem CRESCENTE, ninguem morre.
     *
     * A coreografia com barreira torna o resultado deterministico: nao depende
     * de o escalonador intercalar as threads do jeito certo.
     */
    @Test
    void ordemOpostaCausaDeadlockEOrdemCrescenteNao() throws Exception {
        Long contaA = novaConta("A", "1000.00");
        Long contaB = novaConta("B", "1000.00");
        long menor = Math.min(contaA, contaB);
        long maior = Math.max(contaA, contaB);

        // --- ordem OPOSTA: deadlock garantido ---
        // Barreira ativa: as duas transacoes pegam linhas DIFERENTES primeiro,
        // entao a barreira garante que ambas seguram um lock antes de qualquer
        // uma pedir o segundo. O ciclo e inevitavel.
        int deadlocksOrdemOposta = travarEmPares(menor, maior, maior, menor, true);
        assertThat(deadlocksOrdemOposta)
                .as("travar em ordem oposta tem que produzir exatamente um deadlock")
                .isEqualTo(1);

        // --- ordem CRESCENTE: o que a producao faz ---
        // Sem barreira, e nao por conveniencia: na ordem crescente as duas
        // transacoes querem a MESMA primeira linha, entao uma delas nunca
        // chegaria a barreira. Essa impossibilidade e justamente a garantia -
        // a segunda transacao espera na porta de entrada, com um unico lock na
        // mao, e nao ha ciclo possivel.
        int deadlocksOrdemCrescente = travarEmPares(menor, maior, menor, maior, false);
        assertThat(deadlocksOrdemCrescente)
                .as("travar em ordem crescente nao pode produzir deadlock")
                .isZero();
    }

    /**
     * Sobe duas transacoes: a primeira trava primeiro1 e depois primeiro2, a
     * segunda trava segundo1 e depois segundo2. A barreira garante que ambas
     * seguram o primeiro lock antes de qualquer uma pedir o segundo.
     *
     * Com usarBarreira = false a barreira e neutralizada (1 parte), para o
     * caso em que as duas disputam a mesma primeira linha.
     *
     * @return quantas transacoes morreram com deadlock detectado
     */
    private int travarEmPares(long primeiro1, long primeiro2, long segundo1, long segundo2,
                              boolean usarBarreira) throws Exception {
        // Barreira de 1 parte: await() libera na hora, mantendo um unico
        // caminho de codigo para os dois cenarios.
        CyclicBarrier ambosTravaram = new CyclicBarrier(usarBarreira ? 2 : 1);
        AtomicInteger deadlocks = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            Future<Void> t1 = pool.submit(() -> travarDuasLinhas(primeiro1, primeiro2, ambosTravaram, deadlocks));
            Future<Void> t2 = pool.submit(() -> travarDuasLinhas(segundo1, segundo2, ambosTravaram, deadlocks));
            t1.get(30, TimeUnit.SECONDS);
            t2.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        return deadlocks.get();
    }

    private Void travarDuasLinhas(long idA, long idB, CyclicBarrier barreira, AtomicInteger deadlocks)
            throws Exception {
        // Conexoes DIRETAS ao container, fora do pool da aplicacao.
        //
        // Este teste segura dois locks parados numa barreira. Usando o pool do
        // Hikari (10 conexoes, compartilhado com os outros testes da classe),
        // uma thread ficava esperando conexao enquanto a outra ja segurava a
        // sua: a barreira estourava antes de qualquer deadlock se formar, e o
        // teste falhava por contencao de pool, nao por deadlock.
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            conn.setAutoCommit(false);
            // A aplicacao roda com lock_timeout = 3s, mas ESTE teste investiga
            // deteccao de deadlock: se o lock_timeout cortar primeiro, o teste
            // passa a medir contencao em vez de deadlock. Desligado so aqui,
            // para que o detector do Postgres seja o unico a desempatar.
            try (var stmt = conn.createStatement()) {
                stmt.execute("SET lock_timeout = 0");
            }
            try {
                diag("pedindo lock 1 em " + idA);
                travarLinha(conn, idA);
                diag("TEM lock 1 em " + idA + ", esperando barreira");
                barreira.await(20, TimeUnit.SECONDS);
                diag("barreira liberada, pedindo lock 2 em " + idB);
                travarLinha(conn, idB);
                diag("TEM lock 2 em " + idB + ", commit");
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                if (SQLSTATE_DEADLOCK_DETECTED.equals(e.getSQLState())) {
                    deadlocks.incrementAndGet();
                } else {
                    throw new IllegalStateException(
                            "esperado deadlock (40P01), veio SQLState " + e.getSQLState()
                                    + ": " + e.getMessage(), e);
                }
            }
        }
        return null;
    }

    private static void diag(String msg) {
        System.err.println("[diag " + System.currentTimeMillis() % 1000000 + " "
                + Thread.currentThread().getName() + "] " + msg);
    }

    private void travarLinha(Connection conn, long contaId) throws SQLException {
        try (var ps = conn.prepareStatement("SELECT id FROM accounts WHERE id = ? FOR UPDATE")) {
            ps.setLong(1, contaId);
            ps.executeQuery().close();
        }
    }

    // ------------------------------------------------------------------
    // 3. INVARIANTE GLOBAL
    // ------------------------------------------------------------------

    @Test
    void somaTotalDoSistemaEhInvariante() throws Exception {
        Long contaA = novaConta("A", "700.00");
        Long contaB = novaConta("B", "300.00");
        BigDecimal totalInicial = saldoDe(contaA).add(saldoDe(contaB));

        List<Callable<Void>> tarefas = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            boolean paraFrente = i % 2 == 0;
            Long de = paraFrente ? contaA : contaB;
            Long para = paraFrente ? contaB : contaA;
            tarefas.add(() -> {
                transfers.execute(new TransferCommand(
                        de, para, new BigDecimal("5.00"), UUID.randomUUID().toString()));
                return null;
            });
        }
        dispararTarefas(tarefas, 8);

        // Dinheiro nao nasce nem morre: so muda de lugar.
        assertThat(saldoDe(contaA).add(saldoDe(contaB))).isEqualByComparingTo(totalInicial);
        assertThat(entries.sumAmountByAccountId(contaA).add(entries.sumAmountByAccountId(contaB)))
                .isEqualByComparingTo("0.00");
    }

    // ------------------------------------------------------------------
    // infraestrutura dos testes
    // ------------------------------------------------------------------

    private List<Throwable> dispararEmParalelo(int quantidade, int threads, Runnable acao)
            throws Exception {
        List<Callable<Void>> tarefas = new ArrayList<>();
        for (int i = 0; i < quantidade; i++) {
            tarefas.add(() -> {
                acao.run();
                return null;
            });
        }
        return dispararTarefas(tarefas, threads);
    }

    /** Largada simultanea de verdade: todas as threads esperam o mesmo latch. */
    private List<Throwable> dispararTarefas(List<Callable<Void>> tarefas, int threads)
            throws Exception {
        CountDownLatch largada = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Void>> futuros = new ArrayList<>();
        List<Throwable> falhas = Collections.synchronizedList(new ArrayList<>());

        try {
            for (Callable<Void> tarefa : tarefas) {
                futuros.add(pool.submit(() -> {
                    largada.await();
                    tarefa.call();
                    return null;
                }));
            }
            largada.countDown();
            for (Future<Void> f : futuros) {
                try {
                    f.get(60, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    falhas.add(e.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return falhas;
    }
}
