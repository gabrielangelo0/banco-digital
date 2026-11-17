package com.estudo.banco.email;

/**
 * Para quem mandar, e com que nome tratar.
 *
 * O `nome` e o titular da conta DESTE destinatario, nunca o da outra ponta: o
 * comprovante identifica pelo nome so o proprio dono, e a contraparte aparece
 * apenas pelo numero da conta. Ver {@code EmailTemplates}.
 */
public record Destinatario(String email, String nome) {
}
