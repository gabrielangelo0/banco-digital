package com.estudo.banco.demo;

import com.estudo.banco.auth.RegistrationService;
import com.estudo.banco.ledger.EntryRepository;
import com.estudo.banco.transfer.TransferCommand;
import com.estudo.banco.transfer.TransferService;
import com.estudo.banco.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Razao de dupla entrada nao se corrige apagando linha - se corrige com
 * contrapartida. O reset devolve os saldos lancando o inverso, nao truncando
 * a tabela, e por isso o historico de quem se cadastrou nunca e tocado.
 */
@TestPropertySource(properties = {
        "banco.demo.enabled=true",
        "banco.demo.password=demo-senha-de-teste",
        "banco.demo.transaction-password=demo123"
})
class ResetCompensatorioTest extends PostgresTestBase {

    @Autowired
    DemoResetService reset;

    @Autowired
    EntryRepository entries;

    @Autowired
    RegistrationService registration;

    @Autowired
    TransferService transferencias;

    private BigDecimal saldo(long contaId) {
        return jdbc.queryForObject("select balance from accounts where id = ?", BigDecimal.class, contaId);
    }

    @Test
    void resetRestauraSaldosSemApagarRazao() {
        long entradasAntes = entries.count();

        // Desequilibra o par pelo caminho normal, como um visitante faria.
        transferencias.execute(new TransferCommand(
                1L, 2L, new BigDecimal("123.45"), "desequilibrio-" + UUID.randomUUID()));
        assertThat(saldo(1L)).isNotEqualByComparingTo("1000.0000");

        reset.restaurar();

        assertThat(saldo(1L)).isEqualByComparingTo("1000.0000");
        assertThat(saldo(2L)).isEqualByComparingTo("500.0000");
        // Compensou, nao apagou: o razao so cresce.
        assertThat(entries.count()).isGreaterThan(entradasAntes);
    }

    @Test
    void resetNaoTocaEmContaRegistrada() {
        Long registrada = registration.register(
                "pam-" + UUID.randomUUID() + "@exemplo.com", "senha-forte-1", "654321");
        BigDecimal antes = saldo(registrada);

        reset.restaurar();

        assertThat(saldo(registrada)).isEqualByComparingTo(antes);
    }

    @Test
    void resetComSaldosJaCertosNaoLancaNada() {
        reset.restaurar();
        long depoisDoPrimeiro = entries.count();

        reset.restaurar();

        assertThat(entries.count()).isEqualTo(depoisDoPrimeiro);
    }
}
