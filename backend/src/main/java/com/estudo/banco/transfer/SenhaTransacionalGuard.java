package com.estudo.banco.transfer;

import com.estudo.banco.account.Account;
import com.estudo.banco.account.AccountRepository;
import com.estudo.banco.error.SenhaTransacionalInvalidaException;
import com.estudo.banco.user.User;
import com.estudo.banco.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Autorizacao da movimentacao, separada da autenticacao da sessao.
 *
 * Vive fora do TransferService de proposito: aquele metodo nao e
 * @Transactional e e tambem o caminho do replay idempotente. Misturar
 * autorizacao ali embaralharia uma fronteira que o projeto documenta com
 * cuidado. Aqui a senha e conferida ANTES de qualquer transacao abrir.
 */
@Service
public class SenhaTransacionalGuard {

    private final AccountRepository contas;
    private final UserRepository usuarios;
    private final PasswordEncoder encoder;

    public SenhaTransacionalGuard(AccountRepository contas, UserRepository usuarios,
                                  PasswordEncoder encoder) {
        this.contas = contas;
        this.usuarios = usuarios;
        this.encoder = encoder;
    }

    public void exigir(Long contaId, String senhaTransacional) {
        String hash = contas.findById(contaId)
                .map(Account::getOwnerId)
                .flatMap(usuarios::findById)
                .map(User::getTransactionPasswordHash)
                .orElse(null);

        if (hash == null || !encoder.matches(
                senhaTransacional == null ? "" : senhaTransacional, hash)) {
            throw new SenhaTransacionalInvalidaException();
        }
    }
}
