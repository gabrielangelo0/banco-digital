package com.estudo.banco.transfer;

import com.estudo.banco.auth.RegistrationService;
import com.estudo.banco.support.PostgresTestBase;
import com.estudo.banco.support.SessaoDeTeste;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Idempotencia resolve REPETICAO, nao simultaneidade.
 *
 * O celular perde o sinal antes de receber a resposta e o app tenta de novo:
 * chegam duas requisicoes legitimas e bem formadas. Lock nenhum impede isso -
 * ele faz exatamente o que deve, serializa as duas e executa as duas.
 */
@AutoConfigureMockMvc
class IdempotencyTest extends PostgresTestBase {

    @Autowired
    TransferService transfers;

    @Autowired
    MockMvc mvc;

    @Autowired
    RegistrationService registration;

    private SessaoDeTeste.Sessao sessao() throws Exception {
        return SessaoDeTeste.criar(mvc, registration, "idem");
    }

    @Test
    void mesmaChaveDuasVezesMoveDinheiroUmaVez() {
        Long origem = novaConta("Origem", "1000.00");
        Long destino = novaConta("Destino", "0.00");
        String chave = UUID.randomUUID().toString();

        TransferResult primeira = transfers.execute(
                new TransferCommand(origem, destino, new BigDecimal("100.00"), chave));
        TransferResult segunda = transfers.execute(
                new TransferCommand(origem, destino, new BigDecimal("100.00"), chave));

        // A segunda chamada devolve a resposta ORIGINAL, nao um erro: do ponto
        // de vista do app a operacao deu certo, e deu mesmo.
        assertThat(segunda.transferId()).isEqualTo(primeira.transferId());
        assertThat(saldoDe(origem)).isEqualByComparingTo("900.00");
        assertThat(saldoDe(destino)).isEqualByComparingTo("100.00");
    }

    @Test
    void chavesDiferentesMovemDuasVezes() {
        Long origem = novaConta("Origem", "1000.00");
        Long destino = novaConta("Destino", "0.00");

        TransferResult primeira = transfers.execute(new TransferCommand(
                origem, destino, new BigDecimal("100.00"), UUID.randomUUID().toString()));
        TransferResult segunda = transfers.execute(new TransferCommand(
                origem, destino, new BigDecimal("100.00"), UUID.randomUUID().toString()));

        assertThat(segunda.transferId()).isNotEqualTo(primeira.transferId());
        assertThat(saldoDe(origem)).isEqualByComparingTo("800.00");
        assertThat(saldoDe(destino)).isEqualByComparingTo("200.00");
    }

    @Test
    void mesmaChaveEmParaleloMoveUmaVez() throws Exception {
        Long origem = novaConta("Origem", "1000.00");
        Long destino = novaConta("Destino", "0.00");
        String chave = UUID.randomUUID().toString();

        int tentativas = 10;
        CountDownLatch largada = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(tentativas);
        List<Future<TransferResult>> futuros = new ArrayList<>();
        List<Throwable> falhas = Collections.synchronizedList(new ArrayList<>());

        try {
            for (int i = 0; i < tentativas; i++) {
                futuros.add(pool.submit(() -> {
                    largada.await();
                    return transfers.execute(new TransferCommand(
                            origem, destino, new BigDecimal("100.00"), chave));
                }));
            }
            largada.countDown();

            Set<UUID> ids = Collections.synchronizedSet(new java.util.HashSet<>());
            for (Future<TransferResult> f : futuros) {
                try {
                    ids.add(f.get(30, TimeUnit.SECONDS).transferId());
                } catch (ExecutionException e) {
                    falhas.add(e.getCause());
                }
            }

            assertThat(falhas).isEmpty();
            // Quem perdeu a corrida na unique constraint tem a transacao
            // desfeita e passa a devolver a resposta de quem venceu.
            assertThat(ids).hasSize(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(saldoDe(origem)).isEqualByComparingTo("900.00");
        assertThat(saldoDe(destino)).isEqualByComparingTo("100.00");
    }

    @Test
    void headerAusenteRetorna400() throws Exception {
        // A origem e a conta da sessao: quem se cadastra nasce com 1000.
        var sessao = sessao();
        Long origem = sessao.contaId();
        Long destino = novaConta("Destino", "0.00");

        mvc.perform(post("/api/transfers").cookie(sessao.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(destino, "10.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Idempotency-Key")));

        assertThat(saldoDe(origem)).isEqualByComparingTo("1000.00");
    }

    @Test
    void headerEmBrancoRetorna400() throws Exception {
        var sessao = sessao();
        Long origem = sessao.contaId();
        Long destino = novaConta("Destino", "0.00");

        mvc.perform(post("/api/transfers").cookie(sessao.cookie())
                        .header("Idempotency-Key", "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(destino, "10.00")))
                .andExpect(status().isBadRequest());

        assertThat(saldoDe(origem)).isEqualByComparingTo("1000.00");
    }

    @Test
    void requisicaoValidaViaHttpRetorna200() throws Exception {
        var sessao = sessao();
        Long origem = sessao.contaId();
        Long destino = novaConta("Destino", "0.00");

        mvc.perform(post("/api/transfers").cookie(sessao.cookie())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(destino, "250.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        assertThat(saldoDe(origem)).isEqualByComparingTo("750.00");
    }

    private String corpo(Long destino, String valor) {
        // Sem sourceAccountId: a origem e a conta da sessao. A senha
        // transacional e a que SessaoDeTeste cadastra.
        return """
                {"targetAccountId": %d, "amount": %s, "transactionPassword": "123456"}
                """.formatted(destino, valor);
    }
}
