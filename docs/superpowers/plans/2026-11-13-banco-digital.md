# Banco Digital — Plano de Implementação

> **Para executores agênticos:** SUB-SKILL OBRIGATÓRIA: use superpowers:subagent-driven-development (recomendado) ou superpowers:executing-plans para implementar tarefa a tarefa. Os passos usam checkbox (`- [ ]`) para rastreio.

**Objetivo:** Construir um motor de transferência bancária que demonstra, com testes que provam, as quatro camadas de defesa contra inconsistência de saldo — e um PWA React com visual Nubank sobre ele.

**Arquitetura:** Monólito Spring Boot sobre Postgres. Razão de dupla entrada (`entries`) com saldo materializado em `accounts.balance`, ambos escritos na mesma transação. Transferência bloqueia as duas contas com `SELECT ... FOR UPDATE` ordenado por id, grava a chave de idempotência e o evento de outbox na mesma transação. Frontend React consome a API e escuta notificações por SSE.

**Stack:** Java 21, Spring Boot 3.3, Spring Data JPA, Flyway, Postgres 16, Testcontainers, JUnit 5, logstash-logback-encoder, React 18 + Vite + TypeScript + Tailwind + vite-plugin-pwa.

**Spec:** `docs/superpowers/specs/2026-11-13-banco-digital-design.md`

## Restrições Globais

- Java 21, Spring Boot 3.3.x. Maven **não** está instalado — usar o Maven Wrapper (`./mvnw`).
- Dinheiro é `BigDecimal` em Java e `NUMERIC(19,4)` no Postgres. **Nunca** `double` ou `float`, em lugar nenhum.
- Entrada monetária aceita no máximo 2 casas decimais; mais que isso é erro de validação.
- Toda escrita de dinheiro acontece dentro de um único `@Transactional`.
- Nenhum `UPDATE` em `accounts.balance` sem o `SELECT ... FOR UPDATE` correspondente na mesma transação.
- Contas sempre bloqueadas em ordem crescente de `id`, sem exceção.
- Paleta Nubank: roxo primário `#820AD1`, fundo `#FFFFFF`, texto `#1C1C1C`, cinza secundário `#8F8F8F`.
- Mensagens de erro e textos de UI em português.

## Foco de Revisão

Casos que a spec implica mas que nenhuma tarefa exercitaria por padrão. Cada linha teve seu teste atribuído à tarefa dona do código.

1. **Valor zero ou negativo** — `POST /transfers` com `amount <= 0` deve retornar 400 e não criar lançamento nenhum. (Tarefa 4)
2. **Origem igual ao destino** — transferir de uma conta para ela mesma deve retornar 400. Sem isso o lock ordenado trava na mesma linha e a reconciliação passa a mentir. (Tarefa 4)
3. **Conta inexistente** — id que não existe deve retornar 404 com corpo de erro, nunca 500 ou `NullPointerException`. (Tarefa 4)
4. **Precisão monetária** — `10.999` deve ser rejeitado como entrada inválida, não arredondado em silêncio. (Tarefa 4)
5. **`Idempotency-Key` ausente ou em branco** — deve retornar 400 explicando o header obrigatório, não aceitar a transferência sem proteção. (Tarefa 6)

---

## Estrutura de Arquivos

**Backend** (`backend/`, pacote `com.estudo.banco`):

| Arquivo | Responsabilidade |
|---|---|
| `account/Account.java` | Entidade conta: id, titular, `balance`, `version` |
| `account/AccountRepository.java` | Busca comum e busca com lock pessimista |
| `account/AccountController.java` | `GET /api/accounts`, `GET /api/accounts/{id}`, `GET /api/accounts/{id}/statement` |
| `ledger/Entry.java` | Lançamento do razão: conta, transferência, valor com sinal, direção |
| `ledger/EntryRepository.java` | Extrato por conta, soma por conta |
| `transfer/Transfer.java` | Registro da transferência: origem, destino, valor, status |
| `transfer/TransferService.java` | **O coração.** Orquestra lock ordenado, validação, dupla entrada, outbox |
| `transfer/TransferController.java` | `POST /api/transfers` |
| `idempotency/IdempotencyService.java` | Reserva a chave e devolve resposta gravada em replay |
| `outbox/OutboxEvent.java` + `OutboxPublisher.java` | Evento na mesma transação; publicação assíncrona |
| `notification/NotificationController.java` | `GET /api/notifications/stream` (SSE) |
| `observability/CorrelationIdFilter.java` | Põe `correlationId` no MDC por requisição |
| `error/ApiExceptionHandler.java` | `@RestControllerAdvice`: domínio → status HTTP + corpo de erro |

**Frontend** (`frontend/src/`): `api/client.ts`, `hooks/useNotifications.ts`, `components/BalanceCard.tsx`, `components/StatementList.tsx`, `components/TransferSheet.tsx`, `components/Toast.tsx`, `pages/Home.tsx`.

**Docs:** `docs/GUIA-FLUXO-BANCARIO.md` — guia de estudo, entregue primeiro.

---

### Tarefa 1: Guia de estudo do fluxo bancário

Entregue **antes** de qualquer código, para que o Gabriel leia enquanto o resto é construído.

**Arquivos:**
- Criar: `docs/GUIA-FLUXO-BANCARIO.md`

**Interfaces:**
- Consome: nada.
- Produz: nada em código. É o roteiro de conversa da entrevista.

- [ ] **Passo 1: Escrever o guia** cobrindo, em ordem: o que é razão de dupla entrada e por que saldo não é um campo que se edita; as quatro camadas (atomicidade, concorrência, idempotência, consistência eventual) com o sintoma que cada uma previne; o que é deadlock, por que ordenar os locks resolve, e o diálogo de duas threads que o provoca; lock pessimista versus otimista com o critério de escolha; por que idempotência não sai de lock; outbox e por que transferência interbancária não cabe numa transação; por que `double` não serve para dinheiro. Cada seção termina com a pergunta de entrevista que ela responde.

- [ ] **Passo 2: Commit**

```bash
git add docs/GUIA-FLUXO-BANCARIO.md
git commit -m "docs: guia do fluxo bancário e das quatro camadas de consistência"
```

---

### Tarefa 2: Esqueleto do backend que sobe

**Arquivos:**
- Criar: `backend/pom.xml`, `backend/mvnw`, `backend/.mvn/wrapper/maven-wrapper.properties`
- Criar: `backend/src/main/java/com/estudo/banco/BancoApplication.java`
- Criar: `backend/src/main/resources/application.yml`
- Criar: `backend/src/main/resources/db/migration/V1__schema.sql`
- Criar: `docker-compose.yml` (Postgres 16 na porta 5432, base `banco`)
- Criar: `backend/src/test/java/com/estudo/banco/support/PostgresTestBase.java`
- Teste: `backend/src/test/java/com/estudo/banco/SchemaMigrationTest.java`

**Interfaces:**
- Produz: `PostgresTestBase`, classe base anotada com `@SpringBootTest` e `@Testcontainers` que sobe um `PostgreSQLContainer` estático reaproveitado por toda a suíte. Todas as tarefas seguintes estendem essa classe.

`V1__schema.sql` cria: `accounts(id BIGSERIAL, holder_name TEXT, balance NUMERIC(19,4) NOT NULL DEFAULT 0, version BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ)`; `transfers(id UUID PK, source_account_id, target_account_id, amount NUMERIC(19,4), status TEXT, created_at)`; `entries(id BIGSERIAL, account_id, transfer_id, amount NUMERIC(19,4), direction TEXT, created_at)`; `idempotency_keys(key TEXT PK, transfer_id UUID, response_body JSONB, created_at)`; `outbox_events(id BIGSERIAL, event_type TEXT, payload JSONB, published BOOLEAN DEFAULT FALSE, created_at)`. Constraint `accounts_balance_non_negative CHECK (balance >= 0)`.

- [ ] **Passo 1: Escrever o teste que falha**

```java
class SchemaMigrationTest extends PostgresTestBase {
    @Test void migracaoCriaTodasAsTabelas() {
        var tabelas = jdbc.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema='public'",
            String.class);
        assertThat(tabelas).contains(
            "accounts", "transfers", "entries", "idempotency_keys", "outbox_events");
    }

    @Test void saldoNegativoEhRejeitadoPeloBanco() {
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO accounts (holder_name, balance) VALUES ('x', -1)"))
            .hasMessageContaining("accounts_balance_non_negative");
    }
}
```

- [ ] **Passo 2: Rodar e ver falhar.** `cd backend && ./mvnw test -Dtest=SchemaMigrationTest` — espera-se falha de compilação ou contexto.

- [ ] **Passo 3: Implementar** `pom.xml`, `BancoApplication`, `application.yml`, `V1__schema.sql`, `docker-compose.yml` e `PostgresTestBase`.

- [ ] **Passo 4: Rodar e ver passar.** `cd backend && ./mvnw test -Dtest=SchemaMigrationTest` — espera-se PASS.

- [ ] **Passo 5: Commit**

```bash
git add backend docker-compose.yml && git commit -m "feat: esqueleto Spring Boot com schema do razão e Postgres via Testcontainers"
```

---

### Tarefa 3: Contas e extrato

**Arquivos:**
- Criar: `account/Account.java`, `account/AccountRepository.java`, `account/AccountController.java`, `account/dto/AccountResponse.java`
- Criar: `ledger/Entry.java`, `ledger/EntryRepository.java`, `ledger/Direction.java`
- Criar: `backend/src/main/resources/db/migration/V2__seed.sql` (duas contas: "Gabriel" com 1000.00, "Maria" com 500.00)
- Teste: `backend/src/test/java/com/estudo/banco/account/AccountControllerTest.java`

**Interfaces:**
- Consome: `PostgresTestBase` (Tarefa 2).
- Produz:
  - `Account` com `getId(): Long`, `getBalance(): BigDecimal`, `getVersion(): Long`, `debit(BigDecimal)`, `credit(BigDecimal)` — ambos lançam `SaldoInsuficienteException` quando aplicável.
  - `Direction` — enum `DEBIT`, `CREDIT`.
  - `AccountRepository.findByIdForUpdate(Long id): Optional<Account>` anotado com `@Lock(LockModeType.PESSIMISTIC_WRITE)`.
  - `EntryRepository.findByAccountIdOrderByCreatedAtDesc(Long): List<Entry>` e `sumAmountByAccountId(Long): BigDecimal`.
  - `AccountResponse(Long id, String holderName, BigDecimal balance)`.

- [ ] **Passo 1: Escrever os testes que falham**

```java
@Test void retornaContaComSaldo() // GET /api/accounts/1 → 200, balance 1000.00
@Test void contaInexistenteRetorna404() // GET /api/accounts/9999 → 404  [Foco de Revisão #3]
@Test void extratoVazioRetornaListaVazia() // GET /api/accounts/1/statement → 200, []
```

- [ ] **Passo 2: Rodar e ver falhar.** `./mvnw test -Dtest=AccountControllerTest`

- [ ] **Passo 3: Implementar** as entidades, repositórios, controller, DTO e `V2__seed.sql`.

- [ ] **Passo 4: Rodar e ver passar.** `./mvnw test -Dtest=AccountControllerTest`

- [ ] **Passo 5: Commit**

```bash
git add backend && git commit -m "feat: contas, razão e endpoint de extrato"
```

---

### Tarefa 4: Transferência atômica com dupla entrada

O núcleo. Aqui vivem três dos cinco itens do Foco de Revisão.

**Arquivos:**
- Criar: `transfer/Transfer.java`, `transfer/TransferStatus.java`, `transfer/TransferRepository.java`, `transfer/TransferService.java`, `transfer/TransferController.java`
- Criar: `transfer/dto/TransferRequest.java`, `transfer/dto/TransferResponse.java`
- Criar: `error/ApiExceptionHandler.java`, `error/ApiError.java`, `error/SaldoInsuficienteException.java`, `error/ContaNaoEncontradaException.java`, `error/TransferenciaInvalidaException.java`
- Teste: `backend/src/test/java/com/estudo/banco/transfer/TransferServiceTest.java`

**Interfaces:**
- Consome: `AccountRepository.findByIdForUpdate`, `EntryRepository`, `Account.debit/credit` (Tarefa 3).
- Produz:
  - `TransferService.execute(TransferCommand cmd): TransferResult`, anotado `@Transactional`.
  - `TransferCommand(Long sourceAccountId, Long targetAccountId, BigDecimal amount, String idempotencyKey)`.
  - `TransferResult(UUID transferId, TransferStatus status, BigDecimal sourceBalance, BigDecimal targetBalance)`.
  - `TransferStatus` — enum `COMPLETED`, `FAILED`.

Ordem obrigatória dentro de `execute`: ordenar os dois ids, `findByIdForUpdate` no menor e depois no maior, validar, debitar e creditar, inserir dois `Entry` (`DEBIT` negativo, `CREDIT` positivo), salvar o `Transfer`.

- [ ] **Passo 1: Escrever os testes que falham**

```java
@Test void transfereMovimentandoOsDoisSaldos()
    // 1000→Maria 200: origem 800.00, destino 700.00

@Test void geraDoisLancamentosQueSomamZero()
    // entries da transferência: 2 linhas, soma exatamente 0

@Test void saldoBateComSomaDoRazao()           // reconciliação
    // para cada conta: balance == SUM(entries.amount) + saldo inicial

@Test void saldoInsuficienteNaoDeixaRastro()   // atomicidade
    // transferir 99999 → lança SaldoInsuficienteException
    // E saldos inalterados E nenhum entry criado

@Test void valorZeroOuNegativoEhRejeitado()    // [Foco de Revisão #1]
    // amount 0 e amount -5 → TransferenciaInvalidaException, nenhum entry

@Test void transferenciaParaMesmaContaEhRejeitada()  // [Foco de Revisão #2]
    // source == target → TransferenciaInvalidaException

@Test void contaInexistenteLanca404()          // [Foco de Revisão #3]
    // → ContaNaoEncontradaException

@Test void valorComTresCasasDecimaisEhRejeitado()    // [Foco de Revisão #4]
    // amount 10.999 → TransferenciaInvalidaException, sem arredondar
```

- [ ] **Passo 2: Rodar e ver falhar.** `./mvnw test -Dtest=TransferServiceTest`

- [ ] **Passo 3: Implementar** `TransferService.execute` na ordem obrigatória acima, mais as exceções de domínio e o `ApiExceptionHandler` mapeando `ContaNaoEncontrada`→404, `SaldoInsuficiente`→422, `TransferenciaInvalida`→400.

- [ ] **Passo 4: Rodar e ver passar.** `./mvnw test -Dtest=TransferServiceTest`

- [ ] **Passo 5: Commit**

```bash
git add backend && git commit -m "feat: transferência atômica com razão de dupla entrada e validações de domínio"
```

---

### Tarefa 5: Concorrência — lock ordenado contra lost update e deadlock

A tarefa que sustenta a entrevista.

**Arquivos:**
- Modificar: `transfer/TransferService.java` (configurar `lock_timeout`)
- Modificar: `backend/src/main/resources/application.yml` (`spring.jpa.properties.javax.persistence.lock.timeout: 3000`)
- Teste: `backend/src/test/java/com/estudo/banco/transfer/TransferConcurrencyTest.java`

**Interfaces:**
- Consome: `TransferService.execute` (Tarefa 4).
- Produz: nada novo em assinatura. A garantia é comportamental.

- [ ] **Passo 1: Escrever os testes que falham**

```java
@Test void cemTransferenciasSimultaneasNaoPerdemDinheiro() {
    // conta A com 1000.00; 100 threads transferindo 1.00 de A para B
    // ExecutorService(16) + CountDownLatch para largada simultânea
    // assert: A == 900.00 exatamente, B == 1100.00 exatamente
    // assert: SUM(todos os entries) == 0
}

@Test void transferenciasCruzadasNaoCausamDeadlock() {
    // 50 threads A→B e 50 threads B→A, todas ao mesmo tempo
    // assert: as 100 completam sem DeadlockLoserDataAccessException
    // assert: A + B == soma inicial (conservação)
    // assert: nenhuma thread excedeu o lock_timeout
}

@Test void somaTotalDoSistemaEhInvariante() {
    // após toda a bagunça: SUM(accounts.balance) == total inicial
}
```

- [ ] **Passo 2: Rodar e ver falhar.** `./mvnw test -Dtest=TransferConcurrencyTest` — se a ordenação de locks ainda não estiver correta, o teste cruzado falha com deadlock.

- [ ] **Passo 3: Garantir a ordenação** em `TransferService.execute` e configurar o `lock_timeout`. Documentar em comentário no método por que a ordem é por id crescente.

- [ ] **Passo 4: Rodar e ver passar.** Rodar 3 vezes seguidas para descartar flutuação: `./mvnw test -Dtest=TransferConcurrencyTest`

- [ ] **Passo 5: Commit**

```bash
git add backend && git commit -m "test: provas de concorrência — lost update e deadlock sob lock ordenado"
```

---

### Tarefa 6: Idempotência

**Arquivos:**
- Criar: `idempotency/IdempotencyKey.java`, `idempotency/IdempotencyKeyRepository.java`, `idempotency/IdempotencyService.java`
- Modificar: `transfer/TransferController.java` (ler o header `Idempotency-Key`)
- Modificar: `transfer/TransferService.java` (gravar a chave na mesma transação)
- Teste: `backend/src/test/java/com/estudo/banco/transfer/IdempotencyTest.java`

**Interfaces:**
- Consome: `TransferService.execute` (Tarefa 4).
- Produz: `IdempotencyService.findExisting(String key): Optional<TransferResult>`.

- [ ] **Passo 1: Escrever os testes que falham**

```java
@Test void mesmaChaveDuasVezesMoveDinheiroUmaVez()
    // mesmo Idempotency-Key, 2 chamadas → 200 nas duas,
    // mesmo transferId, saldo movimentado só uma vez

@Test void chavesDiferentesMovemDuasVezes()
    // chaves distintas → dois transferIds, saldo movido 2x

@Test void mesmaChaveEmParaleloMoveUmaVez()
    // 10 threads, mesma chave, largada simultânea
    // → exatamente 1 transferência efetivada

@Test void headerAusenteOuEmBrancoRetorna400()   // [Foco de Revisão #5]
    // sem header e com header "" → 400 citando Idempotency-Key
```

- [ ] **Passo 2: Rodar e ver falhar.** `./mvnw test -Dtest=IdempotencyTest`

- [ ] **Passo 3: Implementar.** A proteção paralela vem da unique constraint em `idempotency_keys.key`: ao capturar `DataIntegrityViolationException`, reler a chave e devolver a resposta gravada.

- [ ] **Passo 4: Rodar e ver passar.** `./mvnw test -Dtest=IdempotencyTest`

- [ ] **Passo 5: Commit**

```bash
git add backend && git commit -m "feat: idempotência por chave com replay da resposta original"
```

---

### Tarefa 7: Outbox e notificação por SSE

**Arquivos:**
- Criar: `outbox/OutboxEvent.java`, `outbox/OutboxEventRepository.java`, `outbox/OutboxPublisher.java`
- Criar: `notification/NotificationController.java`, `notification/NotificationService.java`
- Modificar: `transfer/TransferService.java` (gravar o evento na mesma transação)
- Teste: `backend/src/test/java/com/estudo/banco/outbox/OutboxTest.java`

**Interfaces:**
- Consome: `TransferService.execute` (Tarefa 4).
- Produz:
  - `NotificationService.subscribe(Long accountId): SseEmitter`.
  - `OutboxPublisher.publishPending(): int` — `@Scheduled(fixedDelay = 1000)`, devolve quantos publicou.

- [ ] **Passo 1: Escrever os testes que falham**

```java
@Test void transferenciaGravaEventoNaMesmaTransacao()
    // após execute: 1 outbox_event TRANSFER_COMPLETED, published=false

@Test void transferenciaQueFalhaNaoDeixaEvento()
    // saldo insuficiente → nenhum outbox_event (rollback leva o evento junto)

@Test void publisherMarcaComoPublicado()
    // publishPending() → retorna 1, evento vira published=true

@Test void publisherEhIdempotente()
    // publishPending() de novo → retorna 0
```

- [ ] **Passo 2: Rodar e ver falhar.** `./mvnw test -Dtest=OutboxTest`

- [ ] **Passo 3: Implementar** as entidades, o publisher agendado e o endpoint SSE `GET /api/notifications/stream?accountId=`.

- [ ] **Passo 4: Rodar e ver passar.** `./mvnw test -Dtest=OutboxTest`

- [ ] **Passo 5: Commit**

```bash
git add backend && git commit -m "feat: outbox transacional e notificações em tempo real por SSE"
```

---

### Tarefa 8: Observabilidade

**Arquivos:**
- Criar: `observability/CorrelationIdFilter.java`
- Criar: `backend/src/main/resources/logback-spring.xml`
- Modificar: `backend/pom.xml` (dependência `logstash-logback-encoder`)
- Modificar: `transfer/TransferService.java` (log de auditoria por transferência)
- Teste: `backend/src/test/java/com/estudo/banco/observability/CorrelationIdFilterTest.java`

**Interfaces:**
- Produz: `CorrelationIdFilter`, `OncePerRequestFilter` que lê o header `X-Correlation-Id` ou gera um UUID, põe em `MDC` sob a chave `correlationId`, devolve no header da resposta e limpa o MDC no `finally`.

- [ ] **Passo 1: Escrever os testes que falham**

```java
@Test void geraCorrelationIdQuandoNaoEnviado()  // resposta traz X-Correlation-Id não vazio
@Test void preservaCorrelationIdEnviado()        // eco do valor recebido
@Test void limpaMdcDepoisDaRequisicao()          // MDC.get("correlationId") == null ao final
```

- [ ] **Passo 2: Rodar e ver falhar.** `./mvnw test -Dtest=CorrelationIdFilterTest`

- [ ] **Passo 3: Implementar** o filtro, o `logback-spring.xml` com `LogstashEncoder` em JSON no perfil padrão e console legível no perfil `local`, e o log de auditoria em `TransferService`.

- [ ] **Passo 4: Rodar e ver passar.** `./mvnw test -Dtest=CorrelationIdFilterTest`

- [ ] **Passo 5: Rodar a suíte inteira.** `./mvnw test` — tudo verde.

- [ ] **Passo 6: Commit**

```bash
git add backend && git commit -m "feat: logs estruturados em JSON com correlationId por requisição"
```

---

### Tarefa 9: PWA React — saldo e extrato com visual Nubank

**Arquivos:**
- Criar: `frontend/package.json`, `vite.config.ts`, `tailwind.config.js`, `index.html`, `src/main.tsx`, `src/index.css`
- Criar: `frontend/src/api/client.ts`, `frontend/src/pages/Home.tsx`
- Criar: `frontend/src/components/BalanceCard.tsx`, `frontend/src/components/StatementList.tsx`
- Criar: `frontend/public/manifest.webmanifest` e ícones 192/512
- Teste: verificação visual via browser (sem suíte automatizada — ver nota abaixo)

**Interfaces:**
- Consome: `GET /api/accounts/{id}`, `GET /api/accounts/{id}/statement`.
- Produz: `fetchAccount(id): Promise<Account>`, `fetchStatement(id): Promise<Entry[]>`, `postTransfer(req, idempotencyKey): Promise<TransferResponse>`.

Nota: esta tarefa e a 10 são verificadas visualmente no navegador, não por suíte automatizada. O prazo de 2 horas não comporta montar Vitest + Testing Library, e o risco de um botão desalinhado é incomparavelmente menor que o de um saldo errado. Decisão consciente, registrada aqui para que não pareça esquecimento.

- [ ] **Passo 1: Scaffold** do Vite com React + TypeScript, Tailwind configurado com as cores da spec, `vite-plugin-pwa` com `registerType: 'autoUpdate'`, e proxy de `/api` para `localhost:8080`.

- [ ] **Passo 2: Implementar** `BalanceCard` (cartão roxo `#820AD1`, saudação, saldo grande, olho para ocultar), `StatementList` (lançamentos agrupados por dia, seta verde para entrada e vermelha para saída, valor à direita) e `Home` compondo os dois.

- [ ] **Passo 3: Verificar no navegador.** Subir `docker compose up -d`, `./mvnw spring-boot:run` e `npm run dev`; abrir, conferir que saldo e extrato carregam e que o layout não quebra em 375px de largura.

- [ ] **Passo 4: Commit**

```bash
git add frontend && git commit -m "feat: PWA React com cartão de saldo e extrato no visual Nubank"
```

---

### Tarefa 10: Transferência na UI e notificação ao vivo

**Arquivos:**
- Criar: `frontend/src/components/TransferSheet.tsx`, `frontend/src/components/Toast.tsx`
- Criar: `frontend/src/hooks/useNotifications.ts`
- Modificar: `frontend/src/pages/Home.tsx`

**Interfaces:**
- Consome: `postTransfer` (Tarefa 9), `GET /api/notifications/stream` (Tarefa 7).
- Produz: `useNotifications(accountId): Notification[]` — abre um `EventSource` e reconecta ao perder a conexão.

- [ ] **Passo 1: Implementar** `TransferSheet` como bottom sheet deslizante com campo de valor, seleção de destino e botão de confirmar; gerar o `Idempotency-Key` com `crypto.randomUUID()` **uma vez por tentativa do usuário**, não por clique, para que o botão de retentar reaproveite a mesma chave.

- [ ] **Passo 2: Implementar** `useNotifications` e `Toast` para a notificação de dinheiro recebido.

- [ ] **Passo 3: Verificar fim a fim no navegador.** Transferir entre as duas contas semeadas; conferir que o saldo atualiza, o lançamento aparece no extrato e o toast de notificação dispara.

- [ ] **Passo 4: Escrever o `README.md` da raiz** com: como subir tudo em três comandos, o diagrama do fluxo de uma transferência, e um índice apontando para o guia de estudo e para os testes que provam cada garantia.

- [ ] **Passo 5: Commit**

```bash
git add . && git commit -m "feat: transferência na UI com idempotência e notificação ao vivo"
```
