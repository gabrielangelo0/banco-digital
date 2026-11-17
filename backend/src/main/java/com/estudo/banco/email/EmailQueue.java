package com.estudo.banco.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Escreve mensagens na fila de e-mail.
 *
 * Chamado de DENTRO da transacao de negocio, como o {@code OutboxService}: e
 * isso que torna o fato e a mensagem indivisiveis. Nao existe "boas-vindas a
 * um cadastro que nao houve".
 *
 * A entrega fica com o {@code EmailSender}, fora desta transacao - aqui so se
 * escreve.
 */
@Service
public class EmailQueue {

    private static final Logger log = LoggerFactory.getLogger(EmailQueue.class);

    private final EmailMessageRepository mensagens;

    public EmailQueue(EmailMessageRepository mensagens) {
        this.mensagens = mensagens;
    }

    /**
     * Devolve {@code true} se gravou, {@code false} se a chave ja existia.
     *
     * A consulta pela chave e a trava normal contra duplicata; ver o javadoc
     * de {@code EmailMessageRepository#existsByIdempotencyKey} para o motivo
     * de nao ser o indice unico.
     */
    @Transactional
    public boolean enfileirar(EmailParaEnviar mensagem) {
        if (mensagem.destinatario() == null || mensagem.destinatario().isBlank()) {
            throw new IllegalArgumentException("mensagem sem destinatario");
        }
        if (mensagens.existsByIdempotencyKey(mensagem.chave())) {
            // Reentrega do mesmo evento. O outbox entrega pelo menos uma vez,
            // entao isto e esperado, nao e erro.
            return false;
        }
        mensagens.save(new EmailMessage(mensagem, OffsetDateTime.now()));
        // O endereco nao entra no log: a chave ja identifica a mensagem, e o
        // destinatario e dado pessoal de quem se cadastrou.
        log.info("e-mail enfileirado chave={}", mensagem.chave());
        return true;
    }
}
