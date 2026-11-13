package com.estudo.banco.transfer;

import com.estudo.banco.idempotency.IdempotencyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Porta de entrada da transferencia.
 *
 * Este metodo NAO e @Transactional, e isso e deliberado: o replay idempotente
 * precisa ler o banco DEPOIS que a transacao perdedora foi desfeita, e uma
 * transacao marcada como rollback-only nao consegue mais ler nada util.
 *
 * Tambem por isso a transacao vive em outro bean (TransferExecutor) em vez de
 * um metodo privado desta classe: o @Transactional do Spring e implementado
 * com proxy, e chamar um metodo da propria classe nao atravessa o proxy - a
 * transacao simplesmente nao abriria, em silencio. E a pegadinha classica.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final TransferExecutor executor;
    private final IdempotencyService idempotency;

    public TransferService(TransferExecutor executor, IdempotencyService idempotency) {
        this.executor = executor;
        this.idempotency = idempotency;
    }

    public TransferResult execute(TransferCommand cmd) {
        try {
            return executor.executar(cmd);
        } catch (DataIntegrityViolationException e) {
            // Duas requisicoes com a mesma chave colidiram na unique
            // constraint. A perdedora teve a transacao inteira desfeita -
            // nenhum centavo se moveu - e agora devolve a resposta da
            // vencedora. Se a violacao foi de outra constraint, nao ha
            // resposta gravada e o erro original sobe.
            return idempotency.findExisting(cmd.idempotencyKey())
                    .map(resultado -> {
                        log.info("corrida de idempotencia perdida chave={}, devolvendo resposta original",
                                cmd.idempotencyKey());
                        return resultado;
                    })
                    .orElseThrow(() -> e);
        }
    }
}
