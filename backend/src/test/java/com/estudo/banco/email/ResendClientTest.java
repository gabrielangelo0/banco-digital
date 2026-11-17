package com.estudo.banco.email;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.anything;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * O cliente do Resend.
 *
 * Nenhum teste encosta na internet: a URL base e configuravel e aqui ela vai
 * para um servidor de mentira. O que esta sob prova e o contrato com o
 * provedor - cabecalhos, corpo, e a classificacao do erro.
 */
class ResendClientTest {

    private MockRestServiceServer servidor;
    private ResendClient cliente;

    @BeforeEach
    void preparar() {
        RestClient.Builder builder = RestClient.builder();
        servidor = MockRestServiceServer.bindTo(builder).build();
        cliente = new ResendClient(builder, "https://api.resend.com", "re_chave_de_teste",
                "Banco <banco@bank.devfly.net>");
    }

    private EmailParaEnviar mensagem() {
        return new EmailParaEnviar("a@b.com", "Assunto", "<p>oi</p>", "oi", "transfer/x/DEBIT");
    }

    @Test
    void mandaCabecalhosECorpoQueOResendExige() {
        servidor.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer re_chave_de_teste"))
                // Sem User-Agent o Resend responde 403, e cliente HTTP de Java
                // nem sempre manda um por conta propria.
                .andExpect(header("User-Agent", "banco-digital/1.0"))
                .andExpect(header("Idempotency-Key", "transfer/x/DEBIT"))
                .andExpect(jsonPath("$.from").value("Banco <banco@bank.devfly.net>"))
                .andExpect(jsonPath("$.to").value("a@b.com"))
                .andExpect(jsonPath("$.subject").value("Assunto"))
                .andExpect(jsonPath("$.html").value("<p>oi</p>"))
                .andExpect(jsonPath("$.text").value("oi"))
                .andRespond(withSuccess("{\"id\":\"49a3999c-0ce1-4ea6-ab68-afcd6dc2e794\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(cliente.enviar(mensagem())).isEqualTo("49a3999c-0ce1-4ea6-ab68-afcd6dc2e794");
        servidor.verify();
    }

    @Test
    void erroDeServidorEhTemporario() {
        servidor.expect(requestTo(anything())).andRespond(withServerError());

        assertThatThrownBy(() -> cliente.enviar(mensagem()))
                .isInstanceOf(FalhaTemporariaDoProvedor.class);
    }

    @Test
    void limiteDeVazaoEhTemporarioAindaQueSeja4xx() {
        servidor.expect(requestTo(anything()))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> cliente.enviar(mensagem()))
                .isInstanceOf(FalhaTemporariaDoProvedor.class);
    }

    @Test
    void dominioNaoVerificadoEhDefinitivo() {
        servidor.expect(requestTo(anything()))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> cliente.enviar(mensagem()))
                .isInstanceOf(FalhaDefinitivaDoProvedor.class);
    }

    @Test
    void respostaQueOClienteNaoSabeLerEhTemporaria() {
        // 200 com corpo que nao e JSON: pagina de manutencao, interstitial de
        // CDN. Sem classificar, a excecao escapa crua e envenena o lote.
        servidor.expect(requestTo(anything()))
                .andRespond(withSuccess("<html>manutencao</html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> cliente.enviar(mensagem()))
                .isInstanceOf(FalhaTemporariaDoProvedor.class);
    }

    @Test
    void oMotivoDaRecusaFicaNaMensagemDoErro() {
        // "recusou com 403" nao diz por que. O provedor manda o motivo no
        // corpo, e e ele que vai parar no last_error da fila - que existe
        // justamente para responder "por que esse e-mail nao saiu?".
        servidor.expect(requestTo(anything())).andRespond(withStatus(HttpStatus.FORBIDDEN)
                .body("{\"message\":\"The bank.devfly.net domain is not verified.\"}")
                .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> cliente.enviar(mensagem()))
                .isInstanceOf(FalhaDefinitivaDoProvedor.class)
                .hasMessageContaining("403")
                .hasMessageContaining("domain is not verified");
    }

    @Test
    void aChaveNaoApareceNaMensagemDeErro() {
        servidor.expect(requestTo(anything())).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> cliente.enviar(mensagem()))
                .hasMessageNotContaining("re_chave_de_teste");
    }
}
