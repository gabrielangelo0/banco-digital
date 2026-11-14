package com.estudo.banco.account.dto;

import com.estudo.banco.account.Account;

import java.math.BigDecimal;

public record AccountResponse(Long id, String holderName, BigDecimal balance) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(account.getId(), account.getHolderName(), account.getBalance());
    }
}
