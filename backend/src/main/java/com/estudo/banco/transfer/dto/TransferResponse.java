package com.estudo.banco.transfer.dto;

import com.estudo.banco.transfer.TransferResult;
import com.estudo.banco.transfer.TransferStatus;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * O saldo do DESTINO nao sai daqui.
 *
 * Era inofensivo quando nao havia identidade: todas as contas eram de
 * mentira. Com usuarios de verdade, devolver o saldo do destino transforma
 * a transferencia num diretorio de saldos alheios - o id da conta e
 * bigserial, entao bastava varrer 1..N transferindo um centavo. O front
 * nunca usou esse campo.
 */
public record TransferResponse(
        UUID transferId,
        TransferStatus status,
        BigDecimal sourceBalance) {

    public static TransferResponse from(TransferResult r) {
        return new TransferResponse(r.transferId(), r.status(), r.sourceBalance());
    }
}
