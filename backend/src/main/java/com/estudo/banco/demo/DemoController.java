package com.estudo.banco.demo;

import com.estudo.banco.account.AccountRepository;
import com.estudo.banco.auth.AttemptLimiter;
import com.estudo.banco.auth.ClientIpResolver;
import com.estudo.banco.auth.SessionCookie;
import com.estudo.banco.auth.SessionStore;
import com.estudo.banco.error.AcessoNegadoException;
import com.estudo.banco.error.MuitasTentativasException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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

    /**
     * Entrar como visitante nao custa hash nenhum, mas cria sessao no Redis
     * - e um Redis com teto de memoria. Vinte entradas por IP a cada quinze
     * minutos e mais do que qualquer pessoa curiosa precisa.
     */
    static final int TETO_IP_VISITANTE = 20;

    private final DemoAccounts demo;
    private final AccountRepository contas;
    private final SessionStore sessoes;
    private final AttemptLimiter tentativas;
    private final ClientIpResolver proxy;
    private final String senhaTransacional;

    public DemoController(DemoAccounts demo, AccountRepository contas, SessionStore sessoes,
                          AttemptLimiter tentativas, ClientIpResolver proxy,
                          @Value("${banco.demo.transaction-password}") String senhaTransacional) {
        this.demo = demo;
        this.contas = contas;
        this.sessoes = sessoes;
        this.tentativas = tentativas;
        this.proxy = proxy;
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
    public ResponseEntity<Void> entrar(@Valid @RequestBody EntrarComoVisitante pedido,
                                       HttpServletRequest http) {
        proxy.resolve(http).map(ip -> "ip-visitante:" + ip).ifPresent(chave -> {
            if (tentativas.bloqueado(chave, TETO_IP_VISITANTE)) {
                throw new MuitasTentativasException();
            }
            tentativas.registrar(chave);
        });

        if (!demo.ehDemonstracao(pedido.accountId())) {
            throw new AcessoNegadoException();
        }
        Long dono = contas.findById(pedido.accountId())
                .map(c -> c.getOwnerId())
                .orElseThrow(AcessoNegadoException::new);

        String token = sessoes.create(dono, SessionStore.TTL_VISITANTE);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        SessionCookie.build(token, proxy.ehHttps(http), SessionStore.TTL_VISITANTE)
                                .toString())
                .build();
    }
}
