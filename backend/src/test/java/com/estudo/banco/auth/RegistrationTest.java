package com.estudo.banco.auth;

import com.estudo.banco.account.Account;
import com.estudo.banco.account.AccountRepository;
import com.estudo.banco.error.CadastroInvalidoException;
import com.estudo.banco.support.PostgresTestBase;
import com.estudo.banco.user.User;
import com.estudo.banco.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistrationTest extends PostgresTestBase {

    @Autowired
    private RegistrationService registration;

    @Autowired
    private AccountRepository contas;

    @Autowired
    private UserRepository usuarios;

    private String email(String prefixo) {
        return prefixo + "-" + UUID.randomUUID() + "@exemplo.com";
    }

    @Test
    void cadastroCriaUsuarioEContaComSaldoInicial() {
        Long contaId = registration.register(email("ana"), "senha-forte-1", "123456");

        Account conta = contas.findById(contaId).orElseThrow();
        assertThat(conta.getBalance()).isEqualByComparingTo("1000.0000");
        assertThat(conta.getOwnerId()).isNotNull();
    }

    @Test
    void senhaNuncaEGuardadaEmClaro() {
        String endereco = email("bia");
        registration.register(endereco, "senha-forte-1", "123456");

        User u = usuarios.findByEmail(endereco).orElseThrow();
        assertThat(u.getPasswordHash()).doesNotContain("senha-forte-1").startsWith("$argon2id$");
        assertThat(u.getTransactionPasswordHash()).doesNotContain("123456").startsWith("$argon2id$");
    }

    @Test
    void emailRepetidoIgnorandoCaixaEhRecusado() {
        // Review Focus 3
        String endereco = email("caio");
        registration.register(endereco, "senha-forte-1", "123456");

        assertThatThrownBy(() -> registration.register(
                endereco.toUpperCase(), "outra-senha-1", "654321"))
                .isInstanceOf(CadastroInvalidoException.class);
    }

    @Test
    void senhaTransacionalIgualAhDeLoginEhRecusada() {
        assertThatThrownBy(() -> registration.register(
                email("davi"), "senha-repetida-1", "senha-repetida-1"))
                .isInstanceOf(CadastroInvalidoException.class);
    }

    @Test
    void oEmailEhGravadoEmMinusculas() {
        String endereco = email("elis");
        registration.register(endereco.toUpperCase(), "senha-forte-1", "123456");

        assertThat(usuarios.findByEmail(endereco.toLowerCase())).isPresent();
    }
}
