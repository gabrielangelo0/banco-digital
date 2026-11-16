package com.estudo.banco.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.List;
import java.util.Optional;

/**
 * O que o proxy conta sobre a requisicao - quando da para confiar nele.
 *
 * Duas perguntas dependem disso, e as duas quebram em silencio quando a
 * resposta e chutada:
 *
 * 1. De onde vem o cliente. Com proxies na frente, `getRemoteAddr()` e o IP
 *    do proxy e o IP real vem no X-Forwarded-For. Esse cabecalho e escrito
 *    pelo cliente quando ninguem o sobrescreve: aceitar de qualquer origem
 *    permite forjar IP e, pior, se o cabecalho NAO for confiavel, todo
 *    visitante compartilha um unico IP e um atacante sozinho bloqueia todos.
 *
 * 2. Se a origem era HTTPS. O TLS termina no proxy, entao o backend recebe
 *    HTTP puro e `request.isSecure()` e false em producao. Marcar o cookie
 *    de sessao como Secure a partir dele significa nunca marcar.
 *
 * Por isso a mesma decisao de confianca serve as duas, e sem proxy conhecido
 * as duas respondem "nao sei" em vez de chutar.
 *
 * A faixa default cobre os enderecos privados porque na topologia real o
 * backend ve o IP do container do proxy na rede do compose - 172.x, que muda
 * a cada recriacao. Nada mais alcanca a porta do backend: ela nao e publicada
 * no host, so existe dentro da rede interna.
 */
@Component
public class ClientIpResolver {

    private final List<String> proxiesConfiaveis;

    public ClientIpResolver(
            @Value("${banco.proxies-confiaveis:127.0.0.1,::1,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16}")
            List<String> proxiesConfiaveis) {
        this.proxiesConfiaveis = proxiesConfiaveis;
    }

    public Optional<String> resolve(HttpServletRequest request) {
        if (!deProxyConfiavel(request)) {
            return Optional.empty();
        }
        String encaminhado = request.getHeader("X-Forwarded-For");
        if (encaminhado == null || encaminhado.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(encaminhado.split(",")[0].trim());
    }

    /** A requisicao chegou ao navegador por HTTPS? */
    public boolean ehHttps(HttpServletRequest request) {
        if (request.isSecure()) {
            return true;
        }
        if (!deProxyConfiavel(request)) {
            return false;
        }
        String protocolo = request.getHeader("X-Forwarded-Proto");
        return protocolo != null && "https".equalsIgnoreCase(protocolo.split(",")[0].trim());
    }

    private boolean deProxyConfiavel(HttpServletRequest request) {
        String origemDaConexao = request.getRemoteAddr();
        if (origemDaConexao == null) {
            return false;
        }
        for (String regra : proxiesConfiaveis) {
            String limpa = regra.trim();
            if (limpa.isEmpty()) {
                continue;
            }
            if (limpa.contains("/") ? dentroDaFaixa(origemDaConexao, limpa) : limpa.equals(origemDaConexao)) {
                return true;
            }
        }
        return false;
    }

    private boolean dentroDaFaixa(String endereco, String cidr) {
        try {
            String[] partes = cidr.split("/");
            byte[] base = InetAddress.getByName(partes[0]).getAddress();
            byte[] alvo = InetAddress.getByName(endereco).getAddress();
            int prefixo = Integer.parseInt(partes[1]);
            if (base.length != alvo.length || prefixo < 0 || prefixo > base.length * 8) {
                return false;
            }
            int bytesInteiros = prefixo / 8;
            for (int i = 0; i < bytesInteiros; i++) {
                if (base[i] != alvo[i]) {
                    return false;
                }
            }
            int bitsRestantes = prefixo % 8;
            if (bitsRestantes == 0) {
                return true;
            }
            int mascara = 0xFF << (8 - bitsRestantes);
            return (base[bytesInteiros] & mascara) == (alvo[bytesInteiros] & mascara);
        } catch (RuntimeException | java.net.UnknownHostException e) {
            // Regra malformada nao vira proxy confiavel por acidente.
            return false;
        }
    }
}
