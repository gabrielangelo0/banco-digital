package com.estudo.banco.transfer;

import com.estudo.banco.demo.DemoAccounts;
import com.estudo.banco.auth.UsuarioAtual;
import com.estudo.banco.error.DestinoForaDaDemonstracaoException;
import com.estudo.banco.error.TransferenciaInvalidaException;
import com.estudo.banco.transfer.dto.TransferRequest;
import com.estudo.banco.transfer.dto.TransferResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferService transfers;
    private final SenhaTransacionalGuard senhaTransacional;
    private final DemoAccounts demo;
    private final UsuarioAtual usuarioAtual;

    public TransferController(TransferService transfers,
                              SenhaTransacionalGuard senhaTransacional,
                              UsuarioAtual usuarioAtual,
                              DemoAccounts demo) {
        this.transfers = transfers;
        this.senhaTransacional = senhaTransacional;
        this.usuarioAtual = usuarioAtual;
        this.demo = demo;
    }

    @PostMapping
    public TransferResponse transferir(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            // Sem chave nao ha protecao contra retry do cliente, e aceitar a
            // transferencia assim seria pior do que recusar.
            throw new TransferenciaInvalidaException(
                    "Header Idempotency-Key e obrigatorio");
        }

        // Autorizar antes de qualquer transacao abrir. Senha errada nao deve
        // nem chegar ao executor.
        Long origem = usuarioAtual.contaId();

        // O par de contas de demonstracao e FECHADO, nas duas direcoes.
        //
        // Dinheiro que sai nao tem de onde voltar; dinheiro que ENTRA muda a
        // soma do par, e entao nenhuma transferencia interna devolve as duas
        // contas ao saldo inicial - o reset horario deixa de fechar para
        // sempre. Bloquear so a saida era meia regra, e o placeholder do
        // campo de destino ("ex.: 2") convida justamente a outra metade.
        boolean origemEhDemo = demo.ehDemonstracao(origem);
        boolean destinoEhDemo = demo.ehDemonstracao(request.targetAccountId());
        if (origemEhDemo != destinoEhDemo) {
            throw new DestinoForaDaDemonstracaoException();
        }

        senhaTransacional.exigir(origem, request.transactionPassword());

        TransferResult resultado = transfers.execute(new TransferCommand(
                origem, request.targetAccountId(), request.amount(), idempotencyKey));

        return TransferResponse.from(resultado);
    }
}
