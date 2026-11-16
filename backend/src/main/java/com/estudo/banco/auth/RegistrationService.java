package com.estudo.banco.auth;

import com.estudo.banco.account.Account;
import com.estudo.banco.account.AccountRepository;
import com.estudo.banco.error.CadastroInvalidoException;
import com.estudo.banco.user.User;
import com.estudo.banco.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Cadastro: cria a identidade e a conta na MESMA transacao.
 *
 * Separar as duas deixaria a porta aberta para um usuario sem conta, que
 * consegue entrar e nao tem para onde olhar.
 */
@Service
public class RegistrationService {

    /** Quem se cadastra precisa ter o que mover sem depender de ninguem. */
    private static final BigDecimal SALDO_INICIAL = new BigDecimal("1000.0000");

    private final UserRepository usuarios;
    private final AccountRepository contas;
    private final PasswordEncoder encoder;

    public RegistrationService(UserRepository usuarios, AccountRepository contas,
                               PasswordEncoder encoder) {
        this.usuarios = usuarios;
        this.contas = contas;
        this.encoder = encoder;
    }

    @Transactional
    public Long register(String email, String password, String transactionPassword) {
        String normalizado = normalizar(email);

        // Senha de login e senha transacional iguais anulam a separacao entre
        // autenticar e autorizar: quem pegou uma, pegou as duas.
        if (password.equals(transactionPassword)) {
            throw new CadastroInvalidoException();
        }
        if (usuarios.findByEmail(normalizado).isPresent()) {
            throw new CadastroInvalidoException();
        }

        try {
            User usuario = usuarios.saveAndFlush(new User(
                    normalizado,
                    encoder.encode(password),
                    encoder.encode(transactionPassword)));

            Account conta = contas.save(new Account(titular(normalizado), SALDO_INICIAL, usuario.getId()));
            return conta.getId();
        } catch (DataIntegrityViolationException e) {
            // Duas requisicoes simultaneas com o mesmo e-mail passam as duas
            // pela checagem acima; quem perde a corrida cai aqui, no indice
            // unico, e recebe a mesma recusa generica.
            throw new CadastroInvalidoException();
        }
    }

    private String normalizar(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private String titular(String email) {
        String local = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
        return local.isBlank() ? "Titular" : Character.toUpperCase(local.charAt(0)) + local.substring(1);
    }
}
