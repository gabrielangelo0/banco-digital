package com.estudo.banco.demo;

import com.estudo.banco.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sem `banco.demo.enabled`, as rotas de demonstracao nao existem. Um ambiente
 * de verdade nao deve ter uma porta que entra sem senha, nem por engano.
 */
@AutoConfigureMockMvc
class DemonstracaoDesligadaTest extends PostgresTestBase {

    @Autowired
    MockMvc mvc;

    @Test
    void comDemonstracaoDesligadaAsRotasSomem() throws Exception {
        mvc.perform(get("/api/demo")).andExpect(status().isNotFound());
    }
}
