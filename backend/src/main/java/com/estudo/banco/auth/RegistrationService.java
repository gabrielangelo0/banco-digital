package com.estudo.banco.auth;

import com.estudo.banco.account.Account;
import com.estudo.banco.account.AccountRepository;
import com.estudo.banco.email.EmailQueue;
import com.estudo.banco.email.EmailTemplates;
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
    private final EmailTemplates textos;
    private final EmailQueue fila;

    public RegistrationService(UserRepository usuarios, AccountRepository contas,
                               PasswordEncoder encoder, EmailTemplates textos,
                               EmailQueue fila) {
        this.usuarios = usuarios;
        this.contas = contas;
        this.encoder = encoder;
        this.textos = textos;
        this.fila = fila;
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

            String titular = titular(normalizado);
            Account conta = contas.save(new Account(titular, SALDO_INICIAL, usuario.getId()));

            // Na MESMA transacao da conta, de proposito: nao existe
            // "boas-vindas a um cadastro que nao houve". Aqui so se enfileira;
            // quem entrega e o agendador, fora desta transacao.
            //
            // A chave leva o id do USUARIO e o corpo mostra o numero da CONTA:
            // sao dois numeros diferentes, e trocar um pelo outro faria dois
            // cadastros distintos compartilharem a mesma chave.
            fila.enfileirar(textos.boasVindas(
                    normalizado, titular, usuario.getId(), conta.getId(), SALDO_INICIAL));

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
