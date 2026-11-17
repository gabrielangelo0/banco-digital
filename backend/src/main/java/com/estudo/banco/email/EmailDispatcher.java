package com.estudo.banco.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * O agendador da fila de e-mail.
 *
 * `banco.email.enabled` liga apenas ESTE lado. A fila continua sendo escrita
 * mesmo desligada, espelhando o `banco.outbox.auto-publish`: se a chave
 * gateasse tambem a escrita, um incidente no provedor custaria para sempre os
 * comprovantes das transferencias feitas durante a pausa.
 *
 * A transacao mora em {@link EmailSender}, noutro bean de proposito: ver o
 * javadoc de la.
 */
@Component
public class EmailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EmailDispatcher.class);

    private final EmailSender sender;
    private final boolean ligado;

    public EmailDispatcher(EmailSender sender,
                           @Value("${banco.email.enabled:false}") boolean ligado) {
        this.sender = sender;
        this.ligado = ligado;
    }

    @Scheduled(fixedDelayString = "${banco.email.poll-ms:5000}")
    void entregarAgendado() {
        if (!ligado) {
            return;
        }
        try {
            sender.enviarPendentes();
        } catch (RuntimeException e) {
            // Falhar aqui nao pode derrubar o agendador: as mensagens
            // continuam pendentes e serao tentadas no proximo ciclo. Uma
            // mensagem ruim nao para a fila.
            log.warn("falha ao entregar e-mails, sera tentado novamente", e);
        }
    }
}
