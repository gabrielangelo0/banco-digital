package com.estudo.banco.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * Entrega UMA mensagem, na propria transacao.
 *
 * Uma transacao por mensagem, e nao uma por lote, por dois motivos que custam
 * caro quando faltam:
 *
 * - Uma mensagem que estoura nao pode desfazer o trabalho das outras. Com o
 *   lote inteiro numa transacao so, a mensagem ja ENTREGUE ao provedor tinha o
 *   seu SENT revertido e voltava a pendente - e era reenviada. A unica coisa
 *   entre isso e um comprovante repetido na caixa de alguem seria a chave de
 *   idempotencia do Resend, que dura 24h.
 * - A transacao nao atravessa a chamada de rede do lote inteiro. Com cinco
 *   chamadas dentro de uma transacao so, uma conexao do pool ficava presa por
 *   ate 75 segundos no pior caso.
 *
 * Bean separado porque @Transactional do Spring e por proxy: chamada a metodo
 * da propria classe nao o atravessa.
 */
@Service
public class EntregaDeUmaMensagem {

    private static final Logger log = LoggerFactory.getLogger(EntregaDeUmaMensagem.class);

    private final EmailMessageRepository mensagens;
    private final ResendClient provedor;
    private final int tetoDeTentativas;
    private final Duration idadeMaxima;

    public EntregaDeUmaMensagem(EmailMessageRepository mensagens, ResendClient provedor,
                                @Value("${banco.email.max-attempts:6}") int tetoDeTentativas,
                                @Value("${banco.email.max-age-hours:24}") long idadeMaximaHoras) {
        this.mensagens = mensagens;
        this.provedor = provedor;
        this.tetoDeTentativas = tetoDeTentativas;
        this.idadeMaxima = Duration.ofHours(idadeMaximaHoras);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void entregar(Long id) {
        EmailMessage mensagem = mensagens.findById(id).orElse(null);
        if (mensagem == null || mensagem.getStatus() != EmailStatus.PENDING) {
            return;
        }
        OffsetDateTime agora = OffsetDateTime.now();

        if (velhaDemais(mensagem, agora)) {
            // Ligar a chave pela primeira vez nao pode despejar na caixa de
            // alguem o comprovante de uma transferencia de dias atras: a essa
            // altura a mensagem informa menos do que assusta.
            mensagem.descartar("descartada por idade: mais de " + idadeMaxima.toHours() + "h na fila");
            log.warn("e-mail descartado por idade chave={}", mensagem.getIdempotencyKey());
            return;
        }

        try {
            String idNoProvedor = provedor.enviar(mensagem.paraEnviar());
            mensagem.marcarEnviado(idNoProvedor, agora);
            log.info("e-mail entregue chave={} provedor={}",
                    mensagem.getIdempotencyKey(), idNoProvedor);
        } catch (FalhaDefinitivaDoProvedor e) {
            mensagem.marcarFalho(e.getMessage());
            log.warn("e-mail recusado em definitivo chave={} motivo={}",
                    mensagem.getIdempotencyKey(), e.getMessage());
        } catch (RuntimeException e) {
            // Cinto: `FalhaTemporariaDoProvedor` cai aqui, e qualquer outra
            // coisa tambem. Um erro que ninguem previu e, por definicao, "nao
            // sei; tente mais tarde" - nunca "pare a fila".
            adiarOuDesistir(mensagem, agora, e);
        }
    }

    private void adiarOuDesistir(EmailMessage mensagem, OffsetDateTime agora, RuntimeException e) {
        String motivo = e.getClass().getSimpleName() + ": " + e.getMessage();
        if (mensagem.getAttempts() + 1 >= tetoDeTentativas) {
            mensagem.marcarFalho("desistiu apos " + tetoDeTentativas + " tentativas: " + motivo);
            log.warn("e-mail desistido apos o teto chave={}", mensagem.getIdempotencyKey());
            return;
        }
        mensagem.adiar(agora.plus(esperaApos(mensagem.getAttempts())), motivo);
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
