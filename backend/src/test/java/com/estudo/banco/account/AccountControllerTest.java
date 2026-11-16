package com.estudo.banco.account;

import com.estudo.banco.auth.RegistrationService;
import com.estudo.banco.support.PostgresTestBase;
import com.estudo.banco.support.SessaoDeTeste;
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

    @Autowired
    RegistrationService registration;

    @Test
    void retornaAPropriaContaComSaldo() throws Exception {
        var sessao = SessaoDeTeste.criar(mvc, registration, "conta");

        mvc.perform(get("/api/accounts/" + sessao.contaId()).cookie(sessao.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(1000.0));
    }

    @Test
    void contaQueNaoEhSuaRespondeAcessoNegado() throws Exception {
        // 403 mesmo para id inexistente, de proposito: distinguir "nao e sua"
        // de "nao existe" devolveria um verificador de contas a quem perguntar.
        var sessao = SessaoDeTeste.criar(mvc, registration, "intruso");

        mvc.perform(get("/api/accounts/999999").cookie(sessao.cookie()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACESSO_NEGADO"));
    }

    @Test
    void extratoDeContaNovaVemVazio() throws Exception {
        var sessao = SessaoDeTeste.criar(mvc, registration, "extrato");

        mvc.perform(get("/api/accounts/" + sessao.contaId() + "/statement").cookie(sessao.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
