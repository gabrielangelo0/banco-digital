package com.estudo.banco.transfer;

import com.estudo.banco.auth.RegistrationService;
import com.estudo.banco.support.PostgresTestBase;
import com.estudo.banco.support.SessaoDeTeste;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A transferencia deixou de dizer de onde sai: a origem e a conta da sessao.
 * O que o corpo carrega agora e para onde vai, quanto, e a prova de que quem
 * esta logado tambem pode mover dinheiro.
 */
@AutoConfigureMockMvc
class TransferAutenticadaTest extends PostgresTestBase {

    @Autowired
    MockMvc mvc;

    @Autowired
    RegistrationService registration;

    private ResultActions transferir(SessaoDeTeste.Sessao sessao, Long destino,
                                     String valor, String senhaTransacional) throws Exception {
        return mvc.perform(post("/api/transfers")
                .cookie(sessao.cookie())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetAccountId\":" + destino
                        + ",\"amount\":\"" + valor + "\""
                        + ",\"transactionPassword\":\"" + senhaTransacional + "\"}"));
    }

    private BigDecimal saldo(Long contaId) {
        return jdbc.queryForObject("select balance from accounts where id = ?", BigDecimal.class, contaId);
    }

    private BigDecimal somaDoSistema() {
        return jdbc.queryForObject("select coalesce(sum(balance), 0) from accounts", BigDecimal.class);
    }

    @Test
    void transferenciaAutenticadaSaiDaContaDaSessao() throws Exception {
        var sessao = SessaoDeTeste.criar(mvc, registration, "origem");
        Long destino = novaConta("Destino", "0.00");

        transferir(sessao, destino, "250.00", "123456")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceBalance").value(750.0));

        assertThat(saldo(destino)).isEqualByComparingTo("250.0000");
    }

    @Test
    void senhaTransacionalErradaNaoMoveDinheiro() throws Exception {
        var sessao = SessaoDeTeste.criar(mvc, registration, "senha");
        Long destino = novaConta("Destino", "0.00");
        BigDecimal antes = saldo(sessao.contaId());

        transferir(sessao, destino, "10.00", "senha-errada")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SENHA_TRANSACIONAL_INVALIDA"));

        assertThat(saldo(sessao.contaId())).isEqualByComparingTo(antes);
        assertThat(saldo(destino)).isEqualByComparingTo("0.0000");
    }

    @Test
    void contaDestinoInexistenteDevolveErroLegivel() throws Exception {
        // Review Focus 2: a critica mais votada do app do benchmark e erro de
        // transferencia sem mensagem inteligivel. 500 sem corpo e o pior caso.
        var sessao = SessaoDeTeste.criar(mvc, registration, "destino");

        transferir(sessao, 999999L, "10.00", "123456")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONTA_NAO_ENCONTRADA"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void naoEhPossivelTransferirParaAPropriaConta() throws Exception {
        var sessao = SessaoDeTeste.criar(mvc, registration, "mesma");

        transferir(sessao, sessao.contaId(), "10.00", "123456")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TRANSFERENCIA_INVALIDA"));
    }

    @Test
    void somaDoSistemaSegueInvarianteDepoisDeTransferenciaAutenticada() throws Exception {
        var sessao = SessaoDeTeste.criar(mvc, registration, "invariante");
        Long destino = novaConta("Destino", "0.00");
        BigDecimal antes = somaDoSistema();

        transferir(sessao, destino, "42.50", "123456").andExpect(status().isOk());

        assertThat(somaDoSistema()).isEqualByComparingTo(antes);
    }
}
