package com.estudo.banco.transfer;

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
    private final com.estudo.banco.demo.DemoAccounts demo;
    private final com.estudo.banco.auth.UsuarioAtual usuarioAtual;

    public TransferController(TransferService transfers,
                              SenhaTransacionalGuard senhaTransacional,
                              com.estudo.banco.auth.UsuarioAtual usuarioAtual,
                              com.estudo.banco.demo.DemoAccounts demo) {
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

        // Dinheiro nao escapa do par de contas de demonstracao: se escapasse,
        // o reset nao teria de onde tirar a diferenca de volta.
        if (demo.ehDemonstracao(origem) && !demo.ehDemonstracao(request.targetAccountId())) {
            throw new com.estudo.banco.error.DestinoForaDaDemonstracaoException();
        }

        senhaTransacional.exigir(origem, request.transactionPassword());

        TransferResult resultado = transfers.execute(new TransferCommand(
                origem, request.targetAccountId(), request.amount(), idempotencyKey));

        return TransferResponse.from(resultado);
    }
}
