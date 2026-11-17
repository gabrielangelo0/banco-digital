package com.estudo.banco.outbox;

import com.estudo.banco.email.DadosDoComprovante;
import com.estudo.banco.email.Destinatario;
import com.estudo.banco.email.Destinatarios;
import com.estudo.banco.email.EmailQueue;
import com.estudo.banco.email.EmailTemplates;
import com.estudo.banco.ledger.Direction;
import com.estudo.banco.notification.NotificationService;
import com.estudo.banco.transfer.TransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
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
    private final Destinatarios destinatarios;
    private final EmailTemplates textos;
    private final EmailQueue fila;
    private final TransferRepository transferencias;

    public OutboxDrainer(OutboxEventRepository eventos, NotificationService notificacoes,
                         ObjectMapper mapper, Destinatarios destinatarios,
                         EmailTemplates textos, EmailQueue fila,
                         TransferRepository transferencias) {
        this.eventos = eventos;
        this.notificacoes = notificacoes;
        this.mapper = mapper;
        this.destinatarios = destinatarios;
        this.textos = textos;
        this.fila = fila;
        this.transferencias = transferencias;
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

        enfileirarComprovantes(dados);
    }

    /**
     * O e-mail e o segundo consumidor do mesmo evento - e o primeiro que vive
     * FORA do processo. Aqui so se ENFILEIRA; a chamada ao provedor acontece
     * noutro agendador, fora desta transacao, porque uma chamada de rede aqui
     * seguraria a conexao do pool e faria a falha de um terceiro reverter o
     * lote inteiro de eventos.
     *
     * A ponta que nao resolve e pulada em silencio: conta sem dono e conta de
     * demonstracao sao casos normais, nao erro.
     */
    private void enfileirarComprovantes(TransferCompletedEvent dados) {
        // A hora sai da propria transferencia, e nao de um campo do evento:
        // acrescentar campo mudaria o formato do payload, e os eventos ja
        // gravados em producao voltariam com nulo ali.
        OffsetDateTime quando = transferencias.findById(dados.transferId())
                .map(t -> t.getCreatedAt())
                .orElse(null);
        if (quando == null) {
            log.warn("comprovante sem hora de origem, nao enfileirado transferId={}",
                    dados.transferId());
            return;
        }

        comprovante(dados, Direction.DEBIT, dados.sourceAccountId(), dados.targetAccountId(), quando);
        comprovante(dados, Direction.CREDIT, dados.targetAccountId(), dados.sourceAccountId(), quando);
    }

    private void comprovante(TransferCompletedEvent dados, Direction direcao, Long dono,
                             Long outraPonta, OffsetDateTime quando) {
        Destinatario destinatario = destinatarios.para(dono).orElse(null);
        if (destinatario == null) {
            return;
        }
        fila.enfileirar(textos.comprovante(destinatario.email(), new DadosDoComprovante(
                dados.transferId(), direcao, dados.amount(), destinatario.nome(),
                dono, outraPonta, quando)));
    }

    public record NotificacaoTransferencia(
            java.util.UUID transferId,
            String direction,
            java.math.BigDecimal amount,
            Long counterpartAccountId) {
    }
}
