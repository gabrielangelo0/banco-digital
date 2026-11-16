package com.estudo.banco.auth;

import com.estudo.banco.support.PostgresTestBase;
import com.estudo.banco.support.SessaoDeTeste;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * A critica mais votada do app do benchmark e acesso falhando no dia do
 * pagamento. Bloqueio existe para conter forca bruta - e precisa conter o
 * atacante sem derrubar quem so errou a senha.
 */
@AutoConfigureMockMvc
class BloqueioProgressivoTest extends PostgresTestBase {

    @Autowired
    MockMvc mvc;

    @Autowired
    RegistrationService registration;

    @Autowired
    AttemptLimiter limiter;

    @Autowired
    ClientIpResolver resolver;

    private String cadastrar(String prefixo) {
        String email = prefixo + "-" + UUID.randomUUID() + "@exemplo.com";
        registration.register(email, "senha-forte-1", "123456");
        return email;
    }

    private int login(String email, String senha) throws Exception {
        return mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + senha + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void depoisDeCincoFalhasAIdentidadeFicaBloqueada() throws Exception {
        String email = cadastrar("mia");

        for (int i = 0; i < 5; i++) {
            assertThat(login(email, "senha-errada-9")).isEqualTo(401);
        }

        // A senha agora esta CERTA, e mesmo assim nao entra: o bloqueio e
        // sobre a identidade, nao sobre o palpite.
        assertThat(login(email, "senha-forte-1")).isEqualTo(429);
    }

    @Test
    void bloqueioDeUmaIdentidadeNaoAtingeOutra() throws Exception {
        String bloqueada = cadastrar("noa");
        String outra = cadastrar("ovi");

        for (int i = 0; i < 5; i++) {
            login(bloqueada, "senha-errada-9");
        }

        assertThat(login(outra, "senha-forte-1")).isEqualTo(200);
    }

    @Test
    void loginBemSucedidoLimpaAsFalhas() throws Exception {
        String email = cadastrar("pam");

        for (int i = 0; i < 3; i++) {
            login(email, "senha-errada-9");
        }
        assertThat(login(email, "senha-forte-1")).isEqualTo(200);

        assertThat(limiter.bloqueado("login:" + email)).isFalse();
    }

    @Test
    void semProxyConfiavelOIpEhIgnoradoEmVezDeBloquearTodoMundo() {
        // Com dois proxies na frente, se o X-Forwarded-For nao vier de um
        // proxy conhecido, todo visitante compartilharia um IP - e um unico
        // atacante bloquearia todo mundo de uma vez.
        MockHttpServletRequest desconhecido = new MockHttpServletRequest();
        desconhecido.setRemoteAddr("203.0.113.9");
        desconhecido.addHeader("X-Forwarded-For", "198.51.100.7");

        assertThat(resolver.resolve(desconhecido)).isEmpty();
    }

    @Test
    void comProxyConfiavelOPrimeiroIpDaCadeiaEhUsado() {
        MockHttpServletRequest confiavel = new MockHttpServletRequest();
        confiavel.setRemoteAddr("127.0.0.1");
        confiavel.addHeader("X-Forwarded-For", "198.51.100.7, 10.0.0.3");

        assertThat(resolver.resolve(confiavel)).contains("198.51.100.7");
    }

    @Test
    void senhaTransacionalErradaCincoVezesBloqueiaAConta() throws Exception {
        var sessao = SessaoDeTeste.criar(mvc, registration, "qia");
        Long destino = novaConta("Destino", "0.00");

        for (int i = 0; i < 5; i++) {
            assertThat(transferir(sessao, destino, "errada")).isEqualTo(403);
        }

        // Senha transacional agora CERTA, e ainda assim recusada: o bloqueio
        // da autorizacao e independente do bloqueio do login.
        assertThat(transferir(sessao, destino, "123456")).isEqualTo(429);
    }

    private int transferir(SessaoDeTeste.Sessao sessao, Long destino, String senhaTransacional)
            throws Exception {
        return mvc.perform(post("/api/transfers")
                .cookie(sessao.cookie())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetAccountId\":" + destino
                        + ",\"amount\":\"1.00\",\"transactionPassword\":\"" + senhaTransacional + "\"}"))
                .andReturn().getResponse().getStatus();
    }
}
