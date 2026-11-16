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

    @ExceptionHandler(TransferenciaInvalidaException.class)
    public ResponseEntity<ApiError> transferenciaInvalida(TransferenciaInvalidaException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("TRANSFERENCIA_INVALIDA", e.getMessage()));
    }

    @ExceptionHandler(MuitasTentativasException.class)
    public ResponseEntity<ApiError> muitasTentativas(MuitasTentativasException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(new ApiError("MUITAS_TENTATIVAS", e.getMessage()));
    }

    @ExceptionHandler(SenhaTransacionalInvalidaException.class)
    public ResponseEntity<ApiError> senhaTransacionalInvalida(SenhaTransacionalInvalidaException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiError("SENHA_TRANSACIONAL_INVALIDA", e.getMessage()));
    }

    @ExceptionHandler(AcessoNegadoException.class)
    public ResponseEntity<ApiError> acessoNegado(AcessoNegadoException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiError("ACESSO_NEGADO", e.getMessage()));
    }

    @ExceptionHandler(CredenciaisInvalidasException.class)
    public ResponseEntity<ApiError> credenciaisInvalidas(CredenciaisInvalidasException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiError("CREDENCIAIS_INVALIDAS", e.getMessage()));
    }

    @ExceptionHandler(CadastroInvalidoException.class)
    public ResponseEntity<ApiError> cadastroInvalido(CadastroInvalidoException e) {
        // 409, e a mensagem nao diz QUAL regra falhou: distinguir "e-mail ja
        // existe" de "senha fraca" entrega ao atacante um verificador de
        // cadastro.
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("CADASTRO_INVALIDO", e.getMessage()));
    }

    @ExceptionHandler(SaldoInsuficienteException.class)
    public ResponseEntity<ApiError> saldoInsuficiente(SaldoInsuficienteException e) {
        // 422: a requisicao esta bem formada, mas a regra de negocio recusa.
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ApiError("SALDO_INSUFICIENTE", e.getMessage()));
    }
}
