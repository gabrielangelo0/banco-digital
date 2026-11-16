# Identidade e autenticação — plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mover a identidade do cliente para o servidor: cadastro, login, sessão revogável e senha transacional, com a transferência passando a ser "da minha conta para a de outro".

**Architecture:** Spring Security só para o encoder de senha e o filtro; a sessão é token opaco em cookie `HttpOnly`, guardado no Redis com TTL, porque revogar precisa ser possível. A conta ganha dono (`owner_id`) e uma senha transacional com hash próprio — autenticar não é autorizar. O destino de uma transferência passa a ser um número de conta digitado, sem diretório e sem endpoint de busca.

**Tech Stack:** Java 21, Spring Boot 4.1.2, Spring Security (encoder + filtro), Argon2id via BouncyCastle, Spring Data Redis, Postgres 16, Flyway, Testcontainers (Postgres + Redis), React 18 + TypeScript + Vite.

**Spec:** `docs/superpowers/specs/2026-11-16-autenticacao-e-notificacoes-design.md`

**Fora deste plano:** notificação entre instâncias, Web Push e `SKIP LOCKED` no drenador do outbox. São o segundo subsistema do mesmo design e ganham plano próprio depois que este fechar.

## Global Constraints

- Nenhum segredo versionado. O repositório é público. Credenciais de demonstração vêm de variável de ambiente.
- Cada garantia nova tem teste. A suíte atual (33 testes) continua verde.
- Nenhum container novo publica porta no host; todo container novo entra com teto de memória.
- Hash de senha: **Argon2id**. Nunca armazenar nem logar senha em claro.
- Resposta de login indistinguível entre "usuário não existe" e "senha errada", inclusive no tempo.
- Conta de demonstração só transfere para outra conta de demonstração.
- Saldo inicial de cadastro novo: **R$ 1.000,00**. Contas de demonstração: **1000,00** e **500,00**.
- Mensagens de erro em português, no formato de `ApiError` que já existe (`code` + `message`).

## Review Focus

Classes de entrada que o design implica e que nenhum caminho feliz exercita. Cada uma tem teste na tarefa indicada.

1. **SSE segue aberto depois do logout ou da expiração da sessão** — o stream foi aberto com sessão válida; revogar precisa encerrar o emitter, não só impedir novas conexões. (Tarefa 4)
2. **Transferência para número de conta inexistente** — precisa de erro legível, não 500. É a crítica mais votada do app do benchmark. (Tarefa 5)
3. **E-mail diferindo só por caixa alta cria segundo cadastro** — `Joao@x.com` e `joao@x.com` são a mesma pessoa. (Tarefa 2)
4. **Cookie `Secure` quebra o login em desenvolvimento HTTP** — produção é HTTPS, `npm run dev` não é. (Tarefa 3)
5. **Conta de demonstração transferindo para conta registrada** — o dinheiro escapa do par e o reset deixa de fechar. (Tarefa 5)

---

### Task 1: Esquema de usuário e dono da conta

**Files:**
- Create: `backend/src/main/resources/db/migration/V3__users.sql`
- Create: `backend/src/main/java/com/estudo/banco/user/User.java`
- Create: `backend/src/main/java/com/estudo/banco/user/UserRepository.java`
- Modify: `backend/src/main/java/com/estudo/banco/account/Account.java`
- Test: `backend/src/test/java/com/estudo/banco/user/UserSchemaTest.java`

**Interfaces:**
- Consumes: `PostgresTestBase` (já existe).
- Produces: `User` com `id`, `email`, `passwordHash`, `transactionPasswordHash`, `createdAt`; `UserRepository.findByEmail(String): Optional<User>`; `Account.getOwnerId(): Long`.

- [ ] **Step 1: Write the failing test**

```java
@Test
void migracaoCriaUsuarioELigaContaAoDono() {
    assertThat(jdbc.queryForObject(
        "select count(*) from information_schema.columns "
        + "where table_name='accounts' and column_name='owner_id'", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "select is_nullable from information_schema.columns "
        + "where table_name='accounts' and column_name='owner_id'", String.class)).isEqualTo("YES");
    assertThat(jdbc.queryForObject(
        "select count(*) from accounts where id in (1,2)", Integer.class)).isEqualTo(2);
}

@Test
void emailEUnicoIgnorandoCaixa() {
    jdbc.update("insert into users (email, password_hash, transaction_password_hash) values (?,?,?)",
        "joao@exemplo.com", "x", "y");
    assertThatThrownBy(() -> jdbc.update(
        "insert into users (email, password_hash, transaction_password_hash) values (?,?,?)",
        "JOAO@exemplo.com", "x", "y")).isInstanceOf(DataAccessException.class);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=UserSchemaTest`
Expected: FAIL — tabela `users` não existe.

- [ ] **Step 3: Escrever `V3__users.sql`**

`users`: `id bigserial primary key`, `email text not null`, `password_hash text not null`, `transaction_password_hash text not null`, `created_at timestamptz not null default now()`. Unicidade por `create unique index on users (lower(email))` — índice funcional, não `unique` na coluna, porque a comparação é sem caixa. `accounts` ganha `owner_id bigint references users(id)`, **anulável**: as contas 1 e 2 já existem e ganham dono em tempo de execução (Tarefa 7), não na migração, para que nenhuma credencial entre no repositório.

- [ ] **Step 4: Criar `User` e `UserRepository`, e `ownerId` em `Account`**

`User` como `@Entity` no padrão de `Account`. `Account` ganha `@Column(name = "owner_id") private Long ownerId;` com getter.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest=UserSchemaTest,SchemaMigrationTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/migration/V3__users.sql backend/src/main/java/com/estudo/banco/user backend/src/main/java/com/estudo/banco/account/Account.java backend/src/test/java/com/estudo/banco/user/UserSchemaTest.java
git commit -m "feat: esquema de usuario e dono da conta"
```

---

### Task 2: Cadastro com Argon2id

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/src/main/java/com/estudo/banco/auth/HashingConfig.java`
- Create: `backend/src/main/java/com/estudo/banco/auth/RegistrationService.java`
- Create: `backend/src/main/java/com/estudo/banco/auth/AuthController.java`
- Create: `backend/src/main/java/com/estudo/banco/auth/dto/RegisterRequest.java`
- Create: `backend/src/main/java/com/estudo/banco/error/CadastroInvalidoException.java`
- Modify: `backend/src/main/java/com/estudo/banco/error/ApiExceptionHandler.java`
- Test: `backend/src/test/java/com/estudo/banco/auth/RegistrationTest.java`

**Interfaces:**
- Consumes: `User`, `UserRepository`, `AccountRepository`.
- Produces: `PasswordEncoder` (bean, Argon2id); `RegistrationService.register(String email, String password, String transactionPassword): Long` devolvendo o id da conta criada; `POST /api/auth/register`.

- [ ] **Step 1: Write the failing test**

```java
@Test
void cadastroCriaUsuarioEContaComSaldoInicial() {
    Long contaId = registration.register("ana@exemplo.com", "senha-forte-1", "123456");
    Account conta = contas.findById(contaId).orElseThrow();
    assertThat(conta.getBalance()).isEqualByComparingTo("1000.0000");
    assertThat(conta.getOwnerId()).isNotNull();
}

@Test
void senhaNuncaEGuardadaEmClaro() {
    registration.register("bia@exemplo.com", "senha-forte-1", "123456");
    User u = usuarios.findByEmail("bia@exemplo.com").orElseThrow();
    assertThat(u.getPasswordHash()).doesNotContain("senha-forte-1").startsWith("$argon2id$");
    assertThat(u.getTransactionPasswordHash()).doesNotContain("123456");
}

@Test
void emailRepetidoIgnorandoCaixaEhRecusado() {
    registration.register("caio@exemplo.com", "senha-forte-1", "123456");
    assertThatThrownBy(() -> registration.register("CAIO@exemplo.com", "outra-senha-1", "654321"))
        .isInstanceOf(CadastroInvalidoException.class);
}

@Test
void senhaTransacionalIgualAhDeLoginEhRecusada() {
    assertThatThrownBy(() -> registration.register("davi@exemplo.com", "123456", "123456"))
        .isInstanceOf(CadastroInvalidoException.class);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=RegistrationTest`
Expected: FAIL — classes não existem.

- [ ] **Step 3: Adicionar dependências e implementar**

`pom.xml`: `spring-boot-starter-security` e `bouncycastle` (`bcprov-jdk18on`), que o `Argon2PasswordEncoder` exige. `HashingConfig` expõe `Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()` como `PasswordEncoder`. `RegistrationService.register` normaliza o e-mail para minúsculas, recusa e-mail já existente e senha transacional igual à de login, cria `User` e `Account` com `1000.0000` na mesma transação.

- [ ] **Step 4: Expor `POST /api/auth/register` e mapear o erro**

`RegisterRequest(@Email String email, @Size(min=8) String password, @Size(min=6) String transactionPassword)`. `ApiExceptionHandler` mapeia `CadastroInvalidoException` para **409** com `code = "CADASTRO_INVALIDO"` e mensagem genérica, sem distinguir qual regra falhou.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest=RegistrationTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/pom.xml backend/src/main/java/com/estudo/banco/auth backend/src/main/java/com/estudo/banco/error backend/src/test/java/com/estudo/banco/auth/RegistrationTest.java
git commit -m "feat: cadastro com Argon2id e e-mail sem distincao de caixa"
```

---

### Task 3: Sessão no Redis e login

**Files:**
- Modify: `backend/pom.xml`, `backend/src/main/resources/application.yml`
- Modify: `docker-compose.yml`
- Create: `backend/src/main/java/com/estudo/banco/auth/SessionStore.java`
- Create: `backend/src/main/java/com/estudo/banco/auth/SessionCookie.java`
- Modify: `backend/src/main/java/com/estudo/banco/auth/AuthController.java`
- Test: `backend/src/test/java/com/estudo/banco/auth/SessionTest.java`
- Test: `backend/src/test/java/com/estudo/banco/support/RedisTestBase.java`

**Interfaces:**
- Consumes: `PasswordEncoder`, `UserRepository`.
- Produces: `SessionStore.create(Long userId): String` (token opaco), `.resolve(String token): Optional<Long>`, `.revoke(String token): void`, `.revokeAll(Long userId): void`; `SessionCookie.build(String token, boolean secure): ResponseCookie`; `POST /api/auth/login`, `POST /api/auth/logout`, `DELETE /api/auth/sessions`.

- [ ] **Step 1: Write the failing test**

```java
@Test
void sessaoResolveEDepoisDeRevogarNaoResolve() {
    String token = sessoes.create(7L);
    assertThat(sessoes.resolve(token)).contains(7L);
    sessoes.revoke(token);
    assertThat(sessoes.resolve(token)).isEmpty();
}

@Test
void sairDeTodosOsDispositivosDerrubaTodasAsSessoes() {
    String a = sessoes.create(7L);
    String b = sessoes.create(7L);
    sessoes.revokeAll(7L);
    assertThat(sessoes.resolve(a)).isEmpty();
    assertThat(sessoes.resolve(b)).isEmpty();
}

@Test
void loginComSenhaErradaEComUsuarioInexistenteRespondemIgual() {
    registration.register("edu@exemplo.com", "senha-forte-1", "123456");
    ResponseEntity<ApiError> errada = login("edu@exemplo.com", "errada");
    ResponseEntity<ApiError> inexistente = login("ninguem@exemplo.com", "errada");
    assertThat(errada.getStatusCode()).isEqualTo(inexistente.getStatusCode());
    assertThat(errada.getBody().code()).isEqualTo(inexistente.getBody().code());
    assertThat(errada.getBody().message()).isEqualTo(inexistente.getBody().message());
}

@Test
void cookieNaoEhSecureQuandoARequisicaoNaoEhHttps() {
    ResponseCookie c = SessionCookie.build("t", false);
    assertThat(c.isSecure()).isFalse();
    assertThat(c.isHttpOnly()).isTrue();
    assertThat(SessionCookie.build("t", true).isSecure()).isTrue();
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=SessionTest`
Expected: FAIL — `SessionStore` não existe.

- [ ] **Step 3: Subir Redis e implementar a sessão**

`pom.xml`: `spring-boot-starter-data-redis` e `testcontainers:redis` no escopo de teste. `docker-compose.yml` ganha serviço `redis` (`redis:7-alpine`), sem porta publicada além de `127.0.0.1`, e `mem_limit` de `128m`. `SessionStore` guarda `sessao:{token} -> userId` com TTL de **7 dias** e mantém `sessoes:{userId}` como `SET` dos tokens, para que `revokeAll` apague o conjunto. Token: 32 bytes de `SecureRandom` em Base64 URL.

- [ ] **Step 4: Login, logout e cookie**

`POST /api/auth/login` compara com `PasswordEncoder.matches` **mesmo quando o usuário não existe** — contra um hash descartável — para que o tempo de resposta não revele a diferença. Falha devolve **401** com `code = "CREDENCIAIS_INVALIDAS"`. `SessionCookie.build` marca `Secure` conforme o parâmetro, sempre `HttpOnly` e `SameSite=Lax`; o chamador passa `request.isSecure()`.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest=SessionTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/pom.xml backend/src/main/resources/application.yml docker-compose.yml backend/src/main/java/com/estudo/banco/auth backend/src/test/java/com/estudo/banco
git commit -m "feat: sessao opaca no Redis, revogavel, com login de tempo constante"
```

---

### Task 4: Filtro de autenticação e escopo das rotas

**Files:**
- Create: `backend/src/main/java/com/estudo/banco/auth/SessionAuthFilter.java`
- Create: `backend/src/main/java/com/estudo/banco/auth/UsuarioAtual.java`
- Modify: `backend/src/main/java/com/estudo/banco/account/AccountController.java`
- Modify: `backend/src/main/java/com/estudo/banco/notification/NotificationController.java`
- Modify: `backend/src/main/java/com/estudo/banco/notification/NotificationService.java`
- Test: `backend/src/test/java/com/estudo/banco/auth/EscopoDeRotasTest.java`

**Interfaces:**
- Consumes: `SessionStore`, `AccountRepository`.
- Produces: `UsuarioAtual.contaId(): Long` (resolvido por requisição); `NotificationService.encerrarInscricoes(Long accountId): void`.

- [ ] **Step 1: Write the failing test**

```java
@Test
void listarContasDevolveApenasAContaDoUsuario() {
    Long minha = registration.register("fab@exemplo.com", "senha-forte-1", "123456");
    registration.register("gil@exemplo.com", "senha-forte-1", "123456");
    List<AccountResponse> r = comSessao("fab@exemplo.com").get("/api/accounts");
    assertThat(r).hasSize(1);
    assertThat(r.get(0).id()).isEqualTo(minha);
}

@Test
void extratoDeOutraContaEhRecusado() {
    Long outra = registration.register("hugo@exemplo.com", "senha-forte-1", "123456");
    registration.register("iva@exemplo.com", "senha-forte-1", "123456");
    assertThat(comSessao("iva@exemplo.com").getStatus("/api/accounts/" + outra + "/statement"))
        .isEqualTo(403);
}

@Test
void streamSemSessaoEhRecusado() {
    assertThat(semSessao().getStatus("/api/notifications/stream")).isEqualTo(401);
}

@Test
void logoutEncerraOStreamJaAberto() {
    // Review Focus 1
    var sessao = comSessao("joao@exemplo.com");
    SseEmitter emitter = sessao.abrirStream();
    sessao.post("/api/auth/logout");
    await().atMost(5, SECONDS).untilAsserted(() -> assertThat(emitterEncerrado(emitter)).isTrue());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=EscopoDeRotasTest`
Expected: FAIL

- [ ] **Step 3: Implementar o filtro e reescopar as rotas**

`SessionAuthFilter` lê o cookie, resolve pelo `SessionStore` e guarda o id da conta no escopo da requisição; sem sessão válida devolve **401** para qualquer rota sob `/api` exceto `/api/auth/**` e `/actuator/**`. `AccountController.listar()` passa a devolver só a conta do usuário; `buscar` e `extrato` devolvem **403** para conta alheia. `NotificationController.stream()` **deixa de aceitar `accountId`** e usa o da sessão.

- [ ] **Step 4: Encerrar inscrições na revogação**

`NotificationService.encerrarInscricoes(Long accountId)` chama `complete()` em cada emitter daquela conta. `logout` e `revokeAll` chamam esse método — sem isso um stream aberto continua entregando para uma sessão que já não existe.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd backend && ./mvnw -q test`
Expected: PASS, incluindo a suíte que já existia.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/estudo/banco backend/src/test/java/com/estudo/banco/auth/EscopoDeRotasTest.java
git commit -m "feat: identidade vem da sessao, nao do cliente"
```

---

### Task 5: Transferência por número de conta, com senha transacional

**Files:**
- Modify: `backend/src/main/java/com/estudo/banco/transfer/dto/TransferRequest.java`
- Modify: `backend/src/main/java/com/estudo/banco/transfer/TransferController.java`
- Modify: `backend/src/main/java/com/estudo/banco/transfer/TransferService.java`
- Create: `backend/src/main/java/com/estudo/banco/error/SenhaTransacionalInvalidaException.java`
- Modify: `backend/src/main/java/com/estudo/banco/error/ApiExceptionHandler.java`
- Test: `backend/src/test/java/com/estudo/banco/transfer/TransferAutenticadaTest.java`

**Interfaces:**
- Consumes: `UsuarioAtual`, `PasswordEncoder`, `UserRepository`.
- Produces: `TransferRequest(Long targetAccountId, BigDecimal amount, String transactionPassword)` — **sem `sourceAccountId`**, que passa a vir da sessão.

- [ ] **Step 1: Write the failing test**

```java
@Test
void senhaTransacionalErradaNaoMoveDinheiro() {
    BigDecimal antes = saldo(minhaConta);
    assertThat(transferir(outraConta, "10.00", "errada").getStatus()).isEqualTo(403);
    assertThat(saldo(minhaConta)).isEqualByComparingTo(antes);
}

@Test
void contaDestinoInexistenteDevolveErroLegivel() {
    // Review Focus 2
    var r = transferir(999999L, "10.00", "123456");
    assertThat(r.getStatus()).isEqualTo(404);
    assertThat(r.body().code()).isEqualTo("CONTA_NAO_ENCONTRADA");
    assertThat(r.body().message()).isNotBlank();
}

@Test
void naoEhPossivelTransferirParaAPropriaConta() {
    assertThat(transferir(minhaConta, "10.00", "123456").getStatus()).isEqualTo(422);
}

@Test
void contaDeDemonstracaoNaoTransfereParaContaRegistrada() {
    // Review Focus 5
    var sessao = comSessaoDeVisitante(1L);
    Long registrada = registration.register("lia@exemplo.com", "senha-forte-1", "654321");
    assertThat(sessao.transferir(registrada, "10.00", demoSenhaTransacional()).getStatus())
        .isEqualTo(422);
}

@Test
void somaDoSistemaSegueInvarianteDepoisDeTransferenciaAutenticada() {
    BigDecimal antes = somaDeTodosOsSaldos();
    transferir(outraConta, "42.50", "123456");
    assertThat(somaDeTodosOsSaldos()).isEqualByComparingTo(antes);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=TransferAutenticadaTest`
Expected: FAIL

- [ ] **Step 3: Mudar o contrato e exigir a senha transacional**

`TransferRequest` perde `sourceAccountId`. `TransferController` monta o `TransferCommand` com a conta da sessão. `TransferService.execute` passa a receber a senha transacional e a validar **antes** de abrir a transação — senha errada não deve nem chegar ao `TransferExecutor`. Erro: **403**, `code = "SENHA_TRANSACIONAL_INVALIDA"`. Conta inexistente: **404**, `CONTA_NAO_ENCONTRADA`, mensagem dizendo que a conta de destino não existe.

- [ ] **Step 4: Regra das contas de demonstração**

Transferência cuja origem é conta de demonstração e cujo destino não é devolve **422** com `code = "DESTINO_FORA_DA_DEMONSTRACAO"`.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd backend && ./mvnw -q test`
Expected: PASS. `TransferServiceTest`, `IdempotencyTest` e `TransferConcurrencyTest` precisam ser ajustados ao novo contrato e continuar provando o que provavam.

- [ ] **Step 6: Commit**

```bash
git add backend/src backend/src/test
git commit -m "feat: transferencia sai da conta da sessao e exige senha transacional"
```

---

### Task 6: Bloqueio progressivo e IP confiável

**Files:**
- Create: `backend/src/main/java/com/estudo/banco/auth/AttemptLimiter.java`
- Create: `backend/src/main/java/com/estudo/banco/auth/ClientIpResolver.java`
- Modify: `backend/src/main/java/com/estudo/banco/auth/AuthController.java`, `TransferService.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/estudo/banco/auth/BloqueioProgressivoTest.java`

**Interfaces:**
- Produces: `AttemptLimiter.registrarFalha(String chave): void`, `.bloqueado(String chave): boolean`, `.limpar(String chave): void`; `ClientIpResolver.resolve(HttpServletRequest): Optional<String>`.

- [ ] **Step 1: Write the failing test**

```java
@Test
void depoisDeCincoFalhasAIdentidadeFicaBloqueada() {
    for (int i = 0; i < 5; i++) login("mia@exemplo.com", "errada");
    assertThat(login("mia@exemplo.com", "senha-forte-1").getStatus()).isEqualTo(429);
}

@Test
void bloqueioDeUmaIdentidadeNaoAtingeOutra() {
    for (int i = 0; i < 5; i++) login("mia@exemplo.com", "errada");
    assertThat(login("noa@exemplo.com", "senha-forte-1").getStatus()).isEqualTo(200);
}

@Test
void loginBemSucedidoLimpaAsFalhas() {
    for (int i = 0; i < 3; i++) login("noa@exemplo.com", "errada");
    login("noa@exemplo.com", "senha-forte-1");
    assertThat(limiter.bloqueado("login:noa@exemplo.com")).isFalse();
}

@Test
void semProxyConfiavelOIpEhIgnoradoEmVezDeBloquearTodoMundo() {
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.setRemoteAddr("10.9.9.9");                 // fora da lista confiavel
    req.addHeader("X-Forwarded-For", "203.0.113.7");
    assertThat(resolver.resolve(req)).isEmpty();
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=BloqueioProgressivoTest`
Expected: FAIL

- [ ] **Step 3: Implementar o limitador**

Contadores no Redis com TTL. Limiar: **5** falhas, janela de **15 minutos**, bloqueio de **15 minutos**. Chaves separadas por `login:{email}` e `transacional:{contaId}`. Excedido devolve **429** com `code = "MUITAS_TENTATIVAS"`.

- [ ] **Step 4: Resolver o IP só quando der para confiar**

`ClientIpResolver` devolve o primeiro IP de `X-Forwarded-For` **apenas** se `remoteAddr` estiver na lista `banco.proxies-confiaveis` do `application.yml`; caso contrário devolve vazio e o bloqueio por IP simplesmente não se aplica. O bloqueio por identidade é o primário e nunca depende disso — sem essa separação, um único atacante bloquearia todos os visitantes de uma vez.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest=BloqueioProgressivoTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/src
git commit -m "feat: bloqueio progressivo por identidade, com IP so quando confiavel"
```

---

### Task 7: Entrada de visitante e contas de demonstração

**Files:**
- Create: `backend/src/main/java/com/estudo/banco/demo/DemoAccountInitializer.java`
- Create: `backend/src/main/java/com/estudo/banco/demo/DemoController.java`
- Modify: `backend/src/main/resources/application.yml`, `.env.example`, `.env.prod.example`
- Test: `backend/src/test/java/com/estudo/banco/demo/EntradaDeVisitanteTest.java`

**Interfaces:**
- Produces: `GET /api/demo` devolvendo `{ accounts: [{id, holderName}], transactionPassword }`; `POST /api/auth/guest` com corpo `{ accountId }`.

- [ ] **Step 1: Write the failing test**

```java
@Test
void visitanteEntraEmUmCliqueEVeApenasAContaEscolhida() {
    var r = post("/api/auth/guest", "{\"accountId\":1}");
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(r.cookie("sessao")).isNotNull();
    assertThat(comEsseCookie().get("/api/accounts")).singleElement()
        .extracting(AccountResponse::id).isEqualTo(1L);
}

@Test
void visitanteSoEntraEmContaDeDemonstracao() {
    Long registrada = registration.register("olga@exemplo.com", "senha-forte-1", "654321");
    assertThat(post("/api/auth/guest", "{\"accountId\":" + registrada + "}").getStatus())
        .isEqualTo(403);
}

@Test
void aSenhaTransacionalDaDemonstracaoEhExposta() {
    assertThat(get("/api/demo").body().transactionPassword()).isNotBlank();
}

@Test
void comDemonstracaoDesligadaAsRotasSomem() {
    assertThat(get("/api/demo").getStatus()).isEqualTo(404);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=EntradaDeVisitanteTest`
Expected: FAIL

- [ ] **Step 3: Criar os donos das contas semeadas na subida**

`banco.demo.enabled` tem default **`false`** no `application.yml`; a suíte liga
onde precisa e o `.env.prod.example` liga em produção.
`DemoAccountInitializer` roda depois do Flyway: se `banco.demo.enabled` for `true`, garante um `User` para cada conta de demonstração, com senhas vindas de `BANCO_DEMO_PASSWORD` e `BANCO_DEMO_TRANSACTION_PASSWORD`, e liga `accounts.owner_id`. Sem as variáveis, a aplicação **falha na subida** em vez de criar credencial previsível.

- [ ] **Step 4: Expor a entrada de visitante**

`POST /api/auth/guest` cria sessão numa conta de demonstração e recusa qualquer outra com **403**. `GET /api/demo` devolve as contas e a senha transacional da demonstração. As duas rotas só existem com `banco.demo.enabled=true`.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest=EntradaDeVisitanteTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/src .env.example .env.prod.example
git commit -m "feat: entrada de visitante nas contas de demonstracao"
```

---

### Task 8: Front — cadastro, login, visitante e senha transacional

**Files:**
- Create: `frontend/src/api/auth.ts`
- Create: `frontend/src/screens/LoginScreen.tsx`
- Create: `frontend/src/screens/RegisterScreen.tsx`
- Modify: `frontend/src/App.tsx`, `frontend/src/api/client.ts`, `frontend/src/components/TransferSheet.tsx`
- Modify: `frontend/src/hooks/useNotifications.ts`

**Interfaces:**
- Consumes: `POST /api/auth/register`, `POST /api/auth/login`, `POST /api/auth/logout`, `POST /api/auth/guest`, `GET /api/demo`.
- Produces: `auth.ts` com `register`, `login`, `logout`, `guest`, `demo`.

**Por que esta tarefa não começa por um teste que falha:** o front não tem
harness de teste neste projeto, e montar um é trabalho de outra ordem que não
pertence a este plano. A verificação é no navegador, no passo 5, e é a única
tarefa assim — se você for acrescentar testes de front, é um plano à parte.

- [ ] **Step 1: Ajustar o cliente de API**

Todo `fetch` passa a enviar `credentials: 'include'`. `fetchAccounts` deixa de alimentar a lista de destinos. `postTransfer` passa a receber `{ targetAccountId, amount, transactionPassword }`.

- [ ] **Step 2: Telas de entrada**

`LoginScreen` com e-mail e senha, link para cadastro e **dois botões de visitante**, um por conta de demonstração, com a senha transacional da demonstração visível na tela. `RegisterScreen` pede e-mail, senha e senha transacional, e explica em uma linha por que são duas.

- [ ] **Step 3: Guarda de rota e saída**

`App.tsx` mostra `LoginScreen` enquanto `GET /api/accounts` devolver 401, e o app depois disso. Um botão de sair chama `logout` e volta para a entrada. O seletor "trocar de conta" **sai** — a identidade agora é do servidor.

- [ ] **Step 4: Transferência**

`TransferSheet` passa a ter campo de **número da conta de destino** e campo de **senha transacional**. Erro `SENHA_TRANSACIONAL_INVALIDA` aparece junto do campo da senha; `CONTA_NAO_ENCONTRADA`, junto do campo do número.

- [ ] **Step 5: Verificar no navegador**

Subir `docker compose up -d` e `npm run dev`; cadastrar, sair, entrar, transferir com senha certa e errada, e entrar como visitante. A notificação deve continuar chegando.

- [ ] **Step 6: Commit**

```bash
git add frontend/src
git commit -m "feat: telas de cadastro, login e visitante, com senha transacional"
```

---

### Task 9: Reset por lançamento compensatório, e documentação

**Files:**
- Modify: `scripts/reset-demo.sh`
- Modify: `README.md`, `docs/DEPLOY.md`, `.env.prod.example`, `docker-compose.prod.yml`
- Test: `backend/src/test/java/com/estudo/banco/demo/ResetCompensatorioTest.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void resetRestauraSaldosSemApagarRazao() {
    long entradasAntes = entries.count();
    transferirEntreContasDeDemonstracao("123.45");
    reset.restaurar();
    assertThat(saldo(1L)).isEqualByComparingTo("1000.0000");
    assertThat(saldo(2L)).isEqualByComparingTo("500.0000");
    assertThat(entries.count()).isGreaterThan(entradasAntes);   // compensou, nao apagou
}

@Test
void resetNaoTocaEmContaRegistrada() {
    Long registrada = registration.register("pam@exemplo.com", "senha-forte-1", "654321");
    BigDecimal antes = saldo(registrada);
    reset.restaurar();
    assertThat(saldo(registrada)).isEqualByComparingTo(antes);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=ResetCompensatorioTest`
Expected: FAIL

- [ ] **Step 3: Implementar a restauração**

A restauração vira uma transferência comum entre as duas contas de demonstração, do valor exato da diferença, usando o caminho normal — razão de dupla entrada se corrige com contrapartida, não apagando linha. `scripts/reset-demo.sh` passa a chamar essa rota em vez de rodar `TRUNCATE`.

- [ ] **Step 4: Atualizar compose, exemplo de ambiente e documentação**

`docker-compose.prod.yml` ganha o `redis` com teto de memória e sem porta publicada. `.env.prod.example` ganha `BANCO_DEMO_PASSWORD`, `BANCO_DEMO_TRANSACTION_PASSWORD` e a senha do Redis. `README.md` passa a dizer que há cadastro e entrada de visitante; `docs/DEPLOY.md` ganha o Redis na topologia e nas verificações.

- [ ] **Step 5: Run the whole suite**

Run: `cd backend && ./mvnw test`
Expected: PASS, suíte inteira.

- [ ] **Step 6: Commit**

```bash
git add scripts README.md docs docker-compose.prod.yml .env.prod.example backend/src
git commit -m "feat: reset por lancamento compensatorio, sem mutilar o razao"
```
