package com.estudo.banco.account;

import com.estudo.banco.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AccountControllerTest extends PostgresTestBase {

    @Autowired
    MockMvc mvc;

    @Test
    void retornaContaComSaldo() throws Exception {
        Long id = novaConta("Gabriel", "1000.00");

        mvc.perform(get("/api/accounts/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holderName").value("Gabriel"))
                .andExpect(jsonPath("$.balance").value(1000.0));
    }

    @Test
    void contaInexistenteRetorna404() throws Exception {
        mvc.perform(get("/api/accounts/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONTA_NAO_ENCONTRADA"));
    }

    @Test
    void extratoDeContaNovaVemVazio() throws Exception {
        Long id = novaConta("Maria", "500.00");

        mvc.perform(get("/api/accounts/" + id + "/statement"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
