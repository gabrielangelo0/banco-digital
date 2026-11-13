package com.estudo.banco.transfer;

import com.estudo.banco.error.ContaNaoEncontradaException;
import com.estudo.banco.error.SaldoInsuficienteException;
import com.estudo.banco.error.TransferenciaInvalidaException;
import com.estudo.banco.ledger.Entry;
import com.estudo.banco.ledger.EntryRepository;
import com.estudo.banco.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferServiceTest extends PostgresTestBase {

    @Autowired
    TransferService transfers;

    @Autowired
    EntryRepository entries;

    private TransferCommand comando(Long origem, Long destino, String valor) {
        return new TransferCommand(origem, destino, new BigDecimal(valor), UUID.randomUUID().toString());
    }

    @Test
    void transfereMovimentandoOsDoisSaldos() {
        Long origem = novaConta("Origem", "1000.00");
        Long destino = novaConta("Destino", "500.00");

        TransferResult resultado = transfers.execute(comando(origem, destino, "200.00"));

        assertThat(resultado.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(saldoDe(origem)).isEqualByComparingTo("800.00");
        assertThat(saldoDe(destino)).isEqualByComparingTo("700.00");
    }

    @Test
    void geraDoisLancamentosQueSomamZero() {
        Long origem = novaConta("Origem", "1000.00");
        Long destino = novaConta("Destino", "0.00");

        TransferResult resultado = transfers.execute(comando(origem, destino, "150.00"));

        List<Entry> lancamentos = entries.findByTransferId(resultado.transferId());
        assertThat(lancamentos).hasSize(2);
        BigDecimal soma = lancamentos.stream()
                .map(Entry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(soma).isEqualByComparingTo("0.00");
    }

    @Test
    void saldoBateComSomaDoRazao() {
        Long origem = novaConta("Origem", "1000.00");
        Long destino = novaConta("Destino", "500.00");

        transfers.execute(comando(origem, destino, "120.00"));
        transfers.execute(comando(destino, origem, "45.00"));

        // Reconciliacao: o saldo materializado tem que ser exatamente o saldo
        // inicial mais a soma dos lancamentos do razao.
        assertThat(saldoDe(origem))
                .isEqualByComparingTo(new BigDecimal("1000.00").add(entries.sumAmountByAccountId(origem)));
        assertThat(saldoDe(destino))
                .isEqualByComparingTo(new BigDecimal("500.00").add(entries.sumAmountByAccountId(destino)));
    }

    @Test
    void saldoInsuficienteNaoDeixaRastro() {
        Long origem = novaConta("Origem", "100.00");
        Long destino = novaConta("Destino", "0.00");

        assertThatThrownBy(() -> transfers.execute(comando(origem, destino, "999.00")))
                .isInstanceOf(SaldoInsuficienteException.class);

        // Atomicidade: nem saldo mexido, nem lancamento orfao.
        assertThat(saldoDe(origem)).isEqualByComparingTo("100.00");
        assertThat(saldoDe(destino)).isEqualByComparingTo("0.00");
        assertThat(entries.sumAmountByAccountId(origem)).isEqualByComparingTo("0.00");
        assertThat(entries.sumAmountByAccountId(destino)).isEqualByComparingTo("0.00");
    }

    @Test
    void valorZeroEhRejeitado() {
        Long origem = novaConta("Origem", "100.00");
        Long destino = novaConta("Destino", "0.00");

        assertThatThrownBy(() -> transfers.execute(comando(origem, destino, "0.00")))
                .isInstanceOf(TransferenciaInvalidaException.class);
        assertThat(entries.sumAmountByAccountId(origem)).isEqualByComparingTo("0.00");
    }

    @Test
    void valorNegativoEhRejeitado() {
        Long origem = novaConta("Origem", "100.00");
        Long destino = novaConta("Destino", "0.00");

        assertThatThrownBy(() -> transfers.execute(comando(origem, destino, "-5.00")))
                .isInstanceOf(TransferenciaInvalidaException.class);
        assertThat(saldoDe(destino)).isEqualByComparingTo("0.00");
    }

    @Test
    void transferenciaParaMesmaContaEhRejeitada() {
        Long conta = novaConta("Sozinho", "100.00");

        // Sem esta validacao, origem e destino caem na MESMA linha sob o lock
        // ordenado: debito e credito se anulam e sobram dois lancamentos
        // fantasma no extrato.
        assertThatThrownBy(() -> transfers.execute(comando(conta, conta, "10.00")))
                .isInstanceOf(TransferenciaInvalidaException.class);
        assertThat(entries.sumAmountByAccountId(conta)).isEqualByComparingTo("0.00");
    }

    @Test
    void contaInexistenteEhRejeitada() {
        Long origem = novaConta("Origem", "100.00");

        assertThatThrownBy(() -> transfers.execute(comando(origem, 999999L, "10.00")))
                .isInstanceOf(ContaNaoEncontradaException.class);
    }

    @Test
    void valorComTresCasasDecimaisEhRejeitado() {
        Long origem = novaConta("Origem", "100.00");
        Long destino = novaConta("Destino", "0.00");

        // 10.999 nao pode ser arredondado em silencio: em dinheiro, meio
        // centavo perdido e incidente, nao detalhe de formatacao.
        assertThatThrownBy(() -> transfers.execute(comando(origem, destino, "10.999")))
                .isInstanceOf(TransferenciaInvalidaException.class);
        assertThat(saldoDe(origem)).isEqualByComparingTo("100.00");
    }
}
