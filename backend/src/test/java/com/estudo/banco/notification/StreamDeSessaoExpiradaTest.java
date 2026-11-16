package com.estudo.banco.notification;

import com.estudo.banco.auth.SessionStore;
import com.estudo.banco.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Logout encerra o stream. Expiracao tambem precisa.
 *
 * Revogar pelo logout passa pelo controller, que avisa o servico. A
 * expiracao natural nao passa por lugar nenhum: a chave do Redis
 * simplesmente deixa de existir, e um stream aberto continuava entregando
 * movimentacao por ate 30 minutos a um navegador sem sessao.
 */
class StreamDeSessaoExpiradaTest extends PostgresTestBase {

    @Autowired
    NotificationService notificacoes;

    @Autowired
    SessionStore sessoes;

    @Test
    void sessaoExpiradaDerrubaOStreamJaAberto() {
        Long conta = novaConta("Expira", "10.00");
        String token = sessoes.create(424242L);

        notificacoes.subscribe(conta, token);
        assertThat(notificacoes.quantidadeDeInscritos(conta)).isEqualTo(1);

        // Apagar a chave e o que a expiracao faz. Aqui ninguem avisa o
        // servico - e esse e o ponto.
        sessoes.revoke(token);

        notificacoes.notificar(conta, "transfer", Map.of("amount", "1.00"));

        assertThat(notificacoes.quantidadeDeInscritos(conta)).isZero();
    }

    @Test
    void sessaoVivaContinuaRecebendo() {
        Long conta = novaConta("Vive", "10.00");
        String token = sessoes.create(424243L);

        notificacoes.subscribe(conta, token);
        notificacoes.notificar(conta, "transfer", Map.of("amount", "1.00"));

        assertThat(notificacoes.quantidadeDeInscritos(conta)).isEqualTo(1);
    }
}
