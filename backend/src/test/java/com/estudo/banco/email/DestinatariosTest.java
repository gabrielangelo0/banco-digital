package com.estudo.banco.email;

import com.estudo.banco.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Quem pode receber e-mail.
 *
 * A regra de conta de demonstracao e exercitada em
 * {@code ComprovanteNaTransferenciaTest}, onde `banco.demo.enabled` esta
 * ligado; aqui ela ficaria sempre falsa e o teste nao provaria nada.
 */
class DestinatariosTest extends PostgresTestBase {

    @Autowired
    Destinatarios destinatarios;

    @Test
    void contaCadastradaResolveParaOEmailDoDono() {
        Long conta = novaContaComDono("Mariana", "1000.00", "mariana@exemplo.com");

        assertThat(destinatarios.para(conta)).contains(
                new Destinatario("mariana@exemplo.com", "Mariana"));
    }

    @Test
    void contaSemDonoNaoResolve() {
        assertThat(destinatarios.para(novaConta("Orfa", "100.00"))).isEmpty();
    }

    @Test
    void enderecoLocalNaoResolve() {
        Long conta = novaContaComDono("Demo", "1000.00", "demo-99@banco.local");

        assertThat(destinatarios.para(conta)).isEmpty();
    }

    @Test
    void contaInexistenteNaoResolve() {
        assertThat(destinatarios.para(999_999L)).isEmpty();
    }
}
