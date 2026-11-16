package com.estudo.banco.auth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;

/**
 * Sessao como token OPACO no Redis.
 *
 * Opaco, e nao JWT, porque revogar precisa ser possivel: um JWT valido continua
 * valido ate expirar, e "sair de todos os dispositivos" so funciona
 * reintroduzindo estado no servidor - que e exatamente o que o JWT prometia
 * evitar. Aqui sair e apagar uma chave.
 *
 * Duas estruturas: `sessao:{token}` guarda o dono, e `sessoes:{userId}` guarda
 * o conjunto de tokens daquele usuario. A segunda existe so para o revokeAll;
 * sem ela, derrubar todas as sessoes exigiria varrer o Redis inteiro.
 */
@Service
public class SessionStore {

    static final Duration TTL = Duration.ofDays(7);

    /**
     * Visitante nunca faz logout: cada clique no demo deixaria uma sessao de
     * sete dias que so `revokeAll` apaga. Duas horas bastam para olhar o
     * projeto e mantem o estado no Redis limitado.
     */
    public static final Duration TTL_VISITANTE = Duration.ofHours(2);

    private final StringRedisTemplate redis;
    private final SecureRandom aleatorio = new SecureRandom();

    public SessionStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public String create(Long userId) {
        return create(userId, TTL);
    }

    public String create(Long userId, Duration ttl) {
        byte[] bytes = new byte[32];
        aleatorio.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        redis.opsForValue().set(chave(token), String.valueOf(userId), ttl);
        redis.opsForSet().add(indice(userId), token);
        // O indice expira junto: senao o conjunto do dono da conta de
        // demonstracao acumularia tokens mortos por sete dias.
        redis.expire(indice(userId), ttl);
        return token;
    }

    public Optional<Long> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String dono = redis.opsForValue().get(chave(token));
        return dono == null ? Optional.empty() : Optional.of(Long.valueOf(dono));
    }

    public void revoke(String token) {
        resolve(token).ifPresent(userId -> redis.opsForSet().remove(indice(userId), token));
        redis.delete(chave(token));
    }

    public void revokeAll(Long userId) {
        Set<String> tokens = redis.opsForSet().members(indice(userId));
        if (tokens != null && !tokens.isEmpty()) {
            redis.delete(tokens.stream().map(this::chave).toList());
        }
        redis.delete(indice(userId));
    }

    private String chave(String token) {
        return "sessao:" + token;
    }

    private String indice(Long userId) {
        return "sessoes:" + userId;
    }
}
