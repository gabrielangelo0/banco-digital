package com.estudo.banco.demo;

import com.estudo.banco.auth.RegistrationService;
import com.estudo.banco.support.PostgresTestBase;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import com.estudo.banco.support.SessaoDeTeste;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O demo precisa ser entravel em um clique. Recrutador nenhum cria cadastro
 * para ver um projeto - e exigir cadastro e a diferenca entre ser visto e nao
 * ser.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "banco.demo.enabled=true",
        "banco.demo.password=demo-senha-de-teste",
        "banco.demo.transaction-password=demo123"
})
class EntradaDeVisitanteTest extends PostgresTestBase {

    @Autowired
    MockMvc mvc;

    @Autowired
    RegistrationService registration;

    private Cookie entrarComoVisitante(long contaId) throws Exception {
        return mvc.perform(post("/api/auth/guest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\":" + contaId + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("sessao");
    }

    @Test
    void visitanteEntraEmUmCliqueEVeApenasAContaEscolhida() throws Exception {
        Cookie cookie = entrarComoVisitante(1L);

        assertThat(cookie).isNotNull();
        mvc.perform(get("/api/accounts").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(1));
    }

    @Test
    void visitanteSoEntraEmContaDeDemonstracao() throws Exception {
        Long registrada = registration.register(
                "olga-" + UUID.randomUUID() + "@exemplo.com", "senha-forte-1", "654321");

        mvc.perform(post("/api/auth/guest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\":" + registrada + "}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aSenhaTransacionalDaDemonstracaoEhExposta() throws Exception {
        // Exposta de proposito, e nao dispensada: um atalho para a sessao de
        // visitante seria uma brecha que alguem acharia em producao.
        mvc.perform(get("/api/demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionPassword").value("demo123"))
                .andExpect(jsonPath("$.accounts.length()").value(2));
    }

    @Test
    void contaDeDemonstracaoNaoTransfereParaContaRegistrada() throws Exception {
        // Review Focus 5: sem esta regra o dinheiro escapa do par de contas de
        // demonstracao e o reset deixa de fechar.
        Cookie visitante = entrarComoVisitante(1L);
        Long registrada = registration.register(
                "lia-" + UUID.randomUUID() + "@exemplo.com", "senha-forte-1", "654321");

        mvc.perform(post("/api/transfers")
                .cookie(visitante)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetAccountId\":" + registrada
                        + ",\"amount\":\"1.00\",\"transactionPassword\":\"demo123\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DESTINO_FORA_DA_DEMONSTRACAO"));
    }

    @Test
    void entreContasDeDemonstracaoATransferenciaFunciona() throws Exception {
        Cookie visitante = entrarComoVisitante(1L);

        mvc.perform(post("/api/transfers")
                .cookie(visitante)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetAccountId\":2,\"amount\":\"1.00\",\"transactionPassword\":\"demo123\"}"))
                .andExpect(status().isOk());
    }

    @Autowired
    StringRedisTemplate redis;

    @Test
    void contaRegistradaNaoTransfereParaContaDeDemonstracao() throws Exception {
        // A regra tem DUAS direcoes. Bloquear so a saida deixa o dinheiro
        // ENTRAR no par: a soma do par muda, e o reset horario passa a nunca
        // mais fechar - o placeholder do proprio campo sugere digitar "2".
        var sessao = SessaoDeTeste.criar(mvc, registration, "ari");

        mvc.perform(post("/api/transfers")
                .cookie(sessao.cookie())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetAccountId\":2,\"amount\":\"1.00\",\"transactionPassword\":\"123456\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DESTINO_FORA_DA_DEMONSTRACAO"));
    }

    @Test
    void sessaoDeVisitanteExpiraRapido() throws Exception {
        // Visitante nunca sai: cada clique deixa uma sessao de 7 dias no
        // Redis, e nada a revoga. Num demo publico isso e estado ilimitado
        // num Redis com teto de memoria.
        Cookie cookie = entrarComoVisitante(1L);

        Long segundos = redis.getExpire("sessao:" + cookie.getValue());

        assertThat(segundos).isNotNull();
        assertThat(segundos).isLessThanOrEqualTo(Duration.ofHours(2).toSeconds());
        // E o cookie expira junto. Senao o navegador do visitante guarda por
        // sete dias um token que o servidor ja esqueceu.
        assertThat(cookie.getMaxAge()).isLessThanOrEqualTo((int) Duration.ofHours(2).toSeconds());
    }

    @Test
    void entradaDeVisitanteTemTetoPorIp() throws Exception {
        String ip = "198.51.100.44";
        for (int i = 0; i < 20; i++) {
            mvc.perform(post("/api/auth/guest")
                    .header("X-Forwarded-For", ip)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":1}"))
                    .andExpect(status().isOk());
        }

        mvc.perform(post("/api/auth/guest")
                .header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\":1}"))
                .andExpect(status().isTooManyRequests());
    }

}
