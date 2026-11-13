package com.estudo.banco.idempotency;

import com.estudo.banco.transfer.TransferResult;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

@Service
public class IdempotencyService {

    private final IdempotencyKeyRepository chaves;
    private final ObjectMapper mapper;

    public IdempotencyService(IdempotencyKeyRepository chaves, ObjectMapper mapper) {
        this.chaves = chaves;
        this.mapper = mapper;
    }

    public Optional<TransferResult> findExisting(String chave) {
        if (chave == null || chave.isBlank()) {
            return Optional.empty();
        }
        return chaves.findById(chave).map(k -> mapper.readValue(k.getResponseBody(), TransferResult.class));
    }

    /**
     * Grava a chave consumida.
     *
     * O flush e explicito: a violacao da unique constraint precisa acontecer
     * AQUI, ainda dentro da transacao da transferencia, para que o perdedor da
     * corrida tenha o debito e o credito desfeitos junto.
     */
    public void registrar(String chave, TransferResult resultado) {
        chaves.saveAndFlush(new IdempotencyKey(
                chave, resultado.transferId(), mapper.writeValueAsString(resultado)));
    }
}
