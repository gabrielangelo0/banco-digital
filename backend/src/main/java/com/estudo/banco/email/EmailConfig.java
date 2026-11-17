package com.estudo.banco.email;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Monta o cliente do provedor com tempo-limite.
 *
 * Existe separado do {@link ResendClient} por causa do tempo-limite: o
 * RestClient nao poe nenhum por conta propria, e sem eles uma conexao
 * pendurada no provedor prenderia a thread do agendador para sempre. Como o
 * teste do cliente precisa trocar a fabrica de requisicao pela de mentira, a
 * configuracao fica aqui e a classe fica sem anotacao.
 */
@Configuration
public class EmailConfig {

    private static final Duration CONECTAR = Duration.ofSeconds(5);
    private static final Duration LER = Duration.ofSeconds(10);

    @Bean
    ResendClient resendClient(@Value("${banco.email.base-url}") String baseUrl,
                              @Value("${banco.email.api-key:}") String apiKey,
                              @Value("${banco.email.from}") String remetente) {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(CONECTAR);
        fabrica.setReadTimeout(LER);
        // `RestClient.builder()` e nao um builder injetado: este projeto usa
        // o starter de webmvc, que nao traz a auto-configuracao de cliente
        // REST, e inventar uma dependencia para obter um builder seria caro
        // por nada. O teste do ResendClient monta o seu da mesma forma.
        return new ResendClient(
                RestClient.builder().requestFactory(fabrica), baseUrl, apiKey, remetente);
    }
}
