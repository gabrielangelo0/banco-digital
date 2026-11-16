package com.estudo.banco.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Argon2id, e nao SHA nem MD5.
 *
 * Senha nao se guarda com funcao rapida: a velocidade que serve ao servidor
 * serve igual a quem roubou o banco e esta testando bilhoes de palpites por
 * segundo. Argon2id e lento e caro em memoria de proposito, e o salt vai
 * embutido no proprio hash.
 */
@Configuration
public class HashingConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }
}
