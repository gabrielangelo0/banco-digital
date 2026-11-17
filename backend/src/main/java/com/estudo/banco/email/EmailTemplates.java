package com.estudo.banco.email;

import com.estudo.banco.ledger.Direction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Os textos dos e-mails.
 *
 * HTML em tabela com estilo embutido e nenhuma imagem externa: cliente de
 * e-mail nao e navegador. Folha de estilo separada e descartada, grid e flex
 * nao existem no Outlook, e imagem remota costuma chegar bloqueada - entao o
 * comprovante tem que se sustentar sem ela.
 *
 * Sem motor de template de proposito: sao tres textos, e trazer Thymeleaf para
 * isto seria uma dependencia nova para montar string.
 */
@Component
public class EmailTemplates {

    private static final Locale BRASIL = Locale.forLanguageTag("pt-BR");
    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter DATA_HORA =
            DateTimeFormatter.ofPattern("dd/MM/yyyy' às 'HH:mm", BRASIL);

    private static final String VERDE = "#0d8139";
    private static final String TINTA = "#0d1411";
    private static final String CINZA = "#55605b";
    private static final String CINZA_FRACO = "#8a938f";
    private static final String LINHA = "#eef1ef";

    // ---------------------------------------------------------------- comprovante

    public EmailParaEnviar comprovante(String destinatario, DadosDoComprovante dados) {
        boolean recebida = dados.direcao() == Direction.CREDIT;
        String titulo = recebida ? "Transferência recebida" : "Transferência realizada";
        String valor = dinheiro(dados.valor());
        String quando = momento(dados.quando());
        String identificador = dados.transferId().toString();

        // O dono deste comprovante aparece pelo nome; a outra ponta, so pelo
        // numero da conta. O app nao conta a ninguem quem e o dono de uma
        // conta - nao ha busca de contas justamente por isso -, e o nome do
        // titular e derivado do comeco do e-mail no cadastro, entao exibi-lo
        // devolveria um pedaco do endereco de alguem a outra pessoa.
        String donoHtml = escapar(dados.nomeDoDono()) + " · conta " + dados.contaDoDono();
        String donoTexto = semMarcacao(dados.nomeDoDono()) + " · conta " + dados.contaDoDono();
        String outra = "conta " + dados.contaDaOutraPonta();
        String de = recebida ? outra : donoHtml;
        String para = recebida ? donoHtml : outra;
        // O texto puro nao e interpretado, entao leva o nome cru: reusar a
        // string escapada aqui faria um "&" virar "&amp;" a vista de quem le.
        String deTexto = recebida ? outra : donoTexto;
        String paraTexto = recebida ? donoTexto : outra;

        String html = moldura("Comprovante", """
                <p style="margin:0;font-size:11.5px;color:%s;">%s</p>
                <p style="margin:2px 0 16px;font-size:26px;font-weight:800;color:%s;letter-spacing:-.03em;">%s</p>
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="border-collapse:collapse;font-size:12px;">
                  %s
                  %s
                  %s
                  %s
                </table>
                <p style="margin:14px 0 0;font-size:10.5px;line-height:1.5;color:%s;">
                  Guarde o identificador: é por ele que esta transferência é encontrada no
                  extrato das duas contas. Ele também funciona na busca do app.
                </p>
                """.formatted(
                CINZA, titulo, TINTA, valor,
                campo("De", de),
                campo("Para", para),
                campo("Quando", quando + " (horário de Brasília)"),
                campoMono("Identificador", identificador),
                CINZA_FRACO));

        String texto = """
                %s

                %s

                De: %s
                Para: %s
                Quando: %s (horário de Brasília)
                Identificador: %s

                Guarde o identificador: é por ele que esta transferência é encontrada no
                extrato das duas contas.

                %s
                """.formatted(titulo, valor,
                deTexto, paraTexto, quando, identificador, RODAPE_TEXTO);

        return new EmailParaEnviar(
                destinatario,
                // O assunto e a unica coisa visivel na lista da caixa de
                // entrada: ele precisa responder "entrou ou saiu?" sozinho.
                (recebida ? "Transferência recebida — " : "Comprovante de transferência — ") + valor,
                html,
                texto,
                "transfer/" + dados.transferId() + "/" + dados.direcao().name());
    }

    // ---------------------------------------------------------------- boas-vindas

    public EmailParaEnviar boasVindas(String destinatario, String nome, Long usuarioId,
                                      Long contaId, BigDecimal saldoInicial) {
        String saldo = dinheiro(saldoInicial);

        String html = moldura("Bem-vindo", """
                <p style="margin:0;font-size:11.5px;color:%s;">Sua conta está aberta</p>
                <p style="margin:2px 0 16px;font-size:22px;font-weight:800;color:%s;letter-spacing:-.03em;">Olá, %s</p>
                <p style="margin:0 0 14px;font-size:13.5px;line-height:1.55;color:%s;">
                  Você tem a <strong>conta %s</strong>, e ela começou com <strong>%s</strong>
                  para você ter o que mover sem depender de ninguém.
                </p>
                <p style="margin:0 0 14px;font-size:13.5px;line-height:1.55;color:%s;">
                  Sua conta tem <strong>duas senhas</strong>. A de acesso prova quem você é;
                  a segunda senha, a transacional, é a que autoriza mover dinheiro. Entrar
                  no app não basta para transferir — e é assim de propósito.
                </p>
                <p style="margin:0;font-size:12px;line-height:1.55;color:%s;">
                  A cada transferência, você recebe o comprovante neste endereço.
                </p>
                """.formatted(CINZA, TINTA, escapar(nome), TINTA, contaId, saldo, TINTA, CINZA));

        String texto = """
                Olá, %s. Sua conta está aberta.

                Você tem a conta %s, e ela começou com %s.

                Sua conta tem duas senhas. A de acesso prova quem você é;
                a segunda senha, a transacional, é a que autoriza mover dinheiro.
                Entrar no app não basta para transferir.

                A cada transferência, você recebe o comprovante neste endereço.

                %s
                """.formatted(semMarcacao(nome), contaId, saldo, RODAPE_TEXTO);

        return new EmailParaEnviar(destinatario, "Sua conta no Banco está aberta",
                html, texto, "welcome/" + usuarioId);
    }

    // ---------------------------------------------------------------- pecas

    private static final String RODAPE_TEXTO =
            "Você recebeu este e-mail porque cadastrou este endereço no Banco. "
                    + "Este é um projeto de estudo — o dinheiro não é real.";

    /** O cabecalho com a regua verde, o corpo, e o rodape que explica o porque. */
    private String moldura(String etiqueta, String corpo) {
        return """
                <!doctype html>
                <html lang="pt-BR"><body style="margin:0;padding:16px;background:#eceeed;">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="border-collapse:collapse;">
                <tr><td align="center">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="max-width:560px;border-collapse:collapse;background:#ffffff;border:1px solid #e0e5e2;border-radius:10px;font-family:'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
                  <tr><td style="padding:16px 18px;border-bottom:2px solid %s;">
                    <strong style="font-size:14px;color:%s;letter-spacing:-.02em;">Banco</strong>
                    <span style="float:right;font-size:10.5px;color:%s;text-transform:uppercase;letter-spacing:.08em;">%s</span>
                  </td></tr>
                  <tr><td style="padding:18px;">%s</td></tr>
                  <tr><td style="padding:0 18px 18px;">
                    <p style="margin:0;padding-top:12px;border-top:1px solid %s;font-size:11px;line-height:1.55;color:%s;">%s</p>
                  </td></tr>
                </table>
                </td></tr>
                </table>
                </body></html>
                """.formatted(VERDE, TINTA, CINZA_FRACO, etiqueta, corpo, LINHA, CINZA_FRACO,
                RODAPE_TEXTO);
    }

    private String campo(String rotulo, String valor) {
        return """
                <tr>
                  <td style="padding:7px 0;color:%s;border-top:1px solid %s;">%s</td>
                  <td style="padding:7px 0;color:%s;border-top:1px solid %s;text-align:right;font-weight:600;">%s</td>
                </tr>
                """.formatted(CINZA, LINHA, rotulo, TINTA, LINHA, valor);
    }

    private String campoMono(String rotulo, String valor) {
        return """
                <tr>
                  <td style="padding:7px 0;color:%s;border-top:1px solid %s;vertical-align:top;">%s</td>
                  <td style="padding:7px 0;border-top:1px solid %s;text-align:right;font-family:Menlo,Consolas,monospace;font-size:10.5px;color:%s;word-break:break-all;">%s</td>
                </tr>
                """.formatted(CINZA, LINHA, rotulo, LINHA, CINZA, escapar(valor));
    }

    /**
     * Dinheiro com duas casas.
     *
     * O servidor guarda quatro ("17.2500"); arredondar e explicito porque
     * `toString()` num BigDecimal de quatro casas imprimiria as quatro.
     *
     * O espaco que o CLDR usa entre "R$" e o numero e um espaco nao separavel,
     * invisivel e diferente do espaco comum - quem for procurar "R$ 17,25" no
     * corpo nao encontraria. Aqui ele vira espaco comum.
     */
    private String dinheiro(BigDecimal valor) {
        return NumberFormat.getCurrencyInstance(BRASIL)
                .format(valor.abs().setScale(2, RoundingMode.HALF_UP))
                .replace(' ', ' ');
    }

    private String momento(OffsetDateTime quando) {
        return quando.atZoneSameInstant(BRASILIA).format(DATA_HORA);
    }

    /**
     * O nome do titular e derivado do comeco do e-mail no cadastro, entao e
     * texto que veio de fora e vai para dentro do HTML. O `&` sai primeiro: se
     * saisse depois, escaparia as proprias entidades recem-criadas.
     */
    private String escapar(String texto) {
        if (texto == null) {
            return "";
        }
        return texto.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /** A versao em texto puro nao escapa nada; ela nao e interpretada. */
    private String semMarcacao(String texto) {
        return texto == null ? "" : texto;
    }
}
