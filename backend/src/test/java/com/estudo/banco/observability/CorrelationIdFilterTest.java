package com.estudo.banco.observability;

import com.estudo.banco.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sem correlationId, investigar um incidente em producao vira busca por
 * timestamp. Com ele, uma requisicao inteira sai do log com um grep.
 */
@AutoConfigureMockMvc
class CorrelationIdFilterTest extends PostgresTestBase {

    @Autowired
    MockMvc mvc;

    @Test
    void geraCorrelationIdQuandoNaoEnviado() throws Exception {
        var resultado = mvc.perform(get("/api/accounts"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Correlation-Id"))
                .andReturn();

        assertThat(resultado.getResponse().getHeader("X-Correlation-Id")).isNotBlank();
    }

    @Test
    void preservaCorrelationIdEnviado() throws Exception {
        // Vindo de outro servico, o id tem que atravessar a fronteira intacto.
        mvc.perform(get("/api/accounts").header("X-Correlation-Id", "rastro-123"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", "rastro-123"));
    }

    @Test
    void limpaMdcDepoisDaRequisicao() throws Exception {
        mvc.perform(get("/api/accounts")).andExpect(status().isOk());

        // O MDC e ligado a thread, e threads sao reaproveitadas pelo pool. Sem
        // limpar, a proxima requisicao herdaria o id da anterior.
        assertThat(MDC.get("correlationId")).isNull();
    }
}
