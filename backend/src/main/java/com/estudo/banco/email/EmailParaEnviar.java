package com.estudo.banco.email;

/**
 * Uma mensagem pronta, antes de entrar na fila.
 *
 * A `chave` identifica o FATO que gerou a mensagem, nao a tentativa de envio:
 * `transfer/<id>/DEBIT` e a mesma chave quantas vezes o evento for reentregue.
 * E ela que vira a trava contra comprovante repetido, dos dois lados - no
 * indice unico daqui e no cabecalho `Idempotency-Key` do Resend.
 */
public record EmailParaEnviar(
        String destinatario,
        String assunto,
        String html,
        String texto,
        String chave) {
}
