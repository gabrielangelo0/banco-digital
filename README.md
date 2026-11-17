# Banco Digital — estudo de consistência em transferências

Aplicação bancária de estudo: **Spring Boot 4 + Postgres** no backend, **React PWA** no
frontend. O objetivo não é ter muitas funcionalidades — é demonstrar, com testes que
provam, como dinheiro se move entre contas sem se perder, duplicar ou travar.

**Demo ao vivo: [bank.devfly.net](https://bank.devfly.net)** — entre como visitante em uma
das duas contas de demonstração e transfira para a outra. A notificação que aparece no topo
chega por SSE, não por polling: o backend empurra o evento quando a transferência fecha.

A interface é clara, com o saldo num cartão escuro: o valor é a única coisa que precisa
gritar, e sobre quase preto ele grita sozinho, enquanto o resto da tela fica em branco e
cinza para não disputar. O mesmo app atende celular e desktop — no celular, abas na base;
acima de 1000px entra a coluna de navegação à esquerda, e acima de 1180px os cartões se
organizam em duas colunas. O identificador da transferência aparece em cada linha do
extrato, que é o que liga o extrato ao log do servidor.

Alguns atalhos da tela (Pix, pagamentos, cartões, investimentos) vêm marcados **demo**:
estão ali porque o layout é de um banco completo, mas este app faz uma coisa só, e
clicá-los diz isso em vez de não fazer nada. Tudo o que não está marcado é real — o saldo,
o extrato, a busca, o gráfico de saídas por dia e o total do mês saem do que o servidor
devolveu.

Quando uma transferência fecha, as duas pontas recebem o comprovante por e-mail,
e quem se cadastra recebe uma mensagem de boas-vindas. **Isto está pronto mas
desligado** na demonstração pública: falta verificar o domínio no Resend, e as
contas de demonstração — as que você usa ao entrar como visitante — nunca
receberiam e-mail de qualquer forma, porque o endereço delas não existe. Para
ver funcionando é preciso rodar com uma chave própria.

O e-mail é o primeiro consumidor do evento de transferência que vive **fora** do
processo — a notificação por SSE some se o navegador estiver fechado, o e-mail
não —, e por isso tem fila própria no banco, com reentrega e um registro do que
saiu: dá para responder "esse e-mail foi enviado? quando? com qual identificador
no provedor?" consultando uma tabela. As contas de demonstração não recebem nada:
o endereço delas não existe, e o reset de hora em hora passa pelo mesmo caminho
de uma transferência comum — sem a trava, seriam dois e-mails inválidos por hora,
para sempre.

Há cadastro e login — e **duas senhas**: a de acesso prova quem você é, a transacional
autoriza mover dinheiro. Autenticar não é autorizar. Para ver sem se cadastrar, entre como
visitante em uma das duas contas de demonstração; os saldos delas voltam ao estado inicial
a cada hora, por lançamento compensatório, nunca apagando o razão.

<p align="center">
  <img src="docs/demo.png"
       alt="Tela da conta no celular: cartão escuro com o saldo em destaque, fileira de ações rápidas e as últimas movimentações"
       width="340">
</p>

<p align="center">
  <em>Saldo, ações rápidas e as últimas movimentações. Quando a transferência
  fecha, o saldo anda até o valor novo e a linha recém-chegada acende.</em>
</p>

<p align="center">
  <img src="docs/demo-web.png"
       alt="A mesma conta numa tela larga: coluna de navegação à esquerda, busca no topo, cartão do saldo e ações rápidas lado a lado, extrato e gráfico de saídas por dia embaixo"
       width="760">
</p>

<p align="center">
  <em>A mesma tela numa janela larga. Nada muda de ordem no HTML — só de
  posição —, então a navegação pelo teclado e por leitor de tela segue a
  mesma sequência do celular.</em>
</p>

---

## Subindo em três comandos

```bash
docker compose up -d
```

```bash
cd backend && ./mvnw spring-boot:run
```

```bash
cd frontend && npm install && npm run dev
```

App em http://localhost:5173 · API em http://localhost:8080 · duas contas já semeadas
(Gabriel com R$ 1.000 e Maria com R$ 500).

Para rodar a suíte (sobe um Postgres descartável via Testcontainers, precisa do Docker
ligado):

```bash
cd backend && ./mvnw test
```

---

## Subindo num servidor

A stack de produção é outra: `docker-compose.prod.yml` sobe backend, Postgres e um
container de borda com Caddy, que serve o build do React e faz proxy do `/api` com
HTTPS automático. Só a borda tem porta publicada.

Numa VPS que já tem um proxy na borda — o caso comum, e o da demo acima — o
`docker-compose.proxy.yml` tira as portas e o certificado deste Caddy e o coloca atrás do
que já existe. A armadilha desse arranjo é o SSE: um proxy que acumula a resposta mata o
stream sem erro e sem log. Está tratado na §8 do runbook.

Passo a passo, verificação e operação em **[docs/DEPLOY.md](docs/DEPLOY.md)**.

## O fluxo de uma transferência

```
  App                API                      Banco de dados
   │                  │                              │
   │ POST /transfers  │                              │
   │ Idempotency-Key ─┤                              │
   │                  │ 1. a chave já foi usada? ───▶│
   │                  │◀── sim ──▶ devolve a resposta antiga, FIM
   │                  │                              │
   │                  │ ══════ BEGIN TRANSACTION ════│
   │                  │ 2. trava conta MENOR id ────▶│  SELECT ... FOR UPDATE
   │                  │ 3. trava conta MAIOR id ────▶│  SELECT ... FOR UPDATE
   │                  │ 4. tem saldo?                │
   │                  │ 5. grava 2 lançamentos ─────▶│  −100 e +100
   │                  │ 6. atualiza os 2 saldos ────▶│
   │                  │ 7. grava a transferência ───▶│
   │                  │ 8. grava a chave usada ─────▶│
   │                  │ 9. grava o evento (outbox) ─▶│
   │                  │ ═══════ COMMIT ══════════════│
   │◀── 200 OK ───────│                              │
                      │ 10. publisher lê o outbox → SSE → toast no app
```

Os passos 2 a 9 são **tudo ou nada**.

---

## As quatro camadas de defesa

| Camada | Previne | Mecanismo | Onde está |
|---|---|---|---|
| **1. Atomicidade** | saiu de uma conta e não entrou na outra | `@Transactional` | [`TransferExecutor`](backend/src/main/java/com/estudo/banco/transfer/TransferExecutor.java) |
| **2. Concorrência** | *lost update* e deadlock | `SELECT FOR UPDATE` **ordenado por id** | mesmo arquivo, bloco comentado `ORDEM DE AQUISICAO DE LOCKS` |
| **3. Idempotência** | o dinheiro ir duas vezes | chave única gravada na mesma transação | [`IdempotencyService`](backend/src/main/java/com/estudo/banco/idempotency/IdempotencyService.java) |
| **4. Consistência eventual** | não chegar no outro banco | padrão **Outbox** | [`OutboxPublisher`](backend/src/main/java/com/estudo/banco/outbox/OutboxPublisher.java) |

O raciocínio completo de cada uma, com os diálogos de threads e as perguntas de entrevista
que respondem, está em **[docs/GUIA-FLUXO-BANCARIO.md](docs/GUIA-FLUXO-BANCARIO.md)**.

---

## Os testes que provam cada garantia

Não são testes de caminho feliz — eles atacam as garantias.

| Garantia | Teste |
|---|---|
| Dinheiro não se perde sob concorrência | `cemTransferenciasSimultaneasNaoPerdemDinheiro` — 100 threads, largada simultânea por `CountDownLatch`, saldo final exato |
| Lock ordenado elimina deadlock | `transferenciasCruzadasNaoCausamDeadlock` — 50 A→B e 50 B→A ao mesmo tempo |
| **A ordem é o que resolve, não sorte** | `ordemOpostaCausaDeadlockEOrdemCrescenteNao` — duas conexões e uma `CyclicBarrier` tornam o deadlock **determinístico** na ordem oposta, e impossível na crescente |
| Saldo concorda com o razão | `saldoBateComSomaDoRazao` e `saldoBateComORazaoDepoisDaTempestade` |
| Rollback não deixa rastro | `saldoInsuficienteNaoDeixaRastro`, `transferenciaQueFalhaNaoDeixaEvento` |
| Retry não cobra duas vezes | `mesmaChaveDuasVezesMoveDinheiroUmaVez`, `mesmaChaveEmParaleloMoveUmaVez` |
| Dinheiro não nasce nem morre | `somaTotalDoSistemaEhInvariante` |

Todos em [`backend/src/test/java/com/estudo/banco/transfer/`](backend/src/test/java/com/estudo/banco/transfer/)
e [`outbox/`](backend/src/test/java/com/estudo/banco/outbox/).

Há ainda a **evidência do deadlock acontecendo** quando a ordenação é removida de propósito:
[docs/evidencias/deadlock-sem-ordenacao.md](docs/evidencias/deadlock-sem-ordenacao.md).

---

## Decisões que valem explicar

- **Saldo é a soma do razão.** `entries` é a fonte da verdade (dois lançamentos por
  transferência, somando zero); `accounts.balance` é materialização para leitura, com teste
  de reconciliação entre os dois.
- **Dinheiro é `BigDecimal` / `NUMERIC(19,4)`.** Nunca `double`. Valor com 3 casas decimais é
  recusado, não arredondado em silêncio.
- **A transação vive em outro bean.** `TransferService` orquestra e **não** é
  `@Transactional`; `TransferExecutor` carrega a transação. O `@Transactional` do Spring é
  proxy — chamar um método da própria classe não o atravessa e a transação não abriria, em
  silêncio.
- **A idempotência mora na unique constraint**, não num `if`. Duas requisições concorrentes
  com a mesma chave colidem no commit; a perdedora tem tudo desfeito e devolve a resposta da
  vencedora.
- **Outbox dá at-least-once**, não exactly-once. O consumidor também precisa ser idempotente.
- **Log em JSON com `correlationId`** por requisição ([`CorrelationIdFilter`](backend/src/main/java/com/estudo/banco/observability/CorrelationIdFilter.java)),
  e texto legível no perfil `local`.
- **Constraints como última linha de defesa:** saldo não negativo, valor positivo, origem
  diferente de destino, e sinal do lançamento coerente com a direção.

## Limitações conhecidas

Levantadas por uma revisão independente do código e documentadas em
[docs/GUIA-FLUXO-BANCARIO.md](docs/GUIA-FLUXO-BANCARIO.md) (seção 10): a chave de idempotência
não compara a impressão digital da requisição; ela é gravada no fim da transação em vez de
reservada no início (um retry legítimo pode receber 422 para uma transferência que funcionou);
e estouro de `lock_timeout` vira 500 sem corpo de erro. Nenhuma perde dinheiro no razão.

A mesma revisão encontrou um bug que **foi corrigido**: auto-invocação de `@Transactional` no
agendador do outbox, que fazia o caminho agendado rodar sem transação e nunca drenar. Coberto
agora por `OutboxScheduledTest`, que exercita o agendador de verdade.

## O que ficaria diferente em produção

Particionamento do razão por período, snapshot periódico de saldo, limite de taxa por conta,
inscritos de SSE em Redis pub/sub (hoje são em memória, então só funcionam com uma
instância), e reconciliação diária batendo o razão contra o extrato do banco liquidante.

## Estrutura

```
backend/   Spring Boot 4 · Java 21 · Postgres · Flyway · Testcontainers
frontend/  React 18 · Vite · TypeScript · PWA (manifest + service worker à mão)
docs/      guia de estudo, design, plano e evidências
```
