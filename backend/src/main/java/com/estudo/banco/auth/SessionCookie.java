package com.estudo.banco.auth;

import org.springframework.http.ResponseCookie;

import java.time.Duration;

/**
 * O cookie de sessao.
 *
 * `Secure` vem de quem chama, com `request.isSecure()`, e nao fixo em true:
 * producao e HTTPS, mas `npm run dev` e HTTP, e um cookie sempre Secure
 * simplesmente nao e gravado em desenvolvimento - o login "funciona" e o
 * usuario volta deslogado, sem erro nenhum.
 *
 * `HttpOnly` sempre: nenhum JavaScript precisa ler este valor, e nao poder ler
 * e o que impede um XSS de virar sequestro de sessao.
 */
public final class SessionCookie {

    public static final String NOME = "sessao";

    private SessionCookie() {
    }

    public static ResponseCookie build(String token, boolean secure) {
        return ResponseCookie.from(NOME, token)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(SessionStore.TTL)
                .build();
    }

    public static ResponseCookie expirada(boolean secure) {
        return ResponseCookie.from(NOME, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
    }
}
