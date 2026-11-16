package com.estudo.banco.transfer;

import com.estudo.banco.account.Account;
import com.estudo.banco.account.AccountRepository;
import com.estudo.banco.auth.AttemptLimiter;
import com.estudo.banco.error.MuitasTentativasException;
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
    private final AttemptLimiter tentativas;

    public SenhaTransacionalGuard(AccountRepository contas, UserRepository usuarios,
                                  PasswordEncoder encoder, AttemptLimiter tentativas) {
        this.contas = contas;
        this.usuarios = usuarios;
        this.encoder = encoder;
        this.tentativas = tentativas;
    }

    public void exigir(Long contaId, String senhaTransacional) {
        // Contador proprio, independente do login: quem adivinha senha
        // transacional ja passou pela autenticacao, e e justamente ai que
        // forca bruta sobre a autorizacao acontece.
        String chave = "transacional:" + contaId;
        if (tentativas.bloqueado(chave)) {
            throw new MuitasTentativasException();
        }

        String hash = contas.findById(contaId)
                .map(Account::getOwnerId)
                .flatMap(usuarios::findById)
                .map(User::getTransactionPasswordHash)
                .orElse(null);

        if (hash == null || !encoder.matches(
                senhaTransacional == null ? "" : senhaTransacional, hash)) {
            tentativas.registrarFalha(chave);
            throw new SenhaTransacionalInvalidaException();
        }
        tentativas.limpar(chave);
    }
}
