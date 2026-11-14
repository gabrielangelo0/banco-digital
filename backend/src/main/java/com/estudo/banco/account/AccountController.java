package com.estudo.banco.account;

import com.estudo.banco.account.dto.AccountResponse;
import com.estudo.banco.account.dto.EntryResponse;
import com.estudo.banco.error.ContaNaoEncontradaException;
import com.estudo.banco.ledger.EntryRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountRepository accounts;
    private final EntryRepository entries;

    public AccountController(AccountRepository accounts, EntryRepository entries) {
        this.accounts = accounts;
        this.entries = entries;
    }

    @GetMapping
    public List<AccountResponse> listar() {
        return accounts.findAll().stream().map(AccountResponse::from).toList();
    }

    @GetMapping("/{id}")
    public AccountResponse buscar(@PathVariable Long id) {
        return accounts.findById(id)
                .map(AccountResponse::from)
                .orElseThrow(() -> new ContaNaoEncontradaException(id));
    }

    @GetMapping("/{id}/statement")
    public List<EntryResponse> extrato(@PathVariable Long id) {
        if (!accounts.existsById(id)) {
            throw new ContaNaoEncontradaException(id);
        }
        return entries.findByAccountIdOrderByCreatedAtDescIdDesc(id).stream()
                .map(EntryResponse::from)
                .toList();
    }
}
