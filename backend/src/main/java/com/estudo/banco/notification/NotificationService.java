package com.estudo.banco.notification;

import com.estudo.banco.auth.SessionStore;
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
 *
 * Cada inscricao guarda o TOKEN que a abriu. Logout avisa este servico pelo
 * controller, mas a expiracao natural nao avisa ninguem - a chave do Redis
 * simplesmente deixa de existir. Sem o token aqui, um stream aberto continuaria
 * entregando movimentacao por ate trinta minutos a um navegador sem sessao.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final long TIMEOUT_MS = Duration.ofMinutes(30).toMillis();

    private record Inscricao(SseEmitter emitter, String token) {
    }

    private final Map<Long, Set<Inscricao>> inscritos = new ConcurrentHashMap<>();
    private final SessionStore sessoes;

    public NotificationService(SessionStore sessoes) {
        this.sessoes = sessoes;
    }

    public SseEmitter subscribe(Long accountId, String token) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        Inscricao inscricao = new Inscricao(emitter, token);
        inscritos.computeIfAbsent(accountId, k -> new CopyOnWriteArraySet<>()).add(inscricao);

        emitter.onCompletion(() -> remover(accountId, inscricao));
        emitter.onTimeout(() -> remover(accountId, inscricao));
        emitter.onError(e -> remover(accountId, inscricao));

        try {
            // Primeiro evento imediato: sem ele alguns proxies seguram a
            // resposta no buffer e o cliente fica sem saber que conectou.
            emitter.send(SseEmitter.event().name("ready").data(Map.of("accountId", accountId)));
        } catch (IOException e) {
            remover(accountId, inscricao);
        }
        return emitter;
    }

    public void notificar(Long accountId, String nomeEvento, Object payload) {
        for (Inscricao inscricao : inscritos.getOrDefault(accountId, Set.of())) {
            // A sessao pode ter expirado sem passar pelo logout. Entregar
            // movimentacao a ela seria entregar a um navegador que, se
            // recarregasse a pagina, levaria 401.
            if (sessoes.resolve(inscricao.token()).isEmpty()) {
                log.debug("inscricao com sessao expirada encerrada conta={}", accountId);
                encerrar(accountId, inscricao);
                continue;
            }
            try {
                inscricao.emitter().send(SseEmitter.event().name(nomeEvento).data(payload));
            } catch (IOException | IllegalStateException e) {
                log.debug("inscrito desconectado conta={}", accountId);
                remover(accountId, inscricao);
            }
        }
    }

    private void encerrar(Long accountId, Inscricao inscricao) {
        remover(accountId, inscricao);
        try {
            inscricao.emitter().complete();
        } catch (RuntimeException ignorado) {
            // Emitter ja morto nao e problema: o objetivo e que ele pare.
        }
    }

    private void remover(Long accountId, Inscricao inscricao) {
        Set<Inscricao> doCliente = inscritos.get(accountId);
        if (doCliente != null) {
            doCliente.remove(inscricao);
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
        Set<Inscricao> emitters = inscritos.remove(accountId);
        if (emitters == null) {
            return;
        }
        for (Inscricao inscricao : emitters) {
            try {
                inscricao.emitter().complete();
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
        Set<Inscricao> emitters = inscritos.get(accountId);
        return emitters == null ? 0 : emitters.size();
    }
}
