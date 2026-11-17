package com.estudo.banco.email;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Fala com a API do Resend por HTTP, sem SDK.
 *
 * A API em vez de SMTP porque ela devolve o identificador da mensagem, aceita
 * chave de idempotencia e erra com codigo: SMTP daria um envelope e silencio.
 *
 * O que este cliente NAO faz: decidir se vale tentar de novo. Ele so classifica
 * a falha; quem decide e o {@code EmailSender}, que tem a mensagem e o
 * historico de tentativas na mao.
 */
public class ResendClient {

    /**
     * Obrigatorio. Requisicao sem User-Agent e recusada com 403, e o cliente
     * HTTP do Java nao manda um por conta propria.
     */
    private static final String IDENTIFICACAO = "banco-digital/1.0";

    private final RestClient http;
    private final String remetente;

    public ResendClient(RestClient.Builder builder,
                        @Value("${banco.email.base-url}") String baseUrl,
                        @Value("${banco.email.api-key:}") String apiKey,
                        @Value("${banco.email.from}") String remetente) {
        this.remetente = remetente;
        this.http = builder
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("User-Agent", IDENTIFICACAO)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /** Devolve o identificador que o Resend deu a mensagem. */
    public String enviar(EmailParaEnviar mensagem) {
        try {
            Map<?, ?> resposta = http.post()
                    .uri("/emails")
                    // A chave identifica a INTENCAO. O Resend a guarda por 24h
                    // e devolve a mesma resposta sem reenviar - a segunda trava,
                    // depois do indice unico na nossa tabela.
                    .header("Idempotency-Key", mensagem.chave())
                    .body(Map.of(
                            "from", remetente,
                            "to", mensagem.destinatario(),
                            "subject", mensagem.assunto(),
                            "html", mensagem.html(),
                            "text", mensagem.texto()))
                    .exchange((pedido, resultado) -> {
                        HttpStatusCode status = resultado.getStatusCode();
                        if (status.is2xxSuccessful()) {
                            return resultado.bodyTo(Map.class);
                        }
                        throw classificar(status, corpoDe(resultado));
                    });

            Object id = resposta == null ? null : resposta.get("id");
            if (id == null) {
                // Dois-centos sem identificador nao e sucesso que se possa
                // registrar: sem o id nao da para rastrear a mensagem depois.
                throw new FalhaTemporariaDoProvedor("resposta sem id do provedor");
            }
            return id.toString();
        } catch (ResourceAccessException e) {
            // Tempo-limite ou rede. A mensagem da excecao carrega a URL, nunca
            // a chave: ela vive em cabecalho, que nao entra aqui.
            throw new FalhaTemporariaDoProvedor("falha de rede ao falar com o provedor", e);
        } catch (FalhaTemporariaDoProvedor | FalhaDefinitivaDoProvedor e) {
            throw e;
        } catch (RuntimeException e) {
            // Classificacao TOTAL, de proposito. O caso mais alcancavel e um
            // 200 cujo corpo nao e JSON - pagina de manutencao, interstitial
            // de CDN -, que estoura na leitura e escapava cru. Resposta que
            // nao sei ler e "nao sei, tente mais tarde", nunca "pare a fila".
            throw new FalhaTemporariaDoProvedor(
                    "resposta do provedor que nao consegui interpretar", e);
        }
    }

    private RuntimeException classificar(HttpStatusCode status, String corpo) {
        // 429 e 4xx mas quer dizer "mais devagar", nao "pedido errado".
        if (status.is5xxServerError() || status.value() == 429) {
            return new FalhaTemporariaDoProvedor(
                    "provedor respondeu " + status.value() + ": " + corpo);
        }
        return new FalhaDefinitivaDoProvedor(
                "provedor recusou com " + status.value() + ": " + corpo);
    }

    /**
     * O motivo que o provedor mandou, para ir parar no `last_error` da fila.
     *
     * Sem ele, "recusou com 403" nao diz se o dominio nao esta verificado, se
     * a chave foi revogada ou se o destinatario e invalido - e a fila existe
     * justamente para responder "por que esse e-mail nao saiu?". O corpo da
     * resposta nao carrega a chave: ela viaja em cabecalho.
     */
    private String corpoDe(ConvertibleClientHttpResponse resultado) {
        try {
            String corpo = resultado.bodyTo(String.class);
            return corpo == null || corpo.isBlank() ? "(sem corpo)" : corpo.strip();
        } catch (RuntimeException e) {
            return "(corpo ilegivel)";
        }
    }
}
