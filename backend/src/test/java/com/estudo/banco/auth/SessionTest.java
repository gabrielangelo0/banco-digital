package com.estudo.banco.auth;

import com.estudo.banco.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@AutoConfigureMockMvc
class SessionTest extends PostgresTestBase {

    @Autowired
    SessionStore sessoes;

    @Autowired
    RegistrationService registration;

    @Autowired
    MockMvc mvc;

    private String email(String prefixo) {
        return prefixo + "-" + UUID.randomUUID() + "@exemplo.com";
    }

    private MvcResult login(String email, String senha) throws Exception {
        return mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + senha + "\"}"))
                .andReturn();
    }

    @Test
    void sessaoResolveEDepoisDeRevogarNaoResolve() {
        String token = sessoes.create(7L);

        assertThat(sessoes.resolve(token)).contains(7L);
        sessoes.revoke(token);
        assertThat(sessoes.resolve(token)).isEmpty();
    }

    @Test
    void sairDeTodosOsDispositivosDerrubaTodasAsSessoes() {
        String a = sessoes.create(8L);
        String b = sessoes.create(8L);

        sessoes.revokeAll(8L);

        assertThat(sessoes.resolve(a)).isEmpty();
        assertThat(sessoes.resolve(b)).isEmpty();
    }

    @Test
    void loginBemSucedidoDevolveCookieDeSessao() throws Exception {
        String endereco = email("fla");
        registration.register(endereco, "senha-forte-1", "123456");

        MvcResult r = login(endereco, "senha-forte-1");

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getCookie("sessao")).isNotNull();
        assertThat(sessoes.resolve(r.getResponse().getCookie("sessao").getValue())).isPresent();
    }

    @Test
    void senhaErradaEUsuarioInexistenteRespondemAMesmaCoisa() throws Exception {
        String endereco = email("gui");
        registration.register(endereco, "senha-forte-1", "123456");

        MvcResult errada = login(endereco, "senha-errada-9");
        MvcResult inexistente = login(email("ninguem"), "senha-errada-9");

        assertThat(errada.getResponse().getStatus())
                .isEqualTo(inexistente.getResponse().getStatus()).isEqualTo(401);
        assertThat(errada.getResponse().getContentAsString())
                .isEqualTo(inexistente.getResponse().getContentAsString());
    }

    @Test
    void cookieNaoEhSecureQuandoARequisicaoNaoEhHttps() {
        // Review Focus 4: producao e HTTPS, `npm run dev` nao e. Um cookie
        // sempre Secure some no desenvolvimento e o login nunca funciona.
        ResponseCookie semTls = SessionCookie.build("token-qualquer", false);
        assertThat(semTls.isSecure()).isFalse();
        assertThat(semTls.isHttpOnly()).isTrue();
        assertThat(semTls.getSameSite()).isEqualTo("Lax");

        assertThat(SessionCookie.build("token-qualquer", true).isSecure()).isTrue();
    }

    @Test
    void cookieEhSecureQuandoOProxyConfiavelDizQueAOrigemEraHttps() throws Exception {
        // Em producao o TLS termina no Caddy e o backend recebe HTTP puro.
        // Com o Secure saindo de request.isSecure(), ele NUNCA e marcado em
        // producao: uma visita a http:// manda o token em texto claro antes
        // do redirecionamento. O teste da funcao isolada nao pega isso -
        // ele prova que build() respeita o parametro, nao que alguem passa
        // true em algum ambiente.
        String endereco = email("sec");
        registration.register(endereco, "senha-forte-1", "123456");

        var resposta = mvc.perform(post("/api/auth/login")
                .header("X-Forwarded-Proto", "https")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + endereco + "\",\"password\":\"senha-forte-1\"}"))
                .andReturn().getResponse();

        assertThat(resposta.getHeader(HttpHeaders.SET_COOKIE)).contains("Secure");
    }

    @Test
    void semProxyDizendoHttpsOCookieNaoEhSecure() throws Exception {
        // A outra metade: `npm run dev` e HTTP, e um cookie sempre Secure
        // simplesmente nao e gravado - o login "funciona" e o usuario volta
        // deslogado, sem erro nenhum.
        String endereco = email("dev");
        registration.register(endereco, "senha-forte-1", "123456");

        var resposta = login(endereco, "senha-forte-1").getResponse();

        assertThat(resposta.getHeader(HttpHeaders.SET_COOKIE)).doesNotContain("Secure");
    }

}
