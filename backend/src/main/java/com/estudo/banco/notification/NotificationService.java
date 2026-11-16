package com.estudo.banco.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Notificacoes em tempo real por Server-Sent Events.
 *
 * SSE em vez de WebSocket porque o fluxo e de mao unica - o servidor avisa, o
 * app escuta - e o EventSource do navegador ja reconecta sozinho.
 *
 * Os inscritos vivem em memoria: reiniciar a aplicacao derruba as conexoes, e o
 * navegador reconecta. Em producao com varias instancias isso iria para um
 * Redis pub/sub ou equivalente, senao o evento chega so na instancia que
 * recebeu a transferencia.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final long TIMEOUT_MS = Duration.ofMinutes(30).toMillis();

    private final Map<Long, Set<SseEmitter>> inscritos = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long accountId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        inscritos.computeIfAbsent(accountId, k -> new CopyOnWriteArraySet<>()).add(emitter);

        emitter.onCompletion(() -> remover(accountId, emitter));
        emitter.onTimeout(() -> remover(accountId, emitter));
        emitter.onError(e -> remover(accountId, emitter));

        try {
            // Primeiro evento imediato: sem ele alguns proxies seguram a
            // resposta no buffer e o cliente fica sem saber que conectou.
            emitter.send(SseEmitter.event().name("ready").data(Map.of("accountId", accountId)));
        } catch (IOException e) {
            remover(accountId, emitter);
        }
        return emitter;
    }

    public void notificar(Long accountId, String nomeEvento, Object payload) {
        for (SseEmitter emitter : inscritos.getOrDefault(accountId, Set.of())) {
            try {
                emitter.send(SseEmitter.event().name(nomeEvento).data(payload));
            } catch (IOException | IllegalStateException e) {
                log.debug("inscrito desconectado conta={}", accountId);
                remover(accountId, emitter);
            }
        }
    }

    private void remover(Long accountId, SseEmitter emitter) {
        Set<SseEmitter> doCliente = inscritos.get(accountId);
        if (doCliente != null) {
            doCliente.remove(emitter);
            if (doCliente.isEmpty()) {
                inscritos.remove(accountId);
            }
        }
    }

    /**
     * Encerra todas as inscricoes de uma conta.
     *
     * Chamado quando a sessao e revogada: impedir conexoes novas nao basta, o
     * stream ja aberto continuaria entregando movimentacao para uma sessao que
     * nao existe mais.
     */
    public void encerrarInscricoes(Long accountId) {
        Set<SseEmitter> emitters = inscritos.remove(accountId);
        if (emitters == null) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.complete();
            } catch (RuntimeException ignorado) {
                // Emitter ja morto nao e problema: o objetivo e que ele pare.
            }
        }
    }

    /**
     * Quantos clientes estao ouvindo esta conta agora.
     *
     * Observabilidade: numero de conexoes vivas por conta e o que diz se o
     * stream esta sendo usado, e e tambem como se prova que revogar a sessao
     * desmontou a inscricao.
     */
    public int quantidadeDeInscritos(Long accountId) {
        Set<SseEmitter> emitters = inscritos.get(accountId);
        return emitters == null ? 0 : emitters.size();
    }
}
