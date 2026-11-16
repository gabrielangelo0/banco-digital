package com.estudo.banco.demo;

import com.estudo.banco.account.AccountRepository;
import com.estudo.banco.transfer.TransferCommand;
import com.estudo.banco.transfer.TransferService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * Devolve a demonstracao ao estado inicial por LANCAMENTO COMPENSATORIO.
 *
 * Nao trunca nada. Razao de dupla entrada nao se corrige apagando linha: se
 * corrige com contrapartida - e e por isso que o historico de quem se
 * cadastrou nunca e tocado, mesmo compartilhando as mesmas tabelas.
 *
 * So funciona porque conta de demonstracao nao transfere para fora do par: se
 * o dinheiro escapasse, nao haveria de onde tira-lo de volta.
 */
@Service
@ConditionalOnProperty(name = "banco.demo.enabled", havingValue = "true")
public class DemoResetService {

    private static final Logger log = LoggerFactory.getLogger(DemoResetService.class);

    private final DemoAccounts demo;
    private final AccountRepository contas;
    private final TransferService transferencias;
    private final List<BigDecimal> saldosIniciais;

    public DemoResetService(DemoAccounts demo, AccountRepository contas,
                            TransferService transferencias,
                            @Value("${banco.demo.saldos-iniciais:1000.0000,500.0000}")
                            List<BigDecimal> saldosIniciais) {
        this.demo = demo;
        this.contas = contas;
        this.transferencias = transferencias;
        this.saldosIniciais = saldosIniciais;
    }

    /** De hora em hora: quem chega depois de alguem brincar encontra a demo inteira. */
    @Scheduled(cron = "${banco.demo.reset-cron:0 0 * * * *}")
    public void restaurarAgendado() {
        restaurar();
    }

    public void restaurar() {
        if (demo.ids().size() != 2 || saldosIniciais.size() != 2) {
            log.warn("reset da demonstracao ignorado: esperava exatamente duas contas");
            return;
        }
        Long primeira = demo.ids().get(0);
        Long segunda = demo.ids().get(1);

        BigDecimal atual = contas.findById(primeira).orElseThrow().getBalance();
        BigDecimal alvo = saldosIniciais.get(0);
        // O saldo e NUMERIC(19,4) e o dominio recusa mais de duas casas: a
        // diferenca precisa voltar a centavos. RoundingMode.UNNECESSARY de
        // proposito - se algum dia houver fracao de centavo, quero a excecao,
        // nao um arredondamento silencioso de dinheiro.
        BigDecimal diferenca = atual.subtract(alvo).setScale(2, RoundingMode.UNNECESSARY);

        if (diferenca.signum() == 0) {
            return;
        }

        // Positiva: a primeira tem demais, devolve para a segunda. Negativa: o
        // contrario. Em qualquer caso e uma transferencia comum, pelo caminho
        // normal, com as mesmas garantias de qualquer outra.
        Long origem = diferenca.signum() > 0 ? primeira : segunda;
        Long destino = diferenca.signum() > 0 ? segunda : primeira;

        transferencias.execute(new TransferCommand(
                origem, destino, diferenca.abs(), "reset-demo-" + UUID.randomUUID()));

        log.info("demonstracao restaurada por lancamento compensatorio de {}", diferenca.abs());
    }
}
