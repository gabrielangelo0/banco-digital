package com.estudo.banco.email;

import com.estudo.banco.ledger.Direction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Os textos dos e-mails.
 *
 * Sem contexto do Spring de proposito: {@code EmailTemplates} nao tem
 * dependencia nenhuma, e um teste que sobe Postgres para montar string custa
 * segundos por nada.
 */
class EmailTemplatesTest {

    private final EmailTemplates textos = new EmailTemplates();

    private static final UUID ID = UUID.fromString("cfb01ea9-3d7a-4e21-9f44-2b1c8e5a7d90");
    private static final OffsetDateTime QUANDO =
            OffsetDateTime.parse("2026-11-17T17:18:00Z"); // 14:18 em Brasilia

    private DadosDoComprovante dados(Direction direcao, String valor) {
        return new DadosDoComprovante(ID, direcao, new BigDecimal(valor),
                "Gabriel", 1L, 7L, QUANDO);
    }

    @Test
    void comprovanteEnviadoTrazValorContasEIdentificador() {
        EmailParaEnviar m = textos.comprovante("g@exemplo.com", dados(Direction.DEBIT, "17.2500"));

        assertThat(m.assunto()).isEqualTo("Comprovante de transferência — R$ 17,25");
        assertThat(m.html()).contains("R$ 17,25", "Gabriel", "conta 1", "conta 7", ID.toString());
        assertThat(m.texto()).contains("R$ 17,25", ID.toString());
    }

    @Test
    void comprovanteNaoRevelaONomeDaOutraPonta() {
        EmailParaEnviar m = textos.comprovante("g@exemplo.com", dados(Direction.DEBIT, "17.2500"));

        // "Gabriel" e o dono deste comprovante; a outra ponta aparece so pelo numero.
        assertThat(m.html()).doesNotContain("Mariana");
    }

    @Test
    void horarioSaiEmBrasiliaNaoEmUtc() {
        EmailParaEnviar m = textos.comprovante("g@exemplo.com", dados(Direction.DEBIT, "17.2500"));

        assertThat(m.html()).contains("17/11/2026").contains("14:18").doesNotContain("17:18");
    }

    @Test
    void valorDeQuatroCasasVira2Casas() {
        EmailParaEnviar m = textos.comprovante("g@exemplo.com", dados(Direction.CREDIT, "1000.0000"));

        assertThat(m.html()).contains("R$ 1.000,00").doesNotContain("1000.0000");
    }

    @Test
    void nomeComMarcacaoEEscapado() {
        DadosDoComprovante perigoso = new DadosDoComprovante(ID, Direction.DEBIT,
                new BigDecimal("10.0000"), "<script>x</script>&\"", 1L, 7L, QUANDO);

        EmailParaEnviar m = textos.comprovante("g@exemplo.com", perigoso);

        assertThat(m.html()).doesNotContain("<script>")
                // O `&` tem de ser escapado ANTES dos outros, senao escaparia
                // as entidades que ele mesmo acabou de criar.
                .contains("&lt;script&gt;", "&amp;", "&quot;");
    }

    @Test
    void creditoFalaEmRecebidaJaNoAssunto() {
        EmailParaEnviar m = textos.comprovante("m@exemplo.com", dados(Direction.CREDIT, "17.2500"));

        // O assunto e a unica coisa visivel na lista da caixa de entrada.
        // "Comprovante de transferencia" nos dois sentidos faz quem recebeu
        // dinheiro ler que saiu.
        assertThat(m.assunto()).isEqualTo("Transferência recebida — R$ 17,25");
        assertThat(m.html()).contains("Transferência recebida");
    }

    @Test
    void chaveDoComprovanteTrazDirecao() {
        assertThat(textos.comprovante("g@e.com", dados(Direction.DEBIT, "1.0000")).chave())
                .isEqualTo("transfer/" + ID + "/DEBIT");
        assertThat(textos.comprovante("m@e.com", dados(Direction.CREDIT, "1.0000")).chave())
                .isEqualTo("transfer/" + ID + "/CREDIT");
    }

    @Test
    void boasVindasTrazSaldoInicialEAvisoDaSegundaSenha() {
        EmailParaEnviar m = textos.boasVindas("novo@exemplo.com", "Novo", 42L, 7L,
                new BigDecimal("1000.0000"));

        assertThat(m.chave()).isEqualTo("welcome/42");
        assertThat(m.html()).contains("R$ 1.000,00", "conta 7").doesNotContain("conta 42");
        assertThat(m.texto()).containsIgnoringCase("segunda senha");
    }
}
