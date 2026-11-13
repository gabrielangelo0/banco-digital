# Banco Digital — estudo de consistência em transferências

Aplicação bancária de estudo: **Spring Boot 4 + Postgres** no backend, **React PWA** no
frontend. O objetivo não é ter muitas funcionalidades — é demonstrar, com testes que
provam, como dinheiro se move entre contas sem se perder, duplicar ou travar.

<p align="center">
  <em>Saldo → ação → transferência → extrato → notificação em tempo real.</em>
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
