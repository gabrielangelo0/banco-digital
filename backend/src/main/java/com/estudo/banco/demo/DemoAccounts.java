package com.estudo.banco.demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Quais contas sao de demonstracao.
 *
 * Sempre existe como bean, ligado ou nao: a regra de transferencia precisa
 * perguntar "esta conta e de demonstracao?" mesmo num ambiente onde a
 * demonstracao esta desligada, e a resposta ali e simplesmente nao.
 */
@Component
public class DemoAccounts {

    private final boolean habilitado;
    private final List<Long> ids;

    public DemoAccounts(@Value("${banco.demo.enabled:false}") boolean habilitado,
                        @Value("${banco.demo.account-ids:1,2}") List<Long> ids) {
        this.habilitado = habilitado;
        this.ids = ids;
    }

    public boolean habilitado() {
        return habilitado;
    }

    public boolean ehDemonstracao(Long contaId) {
        return habilitado && contaId != null && ids.contains(contaId);
    }

    public List<Long> ids() {
        return ids;
    }
}
