package com.estudo.banco.outbox;

import com.estudo.banco.notification.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Drena o outbox dentro de uma transacao.
 *
 * Vive em bean separado de {@link OutboxPublisher} pelo mesmo motivo que
 * TransferExecutor vive separado de TransferService: o @Transactional do Spring
 * e implementado por proxy, e uma chamada a um metodo da propria classe nao o
 * atravessa - a transacao nao abriria, em silencio, e as entidades voltariam
 * detached do repositorio. Foi exatamente esse bug que o agendador tinha:
 * marcarPublicado() escrevia num objeto detached e nenhum UPDATE era emitido,
 * entao o outbox nunca drenava e a mesma notificacao era reenviada a cada
 * ciclo. Coberto por OutboxScheduledTest, que exercita o caminho agendado.
 */
@Service
public class OutboxDrainer {

    private static final Logger log = LoggerFactory.getLogger(OutboxDrainer.class);

    private final OutboxEventRepository eventos;
    private final NotificationService notificacoes;
    private final ObjectMapper mapper;

    public OutboxDrainer(OutboxEventRepository eventos, NotificationService notificacoes,
                         ObjectMapper mapper) {
        this.eventos = eventos;
        this.notificacoes = notificacoes;
        this.mapper = mapper;
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
