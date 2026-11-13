package com.estudo.banco.transfer;

import com.estudo.banco.account.Account;
import com.estudo.banco.account.AccountRepository;
import com.estudo.banco.error.ContaNaoEncontradaException;
import com.estudo.banco.error.TransferenciaInvalidaException;
import com.estudo.banco.idempotency.IdempotencyService;
import com.estudo.banco.ledger.Entry;
import com.estudo.banco.ledger.EntryRepository;
import com.estudo.banco.outbox.OutboxService;
import com.estudo.banco.outbox.TransferCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * A transacao da transferencia. Tudo aqui dentro acontece junto, ou nao
 * acontece.
 *
 * As quatro camadas de defesa contra inconsistencia de saldo e onde cada uma
 * aparece (ver docs/GUIA-FLUXO-BANCARIO.md):
 *
 *   1. Atomicidade        - o @Transactional de executar()
 *   2. Concorrencia       - findByIdForUpdate em ordem crescente de id
 *   3. Idempotencia       - a chave gravada nesta mesma transacao
 *   4. Consistencia event.- o outbox gravado nesta mesma transacao
 */
@Service
public class TransferExecutor {

    private static final Logger log = LoggerFactory.getLogger(TransferExecutor.class);
    private static final int ESCALA_MAXIMA = 2;

    private final AccountRepository accounts;
    private final EntryRepository entries;
    private final TransferRepository transfers;
    private final IdempotencyService idempotency;
    private final OutboxService outbox;

    public TransferExecutor(AccountRepository accounts, EntryRepository entries,
                            TransferRepository transfers, IdempotencyService idempotency,
                            OutboxService outbox) {
        this.accounts = accounts;
        this.entries = entries;
        this.transfers = transfers;
        this.idempotency = idempotency;
        this.outbox = outbox;
    }

    @Transactional
    public TransferResult executar(TransferCommand cmd) {
        validar(cmd);

        // Caminho rapido: a chave ja foi consumida, nada a fazer.
        Optional<TransferResult> jaFeita = idempotency.findExisting(cmd.idempotencyKey());
        if (jaFeita.isPresent()) {
            log.info("transferencia idempotente reaproveitada chave={} transferId={}",
                    cmd.idempotencyKey(), jaFeita.get().transferId());
            return jaFeita.get();
        }

        // ------------------------------------------------------------------
        // ORDEM DE AQUISICAO DE LOCKS.
        //
        // Deadlock exige um ciclo de espera: A segura a conta 1 e quer a 2
        // enquanto B segura a 2 e quer a 1. Travando SEMPRE na ordem crescente
        // de id, o ciclo deixa de ser possivel - nao e mitigacao, e
        // impossibilidade estrutural.
        //
        // NAO troque esta ordem por "origem primeiro, destino depois". Existe
        // um teste que prova o deadlock quando isso e feito:
        // TransferConcurrencyTest#ordemOpostaCausaDeadlockEOrdemCrescenteNao
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

        TransferResult resultado = new TransferResult(
                transferencia.getId(), TransferStatus.COMPLETED,
                origem.getBalance(), destino.getBalance());

        // Na MESMA transacao: se a chave colidir, o debito e o credito somem
        // junto com ela.
        idempotency.registrar(cmd.idempotencyKey(), resultado);

        // Tambem na mesma transacao. Nao existe "avisei sobre uma transferencia
        // que nao aconteceu", nem "aconteceu e ninguem soube".
        outbox.registrar(TransferCompletedEvent.TYPE, new TransferCompletedEvent(
                transferencia.getId(), origem.getId(), destino.getId(), valor));

        log.info("transferencia concluida transferId={} origem={} destino={} valor={}",
                transferencia.getId(), origem.getId(), destino.getId(), valor);

        return resultado;
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
        if (cmd.idempotencyKey() == null || cmd.idempotencyKey().isBlank()) {
            throw new TransferenciaInvalidaException("Idempotency-Key e obrigatoria");
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
