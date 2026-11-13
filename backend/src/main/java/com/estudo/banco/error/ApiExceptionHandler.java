package com.estudo.banco.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Traduz excecoes de dominio em status HTTP.
 *
 * Nenhuma excecao de dominio vaza como 500: um erro de negocio previsto nao e
 * falha do servidor.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ContaNaoEncontradaException.class)
    public ResponseEntity<ApiError> contaNaoEncontrada(ContaNaoEncontradaException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("CONTA_NAO_ENCONTRADA", e.getMessage()));
    }

    @ExceptionHandler(SaldoInsuficienteException.class)
    public ResponseEntity<ApiError> saldoInsuficiente(SaldoInsuficienteException e) {
        // 422: a requisicao esta bem formada, mas a regra de negocio recusa.
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ApiError("SALDO_INSUFICIENTE", e.getMessage()));
    }
}
