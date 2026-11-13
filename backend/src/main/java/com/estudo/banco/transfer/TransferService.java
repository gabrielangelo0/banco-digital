package com.estudo.banco.transfer;

import com.estudo.banco.account.Account;
import com.estudo.banco.account.AccountRepository;
import com.estudo.banco.error.ContaNaoEncontradaException;
import com.estudo.banco.error.TransferenciaInvalidaException;
import com.estudo.banco.ledger.Entry;
import com.estudo.banco.ledger.EntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * O coracao da aplicacao.
 *
 * As quatro camadas de defesa contra inconsistencia de saldo, e onde cada uma
 * aparece (ver docs/GUIA-FLUXO-BANCARIO.md):
 *
 *   1. Atomicidade        - o @Transactional deste metodo
 *   2. Concorrencia       - findByIdForUpdate em ordem crescente de id
 *   3. Idempotencia       - IdempotencyService (Task 6)
 *   4. Consistencia event.- outbox gravado nesta mesma transacao (Task 7)
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);
    private static final int ESCALA_MAXIMA = 2;

    private final AccountRepository accounts;
    private final EntryRepository entries;
    private final TransferRepository transfers;

    public TransferService(AccountRepository accounts, EntryRepository entries,
                           TransferRepository transfers) {
        this.accounts = accounts;
        this.entries = entries;
        this.transfers = transfers;
    }

    @Transactional
    public TransferResult execute(TransferCommand cmd) {
        validar(cmd);

        // ------------------------------------------------------------------
        // ORDEM DE AQUISICAO DE LOCKS.
        //
        // Deadlock exige um ciclo de espera: A segura a conta 1 e quer a 2
        // enquanto B segura a 2 e quer a 1. Travando SEMPRE na ordem crescente
        // de id, o ciclo deixa de ser possivel - nao e mitigacao, e
        // impossibilidade estrutural.
        //
        // NAO troque esta ordem por "origem primeiro, destino depois".
        // ------------------------------------------------------------------
        long menorId = Math.min(cmd.sourceAccountId(), cmd.targetAccountId());
        long maiorId = Math.max(cmd.sourceAccountId(), cmd.targetAccountId());

        Account primeira = travar(menorId);
        Account segunda = travar(maiorId);

        Account origem = primeira.getId().equals(cmd.sourceAccountId()) ? primeira : segunda;
        Account destino = primeira.getId().equals(cmd.targetAccountId()) ? primeira : segunda;

        BigDecimal valor = cmd.amount();
        origem.debit(valor);
        destino.credit(valor);

        Transfer transferencia = transfers.save(new Transfer(
                UUID.randomUUID(), origem.getId(), destino.getId(), valor, TransferStatus.COMPLETED));

        // Dupla entrada: os dois lancamentos somam exatamente zero.
        entries.save(Entry.debit(origem.getId(), transferencia.getId(), valor));
        entries.save(Entry.credit(destino.getId(), transferencia.getId(), valor));

        log.info("transferencia concluida transferId={} origem={} destino={} valor={}",
                transferencia.getId(), origem.getId(), destino.getId(), valor);

        return new TransferResult(transferencia.getId(), TransferStatus.COMPLETED,
                origem.getBalance(), destino.getBalance());
    }

    private Account travar(long contaId) {
        return accounts.findByIdForUpdate(contaId)
                .orElseThrow(() -> new ContaNaoEncontradaException(contaId));
    }

    /**
     * Valida antes de encostar no banco: nao faz sentido travar linhas para
     * depois descobrir que o valor era negativo.
     */
    private void validar(TransferCommand cmd) {
        if (cmd.sourceAccountId() == null || cmd.targetAccountId() == null) {
            throw new TransferenciaInvalidaException("Conta de origem e destino sao obrigatorias");
        }
        if (cmd.sourceAccountId().equals(cmd.targetAccountId())) {
            throw new TransferenciaInvalidaException(
                    "Origem e destino nao podem ser a mesma conta");
        }
        if (cmd.amount() == null) {
            throw new TransferenciaInvalidaException("Valor e obrigatorio");
        }
        if (cmd.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new TransferenciaInvalidaException("Valor deve ser maior que zero");
        }
        if (cmd.amount().scale() > ESCALA_MAXIMA) {
            // Arredondar em silencio aqui significaria perder centavos sem que
            // ninguem percebesse. Melhor recusar.
            throw new TransferenciaInvalidaException(
                    "Valor nao pode ter mais de " + ESCALA_MAXIMA + " casas decimais");
        }
    }
}
