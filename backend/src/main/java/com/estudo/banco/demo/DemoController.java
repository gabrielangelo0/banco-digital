package com.estudo.banco.demo;

import com.estudo.banco.account.AccountRepository;
import com.estudo.banco.auth.SessionCookie;
import com.estudo.banco.auth.SessionStore;
import com.estudo.banco.error.AcessoNegadoException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * As duas portas da demonstracao. Existem so com `banco.demo.enabled=true`:
 * num ambiente de verdade nao deve haver entrada sem senha nem por engano.
 */
@RestController
@ConditionalOnProperty(name = "banco.demo.enabled", havingValue = "true")
public class DemoController {

    public record ContaDeDemonstracao(Long id, String holderName) {
    }

    public record DemoInfo(List<ContaDeDemonstracao> accounts, String transactionPassword) {
    }

    public record EntrarComoVisitante(@NotNull Long accountId) {
    }

    private final DemoAccounts demo;
    private final AccountRepository contas;
    private final SessionStore sessoes;
    private final String senhaTransacional;

    public DemoController(DemoAccounts demo, AccountRepository contas, SessionStore sessoes,
                          @Value("${banco.demo.transaction-password}") String senhaTransacional) {
        this.demo = demo;
        this.contas = contas;
        this.sessoes = sessoes;
        this.senhaTransacional = senhaTransacional;
    }

    @GetMapping("/api/demo")
    public DemoInfo info() {
        List<ContaDeDemonstracao> disponiveis = demo.ids().stream()
                .flatMap(id -> contas.findById(id).stream())
                .map(c -> new ContaDeDemonstracao(c.getId(), c.getHolderName()))
                .toList();

        // A senha transacional da demonstracao e exposta de proposito: exibi-la
        // mantem o recurso demonstravel sem abrir um caminho de excecao que
        // alguem usaria em producao.
        return new DemoInfo(disponiveis, senhaTransacional);
    }

    @PostMapping("/api/auth/guest")
    public ResponseEntity<Void> entrar(@RequestBody EntrarComoVisitante pedido,
                                       HttpServletRequest http) {
        if (!demo.ehDemonstracao(pedido.accountId())) {
            throw new AcessoNegadoException();
        }
        Long dono = contas.findById(pedido.accountId())
                .map(c -> c.getOwnerId())
                .orElseThrow(AcessoNegadoException::new);

        String token = sessoes.create(dono);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, SessionCookie.build(token, http.isSecure()).toString())
                .build();
    }
}
