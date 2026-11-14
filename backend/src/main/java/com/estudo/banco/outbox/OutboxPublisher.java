package com.estudo.banco.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * O agendador do outbox.
 *
 * Entrega AT-LEAST-ONCE, nao exactly-once: o drainer pode entregar e morrer
 * antes do commit que marca como publicado, e ai entrega de novo quando voltar.
 * Por isso quem consome o evento tambem precisa ser idempotente.
 * "Exactly-once" de ponta a ponta nao existe - o que existe e at-least-once com
 * consumidor idempotente.
 *
 * A transacao mora em {@link OutboxDrainer}, em outro bean de proposito: ver o
 * javadoc de la.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxDrainer drainer;
    private final boolean autoPublish;

    public OutboxPublisher(OutboxDrainer drainer,
                           @Value("${banco.outbox.auto-publish:true}") boolean autoPublish) {
        this.drainer = drainer;
        this.autoPublish = autoPublish;
    }

    @Scheduled(fixedDelayString = "${banco.outbox.poll-ms:1000}")
    void publicarAgendado() {
        if (!autoPublish) {
            return;
        }
        try {
            publishPending();
        } catch (RuntimeException e) {
            // Falhar aqui nao pode derrubar o agendador: o evento continua
            // pendente e sera tentado de novo no proximo ciclo.
            log.warn("falha ao publicar outbox, sera tentado novamente", e);
        }
    }

    /** Drena ate 100 eventos pendentes. Devolve quantos publicou. */
    public int publishPending() {
        return drainer.publishPending();
    }
}
