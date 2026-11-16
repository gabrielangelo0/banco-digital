package com.estudo.banco.account;

import com.estudo.banco.account.dto.AccountResponse;
import com.estudo.banco.account.dto.EntryResponse;
import com.estudo.banco.auth.UsuarioAtual;
import com.estudo.banco.error.AcessoNegadoException;
import com.estudo.banco.error.ContaNaoEncontradaException;
import com.estudo.banco.ledger.EntryRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Tudo aqui e escopado pela sessao.
 *
 * `listar()` devolvia `findAll()`, o que era coerente num demo sem
 * autenticacao e vira vazamento no instante em que existem usuarios: a lista
 * de contas alheias e tambem a lista de quem tem conta aqui.
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountRepository accounts;
    private final EntryRepository entries;
    private final UsuarioAtual usuarioAtual;

    public AccountController(AccountRepository accounts, EntryRepository entries,
                             UsuarioAtual usuarioAtual) {
        this.accounts = accounts;
        this.entries = entries;
        this.usuarioAtual = usuarioAtual;
    }

    @GetMapping
    public List<AccountResponse> listar() {
        return accounts.findById(usuarioAtual.contaId())
                .map(AccountResponse::from)
                .map(List::of)
                .orElseGet(List::of);
    }

    @GetMapping("/{id}")
    public AccountResponse buscar(@PathVariable Long id) {
        exigirQueSejaMinha(id);
        return accounts.findById(id)
                .map(AccountResponse::from)
                .orElseThrow(() -> new ContaNaoEncontradaException(id));
    }

    @GetMapping("/{id}/statement")
    public List<EntryResponse> extrato(@PathVariable Long id) {
        exigirQueSejaMinha(id);
        if (!accounts.existsById(id)) {
            throw new ContaNaoEncontradaException(id);
        }
        return entries.findByAccountIdOrderByCreatedAtDescIdDesc(id).stream()
                .map(EntryResponse::from)
                .toList();
    }

    /**
     * 403 antes de olhar se a conta existe, de proposito: responder 404 para
     * conta alheia inexistente e 403 para conta alheia existente entrega um
     * verificador de contas.
     */
    private void exigirQueSejaMinha(Long id) {
        if (!id.equals(usuarioAtual.contaId())) {
            throw new AcessoNegadoException();
        }
    }
}
