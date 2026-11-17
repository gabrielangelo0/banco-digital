package com.estudo.banco.email;

import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Escolhe o lote e manda entregar, uma mensagem de cada vez.
 *
 * Note o que este metodo NAO faz: ele nao abre transacao em volta das chamadas
 * de rede. A leitura do lote e uma transacao curta e so de leitura; cada
 * entrega abre a sua, dentro de {@link EntregaDeUmaMensagem}. Era aqui que
 * morava o pior defeito da primeira versao - uma transacao so em volta de todo
 * o lote fazia uma mensagem que estourava desfazer o SENT de outra que JA
 * tinha sido entregue ao provedor.
 */
@Service
public class EmailSender {

    private final EmailMessageRepository mensagens;
    private final EntregaDeUmaMensagem entrega;

    public EmailSender(EmailMessageRepository mensagens, EntregaDeUmaMensagem entrega) {
        this.mensagens = mensagens;
        this.entrega = entrega;
    }

    /** Devolve quantas mensagens foram tratadas neste ciclo. */
    public int enviarPendentes() {
        List<Long> lote = pendentes();
        for (Long id : lote) {
            entrega.entregar(id);
        }
        return lote.size();
    }

    /**
     * Sem @Transactional: seria um metodo da propria classe chamando a si
     * mesmo, e o proxy do Spring nao atravessa isso - a anotacao nao faria
     * nada, em silencio. A consulta do repositorio ja abre a sua.
     */
    private List<Long> pendentes() {
        return mensagens
                .findTop5ByStatusAndNextAttemptAtBeforeOrderByIdAsc(
                        EmailStatus.PENDING, OffsetDateTime.now())
                .stream()
                .map(EmailMessage::getId)
                .toList();
    }
}
