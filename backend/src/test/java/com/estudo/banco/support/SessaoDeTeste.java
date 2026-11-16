package com.estudo.banco.support;

import com.estudo.banco.auth.RegistrationService;
import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Cria um usuario e devolve o cookie de sessao dele.
 *
 * Existe porque, desde que a identidade vem do servidor, nenhum teste de rota
 * de /api funciona sem sessao - e repetir cadastro mais login em cada classe
 * seria copia em cinco lugares.
 */
public final class SessaoDeTeste {

    public record Sessao(Long contaId, Cookie cookie) {
    }

    private SessaoDeTeste() {
    }

    public static Sessao criar(MockMvc mvc, RegistrationService registration, String prefixo)
            throws Exception {
        String email = prefixo + "-" + UUID.randomUUID() + "@exemplo.com";
        Long contaId = registration.register(email, "senha-forte-1", "123456");

        var resposta = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"senha-forte-1\"}"))
                .andReturn().getResponse();

        return new Sessao(contaId, resposta.getCookie("sessao"));
    }
}
