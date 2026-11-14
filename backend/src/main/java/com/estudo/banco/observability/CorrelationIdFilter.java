package com.estudo.banco.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Amarra todos os logs de uma requisicao a um unico identificador.
 *
 * Sem isso, investigar um incidente em producao vira busca por timestamp entre
 * linhas de requisicoes concorrentes. Com isso, um grep devolve a requisicao
 * inteira.
 *
 * O id recebido no header e preservado: vindo de outro servico, ele precisa
 * atravessar a fronteira intacto para o rastro nao se partir.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String correlationId = request.getHeader(HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            // O MDC e ligado a thread, e o container reaproveita threads. Sem
            // esta limpeza, a proxima requisicao herdaria o id da anterior -
            // logs cruzados, rastro mentiroso.
            MDC.remove(MDC_KEY);
        }
    }
}
