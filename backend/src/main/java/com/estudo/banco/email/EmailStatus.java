package com.estudo.banco.email;

/**
 * Onde uma mensagem esta na vida.
 *
 * `FAILED` e desistencia, nao erro transitorio: mensagem que ainda vai ser
 * tentada de novo continua `PENDING`, com a hora da proxima tentativa marcada.
 */
public enum EmailStatus {
    PENDING,
    SENT,
    FAILED
}
