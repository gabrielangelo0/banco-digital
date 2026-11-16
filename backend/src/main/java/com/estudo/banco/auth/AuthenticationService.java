package com.estudo.banco.auth;

import com.estudo.banco.error.CredenciaisInvalidasException;
import com.estudo.banco.user.User;
import com.estudo.banco.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Autenticacao de tempo constante.
 *
 * Quando o e-mail nao existe, a comparacao roda mesmo assim, contra um hash
 * descartavel gerado na subida. Sem isso, "usuario inexistente" responde em
 * microssegundos e "senha errada" responde em dezenas de milissegundos, porque
 * o Argon2 e lento de proposito - e essa diferenca sozinha transforma o login
 * num verificador de quem tem conta aqui.
 */
@Service
public class AuthenticationService {

    private final UserRepository usuarios;
    private final PasswordEncoder encoder;
    private final String hashDescartavel;

    public AuthenticationService(UserRepository usuarios, PasswordEncoder encoder) {
        this.usuarios = usuarios;
        this.encoder = encoder;
        this.hashDescartavel = encoder.encode("sem-usuario-" + UUID.randomUUID());
    }

    public Long autenticar(String email, String senha) {
        String normalizado = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        Optional<User> usuario = usuarios.findByEmail(normalizado);

        String hash = usuario.map(User::getPasswordHash).orElse(hashDescartavel);
        boolean confere = encoder.matches(senha == null ? "" : senha, hash);

        if (usuario.isEmpty() || !confere) {
            throw new CredenciaisInvalidasException();
        }
        return usuario.get().getId();
    }
}
