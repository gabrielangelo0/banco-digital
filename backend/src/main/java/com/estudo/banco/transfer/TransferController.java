package com.estudo.banco.transfer;

import com.estudo.banco.transfer.dto.TransferRequest;
import com.estudo.banco.transfer.dto.TransferResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferService transfers;

    public TransferController(TransferService transfers) {
        this.transfers = transfers;
    }

    @PostMapping
    public TransferResponse transferir(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {

        TransferResult resultado = transfers.execute(new TransferCommand(
                request.sourceAccountId(), request.targetAccountId(),
                request.amount(), idempotencyKey));

        return TransferResponse.from(resultado);
    }
}
