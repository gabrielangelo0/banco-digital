package com.estudo.banco.transfer;

import java.math.BigDecimal;

/**
 * Intencao de transferencia.
 *
 * `idempotencyKey` identifica a INTENCAO do usuario, nao a requisicao HTTP:
 * repetir a mesma intencao com a mesma chave nao move dinheiro de novo.
 */
public record TransferCommand(
        Long sourceAccountId,
        Long targetAccountId,
        BigDecimal amount,
        String idempotencyKey) {
}
