package com.estudo.banco.auth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Contador de tentativas falhas, com janela deslizante no Redis.
 *
 * A chave e a IDENTIDADE, nao o palpite: cinco senhas erradas bloqueiam a
 * conta por um tempo, e acertar a senha depois disso ainda espera. Forca bruta
 * precisa custar caro; errar a senha tres vezes, nao.
 */
@Service
public class AttemptLimiter {

    static final int LIMITE = 5;
    static final Duration JANELA = Duration.ofMinutes(15);

    private final StringRedisTemplate redis;

    public AttemptLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void registrarFalha(String chave) {
        Long tentativas = redis.opsForValue().increment(chave(chave));
        if (tentativas != null && tentativas == 1L) {
            // A janela comeca na primeira falha, nao na ultima: senao uma
            // tentativa a cada 14 minutos renovaria o prazo para sempre.
            redis.expire(chave(chave), JANELA);
        }
    }

    public boolean bloqueado(String chave) {
        String tentativas = redis.opsForValue().get(chave(chave));
        return tentativas != null && Long.parseLong(tentativas) >= LIMITE;
    }

    public void limpar(String chave) {
        redis.delete(chave(chave));
    }

    private String chave(String sufixo) {
        return "tentativas:" + sufixo;
    }
}
