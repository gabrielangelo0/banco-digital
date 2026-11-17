package com.estudo.banco.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Entrega as mensagens pendentes.
 *
 * Vive em bean separado do {@link EmailDispatcher} pelo mesmo motivo que
 * OutboxDrainer vive separado de OutboxPublisher: o @Transactional do Spring e
 * implementado por proxy, e uma chamada a um metodo da propria classe nao o
 * atravessa - a transacao nao abriria, em silencio, e as entidades voltariam
 * detached do repositorio.
 *
 * Esta transacao NAO e a transacao do dinheiro. Ela abre depois, no agendador,
 * justamente para que a chamada de rede ao provedor nunca aconteca enquanto
 * uma linha de conta esta travada.
 */
@Service
public class EmailSender {

    private static final Logger log = LoggerFactory.getLogger(EmailSender.class);

    private final EmailMessageRepository mensagens;
    private final ResendClient provedor;
    private final int tetoDeTentativas;
    private final Duration idadeMaxima;

    public EmailSender(EmailMessageRepository mensagens, ResendClient provedor,
                       @Value("${banco.email.max-attempts:6}") int tetoDeTentativas,
                       @Value("${banco.email.max-age-hours:24}") long idadeMaximaHoras) {
        this.mensagens = mensagens;
        this.provedor = provedor;
        this.tetoDeTentativas = tetoDeTentativas;
        this.idadeMaxima = Duration.ofHours(idadeMaximaHoras);
    }

    /** Devolve quantas mensagens foram tratadas neste ciclo. */
    @Transactional
    public int enviarPendentes() {
        OffsetDateTime agora = OffsetDateTime.now();
        List<EmailMessage> lote = mensagens.findTop5ByStatusAndNextAttemptAtBeforeOrderByIdAsc(
                EmailStatus.PENDING, agora);

        for (EmailMessage mensagem : lote) {
            tratar(mensagem, agora);
        }
        return lote.size();
    }

    private void tratar(EmailMessage mensagem, OffsetDateTime agora) {
        if (velhaDemais(mensagem, agora)) {
            // Ligar a chave pela primeira vez nao pode despejar na caixa de
            // alguem o comprovante de uma transferencia de dias atras: a essa
            // altura a mensagem informa menos do que assusta.
            mensagem.descartar("descartada por idade: mais de " + idadeMaxima.toHours() + "h na fila");
            log.warn("e-mail descartado por idade chave={}", mensagem.getIdempotencyKey());
            return;
        }

        try {
            String id = provedor.enviar(mensagem.paraEnviar());
            mensagem.marcarEnviado(id, agora);
            log.info("e-mail entregue chave={} provedor={}", mensagem.getIdempotencyKey(), id);
        } catch (FalhaDefinitivaDoProvedor e) {
            mensagem.marcarFalho(e.getMessage());
            log.warn("e-mail recusado em definitivo chave={} motivo={}",
                    mensagem.getIdempotencyKey(), e.getMessage());
        } catch (FalhaTemporariaDoProvedor e) {
            if (mensagem.getAttempts() + 1 >= tetoDeTentativas) {
                mensagem.marcarFalho("desistiu apos " + tetoDeTentativas + " tentativas: "
                        + e.getMessage());
                log.warn("e-mail desistido apos o teto chave={}", mensagem.getIdempotencyKey());
                return;
            }
            mensagem.adiar(agora.plus(esperaApos(mensagem.getAttempts())), e.getMessage());
        }
    }

    private boolean velhaDemais(EmailMessage mensagem, OffsetDateTime agora) {
        return mensagem.getCreatedAt() != null
                && mensagem.getCreatedAt().plus(idadeMaxima).isBefore(agora);
    }

    /**
     * Dobra a cada tentativa: 1, 2, 4, 8... minutos.
     *
     * Espera fixa e curta bate na mesma porta fechada muitas vezes; dobrar da
     * ao provedor tempo de voltar sem que a mensagem fique parada por horas no
     * caso em que ele volta rapido.
     */
    private Duration esperaApos(int tentativasJaFeitas) {
        return Duration.ofMinutes(1L << Math.min(tentativasJaFeitas, 8));
    }
}
