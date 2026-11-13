package com.estudo.banco.outbox;

import com.estudo.banco.notification.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Le o outbox e entrega os eventos.
 *
 * Isso da entrega AT-LEAST-ONCE, nao exactly-once: o publisher pode entregar e
 * morrer antes de marcar como publicado, e ai entrega de novo quando voltar.
 * Por isso quem consome o evento tambem precisa ser idempotente. "Exactly-once"
 * de ponta a ponta nao existe - o que existe e at-least-once com consumidor
 * idempotente.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository eventos;
    private final NotificationService notificacoes;
    private final ObjectMapper mapper;
    private final boolean autoPublish;

    public OutboxPublisher(OutboxEventRepository eventos, NotificationService notificacoes,
                           ObjectMapper mapper,
                           @Value("${banco.outbox.auto-publish:true}") boolean autoPublish) {
        this.eventos = eventos;
        this.notificacoes = notificacoes;
        this.mapper = mapper;
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

    @Transactional
    public int publishPending() {
        List<OutboxEvent> pendentes = eventos.findTop100ByPublishedFalseOrderByIdAsc();
        for (OutboxEvent evento : pendentes) {
            entregar(evento);
            evento.marcarPublicado();
        }
        if (!pendentes.isEmpty()) {
            log.info("outbox publicado quantidade={}", pendentes.size());
        }
        return pendentes.size();
    }

    private void entregar(OutboxEvent evento) {
        if (!TransferCompletedEvent.TYPE.equals(evento.getEventType())) {
            return;
        }
        TransferCompletedEvent dados =
                mapper.readValue(evento.getPayload(), TransferCompletedEvent.class);

        notificacoes.notificar(dados.sourceAccountId(), "transfer", new NotificacaoTransferencia(
                dados.transferId(), "DEBIT", dados.amount(), dados.targetAccountId()));
        notificacoes.notificar(dados.targetAccountId(), "transfer", new NotificacaoTransferencia(
                dados.transferId(), "CREDIT", dados.amount(), dados.sourceAccountId()));
    }

    public record NotificacaoTransferencia(
            java.util.UUID transferId,
            String direction,
            java.math.BigDecimal amount,
            Long counterpartAccountId) {
    }
}
