package com.estudo.banco.auth;

import com.estudo.banco.notification.NotificationService;
import com.estudo.banco.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class EscopoDeRotasTest extends PostgresTestBase {

    @Autowired
    MockMvc mvc;

    @Autowired
    RegistrationService registration;

    @Autowired
    NotificationService notificacoes;

    private record Sessao(Long contaId, MockCookie cookie) {
    }

    private Sessao entrar(String prefixo) throws Exception {
        String email = prefixo + "-" + UUID.randomUUID() + "@exemplo.com";
        Long contaId = registration.register(email, "senha-forte-1", "123456");

        var resposta = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"senha-forte-1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        return new Sessao(contaId, (MockCookie) resposta.getCookie(SessionCookie.NOME));
    }

    @Test
    void listarContasDevolveApenasAContaDoUsuario() throws Exception {
        Sessao minha = entrar("fab");
        entrar("gil");

        mvc.perform(get("/api/accounts").cookie(minha.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(minha.contaId()));
    }

    @Test
    void extratoDeOutraContaEhRecusado() throws Exception {
        Sessao dono = entrar("hugo");
        Sessao intruso = entrar("iva");

        mvc.perform(get("/api/accounts/" + dono.contaId() + "/statement").cookie(intruso.cookie()))
                .andExpect(status().isForbidden());
    }

    @Test
    void contaDeOutroUsuarioEhRecusada() throws Exception {
        Sessao dono = entrar("jo");
        Sessao intruso = entrar("kim");

        mvc.perform(get("/api/accounts/" + dono.contaId()).cookie(intruso.cookie()))
                .andExpect(status().isForbidden());
    }

    @Test
    void semSessaoNenhumaRotaDeApiResponde() throws Exception {
        mvc.perform(get("/api/accounts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notifications/stream")).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutEncerraOStreamJaAberto() throws Exception {
        // Review Focus 1: o stream foi aberto com sessao valida. Revogar precisa
        // encerrar o emitter, nao so impedir conexoes novas - senao a sessao
        // morta continua recebendo movimentacao da conta.
        Sessao sessao = entrar("lua");

        notificacoes.subscribe(sessao.contaId());
        assertThat(notificacoes.quantidadeDeInscritos(sessao.contaId())).isEqualTo(1);

        mvc.perform(post("/api/auth/logout").cookie(sessao.cookie()))
                .andExpect(status().isNoContent());

        // A inscricao deixou de existir: `notificar` nao tem mais a quem
        // entregar, que e a propriedade que importa. Nao verifico o callback
        // `onCompletion` porque um emitter criado fora de uma requisicao
        // assincrona nunca o dispara - seria um teste que mede o arranjo do
        // teste, nao o comportamento.
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(notificacoes.quantidadeDeInscritos(sessao.contaId())).isZero());
    }
}
