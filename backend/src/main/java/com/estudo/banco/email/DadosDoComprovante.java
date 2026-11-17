package com.estudo.banco.email;

import com.estudo.banco.ledger.Direction;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * O que um comprovante precisa saber.
 *
 * Note que ha o nome de UM titular so: o dono deste comprovante. A outra ponta
 * entra apenas como numero de conta, e isso e deliberado - ver
 * {@code EmailTemplates}.
 *
 * `quando` sai de `transfers.created_at`, lido na hora de enfileirar, e nao de
 * um campo do evento: acrescentar campo mudaria o formato do payload, e os
 * eventos ja gravados em producao voltariam com nulo ali.
 */
public record DadosDoComprovante(
        UUID transferId,
        Direction direcao,
        BigDecimal valor,
        String nomeDoDono,
        Long contaDoDono,
        Long contaDaOutraPonta,
        OffsetDateTime quando) {
}
