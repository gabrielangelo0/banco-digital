package com.estudo.banco.email;

import com.estudo.banco.outbox.OutboxPublisher;
import com.estudo.banco.support.PostgresTestBase;
import com.estudo.banco.transfer.TransferCommand;
import com.estudo.banco.transfer.TransferResult;
import com.estudo.banco.transfer.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O comprovante nasce do mesmo evento que a notificacao na tela.
 *
 * Ids fixos e altos, criados pelo proprio teste: o PostgresTestBase avisa que
 * nenhum teste pode depender das contas semeadas, porque o banco e
 * compartilhado pela suite e outro teste pode ter mexido no saldo delas.
 */
@TestPropertySource(properties = {
        "banco.demo.enabled=true",
        "banco.demo.account-ids=9001,9002",
        // O DemoAccountInitializer recusa subir sem as duas: credencial
        // previsivel e pior do que nao subir.
        "banco.demo.password=demo-senha-de-teste",
        "banco.demo.transaction-password=123456"})
class ComprovanteNaTransferenciaTest extends PostgresTestBase {

    @Autowired
    TransferService transfers;

    @Autowired
    OutboxPublisher publisher;

    /**
     * Drena ate esvaziar.
     *
     * `publishPending` leva no maximo cem eventos por vez, e o banco e
     * compartilhado por toda a suite com o publisher desligado - entao quando
     * esta classe roda ja existe uma fila de eventos pendentes de outros
     * testes, e uma chamada so pode nem alcancar o evento recem-criado.
     */
    private void drenarTudo() {
        for (int volta = 0; volta < 50 && publisher.publishPending() > 0; volta++) {
            // o proprio publishPending faz o trabalho; aqui so se repete
        }
    }

    /**
     * Endereco novo a cada chamada: `accounts_owner_unique` permite UMA conta
     * por usuario, e o banco e compartilhado por toda a suite - reusar um
     * endereco fixo faz a segunda conta daquele dono esbarrar no indice.
     */
    private List<String> chavesDe(UUID transferId) {
        return jdbc.queryForList(
                "SELECT idempotency_key FROM email_messages WHERE idempotency_key LIKE ?",
                String.class, "transfer/" + transferId + "/%");
    }

    /** Conta com id escolhido, para casar com `banco.demo.account-ids`. */
    private void contaDeDemonstracao(Long id, String titular, String saldo, String email) {
        jdbc.update("INSERT INTO accounts (id, holder_name, balance, owner_id)"
                        + " VALUES (?, ?, ?, ?) ON CONFLICT (id) DO NOTHING",
                id, titular, new BigDecimal(saldo), usuarioDe(email));
    }

    @Test
    void transferenciaEntreCadastradosEnfileiraOsDoisComprovantes() {
        Long origem = novaContaComDono("Gabriel", "1000.00", "gabriel-" + UUID.randomUUID() + "@exemplo.com");
        Long destino = novaContaComDono("Mariana", "0.00", "mariana-" + UUID.randomUUID() + "@exemplo.com");

        TransferResult r = transfers.execute(new TransferCommand(
                origem, destino, new BigDecimal("17.25"), UUID.randomUUID().toString()));
        drenarTudo();

        assertThat(chavesDe(r.transferId())).containsExactlyInAnyOrder(
                "transfer/" + r.transferId() + "/DEBIT",
                "transfer/" + r.transferId() + "/CREDIT");
    }

    @Test
    void transferenciaEntreContasDeDemonstracaoNaoEnfileiraNada() {
        contaDeDemonstracao(9001L, "Demo um", "1000.00", "demo-9001@banco.local");
        contaDeDemonstracao(9002L, "Demo dois", "500.00", "demo-9002@banco.local");

        TransferResult r = transfers.execute(new TransferCommand(
                9001L, 9002L, new BigDecimal("5.00"), UUID.randomUUID().toString()));
        drenarTudo();

        assertThat(chavesDe(r.transferId())).isEmpty();
    }

    @Test
    void pontaSemDonoNaoImpedeAOutra() {
        Long comDono = novaContaComDono("Gabriel", "1000.00", "gabriel2-" + UUID.randomUUID() + "@exemplo.com");
        Long orfa = novaConta("Orfa", "0.00");

        TransferResult r = transfers.execute(new TransferCommand(
                comDono, orfa, new BigDecimal("3.00"), UUID.randomUUID().toString()));
        drenarTudo();

        assertThat(chavesDe(r.transferId()))
                .containsExactly("transfer/" + r.transferId() + "/DEBIT");
    }

    @Test
    void segundaDrenagemNaoDuplicaComprovante() {
        Long origem = novaContaComDono("Gabriel", "1000.00", "gabriel3-" + UUID.randomUUID() + "@exemplo.com");
        Long destino = novaContaComDono("Mariana", "0.00", "mariana3-" + UUID.randomUUID() + "@exemplo.com");

        TransferResult r = transfers.execute(new TransferCommand(
                origem, destino, new BigDecimal("9.00"), UUID.randomUUID().toString()));
        drenarTudo();
        // Devolve o evento a pendente para forcar a reentrega que o outbox
        // promete: a entrega e "pelo menos uma vez".
        jdbc.update("UPDATE outbox_events SET published = false WHERE payload->>'transferId' = ?",
                r.transferId().toString());
        drenarTudo();

        assertThat(chavesDe(r.transferId())).hasSize(2);
    }
}
