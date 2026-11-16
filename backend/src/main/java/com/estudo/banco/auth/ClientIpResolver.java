package com.estudo.banco.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * De onde vem o cliente - quando da para confiar na resposta.
 *
 * Com proxies na frente, `getRemoteAddr()` e o IP do proxy, e o IP real vem no
 * X-Forwarded-For. Mas esse cabecalho e escrito pelo cliente quando ninguem o
 * sobrescreve: aceitar de qualquer origem permite forjar IP e, pior, se o
 * cabecalho NAO for confiavel, todo visitante compartilha um unico IP e um
 * atacante sozinho bloqueia todo mundo.
 *
 * Por isso: so le o cabecalho se a conexao veio de um proxy conhecido, e
 * devolve vazio em vez de chutar. Sem IP, o bloqueio por identidade continua
 * valendo - ele e o primario.
 */
@Component
public class ClientIpResolver {

    private final List<String> proxiesConfiaveis;

    public ClientIpResolver(@Value("${banco.proxies-confiaveis:127.0.0.1,::1}") List<String> proxiesConfiaveis) {
        this.proxiesConfiaveis = proxiesConfiaveis;
    }

    public Optional<String> resolve(HttpServletRequest request) {
        String origemDaConexao = request.getRemoteAddr();
        if (origemDaConexao == null || !proxiesConfiaveis.contains(origemDaConexao)) {
            return Optional.empty();
        }

        String encaminhado = request.getHeader("X-Forwarded-For");
        if (encaminhado == null || encaminhado.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(encaminhado.split(",")[0].trim());
    }
}
