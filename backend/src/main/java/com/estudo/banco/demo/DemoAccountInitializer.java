package com.estudo.banco.demo;

import com.estudo.banco.account.Account;
import com.estudo.banco.account.AccountRepository;
import com.estudo.banco.user.User;
import com.estudo.banco.user.UserRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Da dono as contas semeadas, na subida - nao na migracao.
 *
 * Migracao vai para o repositorio, e o repositorio e publico: uma senha de
 * demonstracao escrita num arquivo versionado e uma senha publicada. Aqui ela
 * vem do ambiente, e sem as variaveis a aplicacao nao sobe, em vez de cair
 * num valor previsivel.
 */
@Component
@ConditionalOnProperty(name = "banco.demo.enabled", havingValue = "true")
public class DemoAccountInitializer {

    private final DemoAccounts demo;
    private final AccountRepository contas;
    private final UserRepository usuarios;
    private final PasswordEncoder encoder;
    private final String senha;
    private final String senhaTransacional;

    public DemoAccountInitializer(DemoAccounts demo, AccountRepository contas,
                                  UserRepository usuarios, PasswordEncoder encoder,
                                  org.springframework.core.env.Environment env) {
        this.demo = demo;
        this.contas = contas;
        this.usuarios = usuarios;
        this.encoder = encoder;
        this.senha = exigir(env, "banco.demo.password");
        this.senhaTransacional = exigir(env, "banco.demo.transaction-password");
    }

    private String exigir(org.springframework.core.env.Environment env, String chave) {
        String valor = env.getProperty(chave);
        if (valor == null || valor.isBlank()) {
            throw new IllegalStateException(
                    "Demonstracao ligada sem " + chave + ". Defina a variavel de ambiente "
                            + "correspondente: credencial previsivel e pior do que nao subir.");
        }
        return valor;
    }

    @EventListener(ContextRefreshedEvent.class)
    @Transactional
    public void prepararContas() {
        for (Long contaId : demo.ids()) {
            contas.findById(contaId).ifPresent(this::garantirDono);
        }
    }

    private void garantirDono(Account conta) {
        if (conta.getOwnerId() != null) {
            return;
        }
        String email = "demo-" + conta.getId() + "@banco.local";
        User dono = usuarios.findByEmail(email).orElseGet(() -> usuarios.saveAndFlush(
                new User(email, encoder.encode(senha), encoder.encode(senhaTransacional))));

        conta.definirDono(dono.getId());
        contas.save(conta);
    }
}
