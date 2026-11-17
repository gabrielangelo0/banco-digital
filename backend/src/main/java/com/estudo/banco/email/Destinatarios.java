package com.estudo.banco.email;

import com.estudo.banco.account.AccountRepository;
import com.estudo.banco.demo.DemoAccounts;
import com.estudo.banco.user.UserRepository;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

/**
 * De uma conta ao endereco de quem a possui - quando houver, e quando valer.
 *
 * Devolver vazio aqui nao e erro: e a resposta certa para conta sem dono e
 * para conta de demonstracao. Quem chama pula a ponta que nao resolveu e segue
 * com a outra.
 */
@Service
public class Destinatarios {

    /**
     * As contas semeadas pertencem a `demo-N@banco.local`, que nao existe.
     *
     * Cada tentativa de entrega ali e uma rejeicao definitiva, e rejeicao
     * definitiva queima a reputacao do dominio que manda. Importa mais do que
     * parece: o reset da demonstracao passa pelo TransferService igual a
     * qualquer transferencia, entao sem esta trava o servidor mandaria dois
     * e-mails invalidos por hora, para sempre.
     */
    private static final String SUFIXO_SINTETICO = ".local";

    private final AccountRepository contas;
    private final UserRepository usuarios;
    private final DemoAccounts demo;

    public Destinatarios(AccountRepository contas, UserRepository usuarios, DemoAccounts demo) {
        this.contas = contas;
        this.usuarios = usuarios;
        this.demo = demo;
    }

    public Optional<Destinatario> para(Long contaId) {
        if (contaId == null || demo.ehDemonstracao(contaId)) {
            return Optional.empty();
        }
        return contas.findById(contaId)
                .filter(conta -> conta.getOwnerId() != null)
                .flatMap(conta -> usuarios.findById(conta.getOwnerId())
                        .map(dono -> new Destinatario(dono.getEmail(), conta.getHolderName())))
                .filter(d -> !d.email().toLowerCase(Locale.ROOT).endsWith(SUFIXO_SINTETICO));
    }
}
