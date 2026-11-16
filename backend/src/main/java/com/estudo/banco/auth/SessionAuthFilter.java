package com.estudo.banco.auth;

import com.estudo.banco.account.Account;
import com.estudo.banco.account.AccountRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;

/**
 * Resolve a sessao e recusa quem nao tem uma.
 *
 * Rotas de /api/auth ficam de fora pela razao obvia: e por elas que se obtem a
 * sessao. O actuator tambem, porque o healthcheck do container nao tem como
 * se autenticar.
 */
@Component
public class SessionAuthFilter extends OncePerRequestFilter {

    private final SessionStore sessoes;
    private final AccountRepository contas;
    private final UsuarioAtual usuarioAtual;

    public SessionAuthFilter(SessionStore sessoes, AccountRepository contas, UsuarioAtual usuarioAtual) {
        this.sessoes = sessoes;
        this.contas = contas;
        this.usuarioAtual = usuarioAtual;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String caminho = request.getRequestURI();
        return !caminho.startsWith("/api/") || caminho.startsWith("/api/auth/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Optional<Long> userId = cookie(request).flatMap(sessoes::resolve);
        if (userId.isEmpty()) {
            recusar(response);
            return;
        }

        Optional<Account> conta = contas.findByOwnerId(userId.get());
        if (conta.isEmpty()) {
            // Sessao valida para um usuario sem conta: nao deveria existir, e
            // seguir adiante daria NullPointerException la na frente.
            recusar(response);
            return;
        }

        usuarioAtual.definir(userId.get(), conta.get().getId());
        chain.doFilter(request, response);
    }

    private Optional<String> cookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(c -> SessionCookie.NOME.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }

    private void recusar(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"SEM_SESSAO\",\"message\":\"Entre para continuar.\"}");
    }
}
