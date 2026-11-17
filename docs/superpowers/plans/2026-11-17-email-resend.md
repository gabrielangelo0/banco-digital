# E-mail transacional com Resend — plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Comprovante por e-mail nas duas pontas de cada transferência entre contas cadastradas, e e-mail de boas-vindas no cadastro, entregues pelo Resend a partir de uma fila própria no banco.

**Architecture:** O `OutboxDrainer` continua entregando só SSE e, na mesma transação, escreve linhas numa tabela `email_messages`. Um agendador separado (`EmailDispatcher` → `EmailSender`) drena essa fila FORA da transação de negócio, chama a API do Resend por HTTP e grava o resultado. A duplicata é barrada por consulta à chave de idempotência antes de inserir; o índice único é rede para corrida.

**Tech Stack:** Java 21 + Spring Boot 4, Postgres + Flyway, `RestClient` (já no classpath via `spring-boot-starter-webmvc`), JUnit 5 + Testcontainers + `MockRestServiceServer`.

**Spec:** `docs/superpowers/specs/2026-11-17-email-resend-design.md`

## Global Constraints

- Repositório **público**: nenhuma credencial em arquivo versionado. Chave só por variável de ambiente, como as `BANCO_DEMO_*`.
- Nenhuma dependência nova no `pom.xml`.
- Nenhum teste acessa a internet. A URL base do provedor é configurável (`banco.email.base-url`).
- Nenhuma chamada HTTP externa dentro da transação que segura a linha da conta.
- `User-Agent: banco-digital/1.0` em toda requisição ao Resend — sem ele a resposta é 403.
- `Idempotency-Key` em toda requisição; até 256 caracteres.
- Comentários e nomes em português, sem acentos em identificadores, no estilo do resto do backend: o comentário explica **por que**, não o que.
- A fila é sempre escrita; `banco.email.enabled` (padrão `false`) liga apenas a entrega.
- Nunca registrar em log a chave da API nem o endereço completo do destinatário.

## Review Focus

Entradas que a spec implica e que nenhum teste óbvio exercita — cada uma ganhou teste na tarefa dona:

1. **Nome de titular com `<`, `&` ou `"`** (vem do começo do e-mail no cadastro) entra no HTML do comprovante. Sem escape, quebra o corpo ou injeta marcação. → Tarefa 3.
2. **429 do Resend é 4xx mas é transitório.** A regra "4xx que não é 429 falha definitivo" precisa de teste que fixe o 429 do lado certo, senão o limite de vazão vira comprovante perdido. → Tarefa 5.
3. **Mensagem parada há dias em `PENDING`** quando a chave é ligada pela primeira vez: o entregador mandaria comprovantes velhos de uma vez. Precisa de um teto de idade. → Tarefa 6.
4. **Valor chega como `BigDecimal` de 4 casas** (`17.2500`) e precisa virar `R$ 17,25` — arredondamento explícito, não `toString()`. → Tarefa 3.
5. **Uma ponta resolve e a outra não** (conta sem dono). O drenador não pode falhar por causa disso; enfileira a que resolveu. → Tarefa 4.

---

### Task 1: A tabela e a fila

**Files:**
- Create: `backend/src/main/resources/db/migration/V4__email_messages.sql`
- Create: `backend/src/main/java/com/estudo/banco/email/EmailStatus.java`
- Create: `backend/src/main/java/com/estudo/banco/email/EmailMessage.java`
- Create: `backend/src/main/java/com/estudo/banco/email/EmailMessageRepository.java`
- Create: `backend/src/main/java/com/estudo/banco/email/EmailParaEnviar.java`
- Create: `backend/src/main/java/com/estudo/banco/email/EmailQueue.java`
- Test: `backend/src/test/java/com/estudo/banco/email/EmailQueueTest.java`

**Interfaces:**
- Consumes: `PostgresTestBase` (container e `jdbc`).
- Produces:
  - `enum EmailStatus { PENDING, SENT, FAILED }`
  - `record EmailParaEnviar(String destinatario, String assunto, String html, String texto, String chave)`
  - `EmailQueue.enfileirar(EmailParaEnviar mensagem) -> boolean` — `true` se gravou, `false` se a chave já existia
  - `EmailMessageRepository extends JpaRepository<EmailMessage, Long>` com `boolean existsByIdempotencyKey(String chave)` e `List<EmailMessage> findTop5ByStatusAndNextAttemptAtBeforeOrderByIdAsc(EmailStatus status, OffsetDateTime agora)`
  - `EmailMessage` com `marcarEnviado(String providerMessageId)`, `adiar(OffsetDateTime quando, String erro)`, `marcarFalho(String erro)` e os getters das colunas

- [ ] **Step 1: Escrever o teste que falha**

```java
class EmailQueueTest extends PostgresTestBase {
    @Autowired EmailQueue fila;

    @Test
    void enfileiraMensagemPendente() {
        boolean gravou = fila.enfileirar(new EmailParaEnviar(
                "alguem@exemplo.com", "Assunto", "<p>oi</p>", "oi", "teste/" + UUID.randomUUID()));

        assertThat(gravou).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM email_messages WHERE to_address = 'alguem@exemplo.com'",
                String.class)).isEqualTo("PENDING");
    }

    @Test
    void mesmaChaveGravaUmaVezSo() {
        String chave = "teste/" + UUID.randomUUID();
        EmailParaEnviar m = new EmailParaEnviar("a@b.com", "A", "<p>a</p>", "a", chave);

        assertThat(fila.enfileirar(m)).isTrue();
        assertThat(fila.enfileirar(m)).isFalse();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM email_messages WHERE idempotency_key = ?",
                Integer.class, chave)).isEqualTo(1);
    }

    @Test
    void recusaDestinatarioVazio() {
        assertThatThrownBy(() -> fila.enfileirar(new EmailParaEnviar(
                "  ", "A", "<p>a</p>", "a", "teste/" + UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `cd backend && ./mvnw test -Dtest=EmailQueueTest`
Expected: FAIL — `EmailQueue` não existe (erro de compilação).

- [ ] **Step 3: Escrever a migração `V4__email_messages.sql`**

Colunas: `id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY`, `to_address TEXT NOT NULL`, `subject TEXT NOT NULL`, `body_html TEXT NOT NULL`, `body_text TEXT NOT NULL`, `idempotency_key TEXT NOT NULL`, `status TEXT NOT NULL`, `attempts INT NOT NULL DEFAULT 0`, `next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now()`, `provider_message_id TEXT`, `last_error TEXT`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`, `sent_at TIMESTAMPTZ`.

Mais `CREATE UNIQUE INDEX email_messages_key_unique ON email_messages (idempotency_key)` e um índice de varredura `ON email_messages (status, next_attempt_at)`.

O comentário no topo explica por que o índice único existe mesmo havendo consulta antes de inserir: ele é a rede para duas entregas simultâneas, não o caminho normal.

- [ ] **Step 4: Implementar `EmailStatus`, `EmailMessage`, `EmailMessageRepository`, `EmailParaEnviar` e `EmailQueue`**

`EmailQueue.enfileirar` é `@Transactional`: rejeita destinatário em branco com `IllegalArgumentException`, consulta `existsByIdempotencyKey` e devolve `false` sem gravar quando já existe. `status` é mapeado com `@Enumerated(EnumType.STRING)`.

- [ ] **Step 5: Rodar e ver passar**

Run: `cd backend && ./mvnw test -Dtest=EmailQueueTest`
Expected: PASS, 3 testes.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/migration/V4__email_messages.sql backend/src/main/java/com/estudo/banco/email backend/src/test/java/com/estudo/banco/email
git commit -m "feat: a fila de e-mail, com a chave de idempotencia como trava"
```

---

### Task 2: Quem pode receber

**Files:**
- Create: `backend/src/main/java/com/estudo/banco/email/Destinatarios.java`
- Modify: `backend/src/test/java/com/estudo/banco/support/PostgresTestBase.java` (acrescentar `novaContaComDono`)
- Test: `backend/src/test/java/com/estudo/banco/email/DestinatariosTest.java`

**Interfaces:**
- Consumes: `AccountRepository.findById`, `UserRepository.findById`, `DemoAccounts.ehDemonstracao(Long)`.
- Produces:
  - `record Destinatario(String email, String nome)`
  - `Destinatarios.para(Long contaId) -> Optional<Destinatario>`
  - `PostgresTestBase.novaContaComDono(String titular, String saldo, String email) -> Long`
  - `PostgresTestBase.usuarioDe(String email) -> Long` — insere o usuário se não existir e devolve o id; a Tarefa 4 precisa dele para criar contas com id escolhido

- [ ] **Step 1: Escrever o teste que falha**

```java
class DestinatariosTest extends PostgresTestBase {
    @Autowired Destinatarios destinatarios;

    @Test
    void contaCadastradaResolveParaOEmailDoDono() {
        Long conta = novaContaComDono("Mariana", "1000.00", "mariana@exemplo.com");

        assertThat(destinatarios.para(conta)).contains(
                new Destinatario("mariana@exemplo.com", "Mariana"));
    }

    @Test
    void contaSemDonoNaoResolve() {
        assertThat(destinatarios.para(novaConta("Orfa", "100.00"))).isEmpty();
    }

    @Test
    void enderecoLocalNaoResolve() {
        Long conta = novaContaComDono("Demo", "1000.00", "demo-99@banco.local");

        assertThat(destinatarios.para(conta)).isEmpty();
    }

    @Test
    void contaInexistenteNaoResolve() {
        assertThat(destinatarios.para(999_999L)).isEmpty();
    }
}
```

A regra de conta de demonstração é exercitada na Tarefa 4, onde `banco.demo.enabled` está ligado; aqui ela ficaria sempre falsa e o teste não provaria nada.

- [ ] **Step 2: Rodar e ver falhar**

Run: `cd backend && ./mvnw test -Dtest=DestinatariosTest`
Expected: FAIL — `Destinatarios` não existe.

- [ ] **Step 3: Acrescentar `novaContaComDono` ao `PostgresTestBase`**

`usuarioDe` insere em `users` (e-mail, dois hashes quaisquer não vazios) se ainda não houver e devolve o id; `novaContaComDono` usa ele e insere em `accounts` com `owner_id`, devolvendo o id da conta. Comentário dizendo por que existe: os testes de e-mail precisam de conta com dono, e `novaConta` cria conta órfã de propósito.

- [ ] **Step 4: Implementar `Destinatarios.para`**

Devolve vazio quando: a conta não existe, `DemoAccounts.ehDemonstracao(contaId)` é verdadeiro, `ownerId` é nulo, o usuário não existe, ou o e-mail termina em `.local`. O nome vem de `Account.getHolderName()`.

- [ ] **Step 5: Rodar e ver passar**

Run: `cd backend && ./mvnw test -Dtest=DestinatariosTest`
Expected: PASS, 4 testes.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/estudo/banco/email/Destinatarios.java backend/src/test/java/com/estudo/banco/email/DestinatariosTest.java backend/src/test/java/com/estudo/banco/support/PostgresTestBase.java
git commit -m "feat: quem pode receber e-mail, e quem nao pode"
```

---

### Task 3: Os textos

**Files:**
- Create: `backend/src/main/java/com/estudo/banco/email/EmailTemplates.java`
- Test: `backend/src/test/java/com/estudo/banco/email/EmailTemplatesTest.java`

**Interfaces:**
- Consumes: `EmailParaEnviar` (Tarefa 1), `Direction` (`com.estudo.banco.ledger.Direction`).
- Produces:
  - `record DadosDoComprovante(UUID transferId, Direction direcao, BigDecimal valor, String nomeDoDono, Long contaDoDono, Long contaDaOutraPonta, OffsetDateTime quando)`
  - `EmailTemplates.comprovante(String destinatario, DadosDoComprovante dados) -> EmailParaEnviar`
  - `EmailTemplates.boasVindas(String destinatario, String nome, Long usuarioId, Long contaId, BigDecimal saldoInicial) -> EmailParaEnviar`
    (a chave é `welcome/<usuarioId>`, e o corpo mostra o número da **conta** — são números diferentes)

`EmailTemplates` não é `@Service` com estado: é um componente sem dependências, testável sem contexto do Spring (`JUnit` puro, sem `PostgresTestBase`).

- [ ] **Step 1: Escrever o teste que falha**

```java
class EmailTemplatesTest {
    private final EmailTemplates textos = new EmailTemplates();

    private static final UUID ID = UUID.fromString("cfb01ea9-3d7a-4e21-9f44-2b1c8e5a7d90");
    private static final OffsetDateTime QUANDO =
            OffsetDateTime.parse("2026-11-17T17:18:00Z"); // 14:18 em Brasilia

    private DadosDoComprovante dados(Direction direcao, String valor) {
        return new DadosDoComprovante(ID, direcao, new BigDecimal(valor),
                "Gabriel", 1L, 7L, QUANDO);
    }

    @Test
    void comprovanteEnviadoTrazValorContasEIdentificador() {
        EmailParaEnviar m = textos.comprovante("g@exemplo.com", dados(Direction.DEBIT, "17.2500"));

        assertThat(m.assunto()).isEqualTo("Comprovante de transferência — R$ 17,25");
        assertThat(m.html()).contains("R$ 17,25", "Gabriel", "conta 1", "conta 7", ID.toString());
        assertThat(m.texto()).contains("R$ 17,25", ID.toString());
    }

    @Test
    void comprovanteNaoRevelaONomeDaOutraPonta() {
        EmailParaEnviar m = textos.comprovante("g@exemplo.com", dados(Direction.DEBIT, "17.2500"));

        // "Gabriel" e o dono deste comprovante; a outra ponta aparece so pelo numero.
        assertThat(m.html()).doesNotContain("Mariana");
    }

    @Test
    void horarioSaiEmBrasiliaNaoEmUtc() {
        EmailParaEnviar m = textos.comprovante("g@exemplo.com", dados(Direction.DEBIT, "17.2500"));

        assertThat(m.html()).contains("17/11/2026").contains("14:18").doesNotContain("17:18");
    }

    @Test
    void valorDeQuatroCasasVira2Casas() {
        EmailParaEnviar m = textos.comprovante("g@exemplo.com", dados(Direction.CREDIT, "1000.0000"));

        assertThat(m.html()).contains("R$ 1.000,00").doesNotContain("1000.0000");
    }

    @Test
    void nomeComMarcacaoEEscapado() {
        DadosDoComprovante perigoso = new DadosDoComprovante(ID, Direction.DEBIT,
                new BigDecimal("10.0000"), "<script>x</script>&\"", 1L, 7L, QUANDO);

        EmailParaEnviar m = textos.comprovante("g@exemplo.com", perigoso);

        assertThat(m.html()).doesNotContain("<script>").contains("&lt;script&gt;");
    }

    @Test
    void creditoFalaEmRecebida() {
        EmailParaEnviar m = textos.comprovante("m@exemplo.com", dados(Direction.CREDIT, "17.2500"));

        assertThat(m.assunto()).isEqualTo("Comprovante de transferência — R$ 17,25");
        assertThat(m.html()).contains("Transferência recebida");
    }

    @Test
    void chaveDoComprovanteTrazDirecao() {
        assertThat(textos.comprovante("g@e.com", dados(Direction.DEBIT, "1.0000")).chave())
                .isEqualTo("transfer/" + ID + "/DEBIT");
        assertThat(textos.comprovante("m@e.com", dados(Direction.CREDIT, "1.0000")).chave())
                .isEqualTo("transfer/" + ID + "/CREDIT");
    }

    @Test
    void boasVindasTrazSaldoInicialEAvisoDaSegundaSenha() {
        EmailParaEnviar m = textos.boasVindas("novo@exemplo.com", "Novo", 42L, 7L,
                new BigDecimal("1000.0000"));

        assertThat(m.chave()).isEqualTo("welcome/42");
        assertThat(m.html()).contains("R$ 1.000,00", "conta 7").doesNotContain("conta 42");
        assertThat(m.texto()).containsIgnoringCase("segunda senha");
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `cd backend && ./mvnw test -Dtest=EmailTemplatesTest`
Expected: FAIL — `EmailTemplates` não existe.

- [ ] **Step 3: Implementar `EmailTemplates`**

HTML em tabelas com estilo embutido, sem imagem externa, seguindo o modelo **A · Documento** da spec: régua verde `#0d8139` no topo com a marca, o valor em destaque, e a tabela De / Para / Quando / Identificador. Identificador em fonte monoespaçada, inteiro, com a frase que explica para que serve. Rodapé dizendo por que a pessoa recebeu e que é projeto de estudo com dinheiro que não é real.

Dinheiro: `NumberFormat.getCurrencyInstance(new Locale("pt","BR"))` sobre o valor com `setScale(2, RoundingMode.HALF_UP)`. Data: `DateTimeFormatter.ofPattern("dd/MM/yyyy', às 'HH:mm")` com `ZoneId.of("America/Sao_Paulo")`, e o texto diz "horário de Brasília". Escape: um método privado que troca `&`, `<`, `>` e `"` — nessa ordem, `&` primeiro.

A versão `texto` é o mesmo conteúdo em linhas, sem marcação.

- [ ] **Step 4: Rodar e ver passar**

Run: `cd backend && ./mvnw test -Dtest=EmailTemplatesTest`
Expected: PASS, 8 testes.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/estudo/banco/email/EmailTemplates.java backend/src/test/java/com/estudo/banco/email/EmailTemplatesTest.java
git commit -m "feat: o comprovante e o e-mail de boas-vindas"
```

---

### Task 4: O gancho na transferência

**Files:**
- Modify: `backend/src/main/java/com/estudo/banco/outbox/OutboxDrainer.java`
- Modify: `backend/src/main/java/com/estudo/banco/outbox/TransferCompletedEvent.java` (nada a mudar — confirmar que o `transferId` basta)
- Test: `backend/src/test/java/com/estudo/banco/email/ComprovanteNaTransferenciaTest.java`

**Interfaces:**
- Consumes: `EmailQueue.enfileirar`, `Destinatarios.para`, `EmailTemplates.comprovante`, `DadosDoComprovante`, `TransferRepository`.
- Produces: nada para tarefas seguintes.

- [ ] **Step 1: Escrever o teste que falha**

```java
// Ids fixos e altos, criados pelo proprio teste: o PostgresTestBase avisa que
// nenhum teste pode depender das contas semeadas, porque o banco e compartilhado
// pela suite e outro teste pode ter mexido no saldo delas.
@TestPropertySource(properties = {"banco.demo.enabled=true", "banco.demo.account-ids=9001,9002"})
class ComprovanteNaTransferenciaTest extends PostgresTestBase {
    @Autowired TransferService transfers;
    @Autowired OutboxPublisher publisher;

    private List<String> chavesDe(UUID transferId) {
        return jdbc.queryForList(
                "SELECT idempotency_key FROM email_messages WHERE idempotency_key LIKE ?",
                String.class, "transfer/" + transferId + "/%");
    }

    @Test
    void transferenciaEntreCadastradosEnfileiraOsDoisComprovantes() {
        Long origem = novaContaComDono("Gabriel", "1000.00", "gabriel@exemplo.com");
        Long destino = novaContaComDono("Mariana", "0.00", "mariana@exemplo.com");

        TransferResult r = transfers.execute(new TransferCommand(
                origem, destino, new BigDecimal("17.25"), UUID.randomUUID().toString()));
        publisher.publishPending();

        assertThat(chavesDe(r.transferId())).containsExactlyInAnyOrder(
                "transfer/" + r.transferId() + "/DEBIT",
                "transfer/" + r.transferId() + "/CREDIT");
    }

    @Test
    void transferenciaEntreContasDeDemonstracaoNaoEnfileiraNada() {
        contaDeDemonstracao(9001L, "Demo um", "1000.00", "demo-9001@banco.local");
        contaDeDemonstracao(9002L, "Demo dois", "500.00", "demo-9002@banco.local");

        TransferResult r = transfers.execute(new TransferCommand(
                9001L, 9002L, new BigDecimal("5.00"), UUID.randomUUID().toString()));
        publisher.publishPending();

        assertThat(chavesDe(r.transferId())).isEmpty();
    }

    /** Conta com id escolhido, para casar com `banco.demo.account-ids`. */
    private void contaDeDemonstracao(Long id, String titular, String saldo, String email) {
        jdbc.update("INSERT INTO accounts (id, holder_name, balance, owner_id)"
                + " VALUES (?, ?, ?, ?) ON CONFLICT (id) DO NOTHING",
                id, titular, new BigDecimal(saldo), usuarioDe(email));
    }

    @Test
    void pontaSemDonoNaoImpedeAOutra() {
        Long comDono = novaContaComDono("Gabriel", "1000.00", "gabriel2@exemplo.com");
        Long orfa = novaConta("Orfa", "0.00");

        TransferResult r = transfers.execute(new TransferCommand(
                comDono, orfa, new BigDecimal("3.00"), UUID.randomUUID().toString()));
        publisher.publishPending();

        assertThat(chavesDe(r.transferId()))
                .containsExactly("transfer/" + r.transferId() + "/DEBIT");
    }

    @Test
    void segundaDrenagemNaoDuplicaComprovante() {
        Long origem = novaContaComDono("Gabriel", "1000.00", "gabriel3@exemplo.com");
        Long destino = novaContaComDono("Mariana", "0.00", "mariana3@exemplo.com");

        TransferResult r = transfers.execute(new TransferCommand(
                origem, destino, new BigDecimal("9.00"), UUID.randomUUID().toString()));
        publisher.publishPending();
        // Devolve o evento a pendente para forcar a reentrega que o outbox
        // promete: a entrega e "pelo menos uma vez".
        jdbc.update("UPDATE outbox_events SET published = false WHERE payload->>'transferId' = ?",
                r.transferId().toString());
        publisher.publishPending();

        assertThat(chavesDe(r.transferId())).hasSize(2);
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `cd backend && ./mvnw test -Dtest=ComprovanteNaTransferenciaTest`
Expected: FAIL — nenhuma linha em `email_messages`.

- [ ] **Step 3: Estender `OutboxDrainer.entregar`**

Depois das duas notificações por SSE, para cada ponta: `Destinatarios.para(contaId)`, e se resolver, montar `DadosDoComprovante` (o `quando` vem de `TransferRepository.findById(transferId).getCreatedAt()`, **não** de um campo novo no evento) e chamar `EmailQueue.enfileirar`. A ponta que não resolve é pulada em silêncio — não é erro.

O comentário explica por que o `quando` não virou campo do evento: os eventos já gravados em produção voltariam com nulo.

- [ ] **Step 4: Rodar e ver passar**

Run: `cd backend && ./mvnw test -Dtest=ComprovanteNaTransferenciaTest`
Expected: PASS, 4 testes.

`usuarioDe(String email)` é um auxiliar a extrair do `novaContaComDono` da Tarefa 2:
insere o usuário se não existir e devolve o id. As duas contas de demonstração
têm endereço `.local` de propósito — é o par que a produção realmente tem.

- [ ] **Step 5: Rodar a suíte inteira**

Run: `cd backend && ./mvnw test`
Expected: tudo verde. `OutboxTest` e `OutboxScheduledTest` continuam passando — o drenador ganhou trabalho, não mudou de contrato.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/estudo/banco/outbox/OutboxDrainer.java backend/src/test/java/com/estudo/banco/email/ComprovanteNaTransferenciaTest.java
git commit -m "feat: a transferencia enfileira os dois comprovantes"
```

---

### Task 5: O cliente do Resend

**Files:**
- Create: `backend/src/main/java/com/estudo/banco/email/ResendClient.java`
- Create: `backend/src/main/java/com/estudo/banco/email/EmailConfig.java`
- Create: `backend/src/main/java/com/estudo/banco/email/FalhaTemporariaDoProvedor.java`
- Create: `backend/src/main/java/com/estudo/banco/email/FalhaDefinitivaDoProvedor.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/estudo/banco/email/ResendClientTest.java`

**Interfaces:**
- Consumes: `EmailParaEnviar`.
- Produces: `ResendClient.enviar(EmailParaEnviar) -> String` (o `id` devolvido pelo Resend); lança `FalhaTemporariaDoProvedor` (tentar de novo) ou `FalhaDefinitivaDoProvedor` (desistir). Ambas `RuntimeException`.

- [ ] **Step 1: Escrever o teste que falha**

Teste puro de Spring REST (`@RestClientTest` ou `MockRestServiceServer` sobre um `RestClient.Builder`), sem Postgres.

```java
class ResendClientTest {
    private MockRestServiceServer servidor;
    private ResendClient cliente;

    @BeforeEach
    void preparar() {
        RestClient.Builder builder = RestClient.builder();
        servidor = MockRestServiceServer.bindTo(builder).build();
        cliente = new ResendClient(builder, "https://api.resend.com", "re_chave_de_teste",
                "Banco <banco@bank.devfly.net>");
    }

    private EmailParaEnviar mensagem() {
        return new EmailParaEnviar("a@b.com", "Assunto", "<p>oi</p>", "oi", "transfer/x/DEBIT");
    }

    @Test
    void mandaCabecalhosECorpoQueOResendExige() {
        servidor.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer re_chave_de_teste"))
                .andExpect(header("User-Agent", "banco-digital/1.0"))
                .andExpect(header("Idempotency-Key", "transfer/x/DEBIT"))
                .andExpect(jsonPath("$.from").value("Banco <banco@bank.devfly.net>"))
                .andExpect(jsonPath("$.to").value("a@b.com"))
                .andExpect(jsonPath("$.subject").value("Assunto"))
                .andExpect(jsonPath("$.html").value("<p>oi</p>"))
                .andExpect(jsonPath("$.text").value("oi"))
                .andRespond(withSuccess("{\"id\":\"49a3999c-0ce1-4ea6-ab68-afcd6dc2e794\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(cliente.enviar(mensagem())).isEqualTo("49a3999c-0ce1-4ea6-ab68-afcd6dc2e794");
        servidor.verify();
    }

    @Test
    void erroDeServidorEhTemporario() {
        servidor.expect(requestTo(anything())).andRespond(withServerError());

        assertThatThrownBy(() -> cliente.enviar(mensagem()))
                .isInstanceOf(FalhaTemporariaDoProvedor.class);
    }

    @Test
    void limiteDeVazaoEhTemporarioAindaQueSeja4xx() {
        servidor.expect(requestTo(anything()))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> cliente.enviar(mensagem()))
                .isInstanceOf(FalhaTemporariaDoProvedor.class);
    }

    @Test
    void dominioNaoVerificadoEhDefinitivo() {
        servidor.expect(requestTo(anything()))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> cliente.enviar(mensagem()))
                .isInstanceOf(FalhaDefinitivaDoProvedor.class);
    }

    @Test
    void aChaveNaoApareceNaMensagemDeErro() {
        servidor.expect(requestTo(anything())).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> cliente.enviar(mensagem()))
                .hasMessageNotContaining("re_chave_de_teste");
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `cd backend && ./mvnw test -Dtest=ResendClientTest`
Expected: FAIL — `ResendClient` não existe.

- [ ] **Step 3: Implementar `ResendClient` e `EmailConfig`**

`ResendClient(RestClient.Builder builder, String baseUrl, String apiKey, String remetente)` monta o `RestClient` com os cabeçalhos fixos. 429 e 5xx → `FalhaTemporariaDoProvedor`; os demais 4xx → `FalhaDefinitivaDoProvedor`. A mensagem da exceção leva status e corpo da resposta, nunca a chave.

`EmailConfig` publica o bean com `@Value` em `banco.email.base-url`, `banco.email.api-key`, `banco.email.from`, e define os tempos-limite no `ClientHttpRequestFactory`: **5 s para conectar, 10 s para ler**.

No `application.yml`, bloco `banco.email` com `enabled: ${BANCO_EMAIL_ENABLED:false}`, `api-key: ${BANCO_EMAIL_API_KEY:}`, `from: ${BANCO_EMAIL_FROM:Banco <banco@bank.devfly.net>}`, `base-url: ${BANCO_EMAIL_BASE_URL:https://api.resend.com}`, `poll-ms`, `max-attempts: 6`, `max-age-hours: 24`. E `spring.task.scheduling.pool-size: 2`, com o comentário dizendo por que: o entregador não pode dividir a thread com o drenador.

- [ ] **Step 4: Rodar e ver passar**

Run: `cd backend && ./mvnw test -Dtest=ResendClientTest`
Expected: PASS, 5 testes.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/estudo/banco/email backend/src/main/resources/application.yml backend/src/test/java/com/estudo/banco/email/ResendClientTest.java
git commit -m "feat: o cliente do Resend, com tempo-limite e 429 como falha que passa"
```

---

### Task 6: O entregador

**Files:**
- Create: `backend/src/main/java/com/estudo/banco/email/EmailSender.java`
- Create: `backend/src/main/java/com/estudo/banco/email/EmailDispatcher.java`
- Test: `backend/src/test/java/com/estudo/banco/email/EmailSenderTest.java`

**Interfaces:**
- Consumes: `EmailMessageRepository`, `ResendClient`, `EmailStatus`.
- Produces: `EmailSender.enviarPendentes() -> int` (quantas tentou); `EmailDispatcher` com `@Scheduled`, que só chama o sender quando `banco.email.enabled` é verdadeiro.

A separação entre os dois beans é a mesma de `OutboxPublisher`/`OutboxDrainer`, e pelo mesmo motivo: `@Transactional` do Spring é por proxy, e chamada interna não o atravessa.

- [ ] **Step 1: Escrever o teste que falha**

`ResendClient` entra como `@MockitoBean` — o que está sob teste aqui é a máquina de estados da fila, não o HTTP (esse é a Tarefa 5).

```java
@TestPropertySource(properties = "banco.email.enabled=false")
class EmailSenderTest extends PostgresTestBase {
    @Autowired EmailSender sender;
    @Autowired EmailQueue fila;
    @MockitoBean ResendClient resend;

    private String enfileirarUma() {
        String chave = "teste/" + UUID.randomUUID();
        fila.enfileirar(new EmailParaEnviar("a@b.com", "A", "<p>a</p>", "a", chave));
        return chave;
    }

    private Map<String, Object> linha(String chave) {
        return jdbc.queryForMap(
                "SELECT status, attempts, provider_message_id, sent_at, next_attempt_at"
                        + " FROM email_messages WHERE idempotency_key = ?", chave);
    }

    @Test
    void sucessoMarcaEnviadoEGuardaOIdDoProvedor() {
        String chave = enfileirarUma();
        when(resend.enviar(any())).thenReturn("id-do-resend");

        sender.enviarPendentes();

        assertThat(linha(chave)).containsEntry("status", "SENT")
                .containsEntry("provider_message_id", "id-do-resend");
        assertThat(linha(chave).get("sent_at")).isNotNull();
    }

    @Test
    void falhaTemporariaContaTentativaEAdia() {
        String chave = enfileirarUma();
        when(resend.enviar(any())).thenThrow(new FalhaTemporariaDoProvedor("500"));

        sender.enviarPendentes();

        assertThat(linha(chave)).containsEntry("status", "PENDING").containsEntry("attempts", 1);
        assertThat((OffsetDateTime) linha(chave).get("next_attempt_at"))
                .isAfter(OffsetDateTime.now());
    }

    @Test
    void falhaDefinitivaDesisteNaHora() {
        String chave = enfileirarUma();
        when(resend.enviar(any())).thenThrow(new FalhaDefinitivaDoProvedor("403"));

        sender.enviarPendentes();

        assertThat(linha(chave)).containsEntry("status", "FAILED").containsEntry("attempts", 1);
    }

    @Test
    void desisteAoChegarNoTetoDeTentativas() {
        String chave = enfileirarUma();
        when(resend.enviar(any())).thenThrow(new FalhaTemporariaDoProvedor("500"));

        for (int i = 0; i < 7; i++) {
            jdbc.update("UPDATE email_messages SET next_attempt_at = now() - interval '1 hour'"
                    + " WHERE idempotency_key = ?", chave);
            sender.enviarPendentes();
        }

        assertThat(linha(chave)).containsEntry("status", "FAILED");
    }

    @Test
    void mensagemVelhaDemaisNaoEhEnviada() {
        String chave = enfileirarUma();
        jdbc.update("UPDATE email_messages SET created_at = now() - interval '48 hours'"
                + " WHERE idempotency_key = ?", chave);

        sender.enviarPendentes();

        // Ligar a chave pela primeira vez nao pode despejar comprovantes de dias atras.
        assertThat(linha(chave)).containsEntry("status", "FAILED");
        verifyNoInteractions(resend);
    }

    @Test
    void naoTentaAntesDaHoraMarcada() {
        String chave = enfileirarUma();
        jdbc.update("UPDATE email_messages SET next_attempt_at = now() + interval '1 hour'"
                + " WHERE idempotency_key = ?", chave);

        sender.enviarPendentes();

        verifyNoInteractions(resend);
        assertThat(linha(chave)).containsEntry("status", "PENDING");
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `cd backend && ./mvnw test -Dtest=EmailSenderTest`
Expected: FAIL — `EmailSender` não existe.

- [ ] **Step 3: Implementar `EmailSender` e `EmailDispatcher`**

`enviarPendentes()` é `@Transactional`: pega `findTop5ByStatusAndNextAttemptAtBeforeOrderByIdAsc(PENDING, now())`, e para cada uma — se `created_at` é mais velha que `banco.email.max-age-hours`, marca `FAILED` sem chamar o provedor; senão chama `ResendClient.enviar` e aplica o resultado. Espera entre tentativas: `2^attempts` minutos. Ao atingir `max-attempts`, `FAILED`.

`EmailDispatcher` é `@Scheduled(fixedDelayString = "${banco.email.poll-ms:5000}")`, não deixa exceção subir (uma mensagem ruim não para a fila) e não faz nada quando `banco.email.enabled` é falso.

- [ ] **Step 4: Rodar e ver passar**

Run: `cd backend && ./mvnw test -Dtest=EmailSenderTest`
Expected: PASS, 6 testes.

- [ ] **Step 5: Rodar a suíte inteira**

Run: `cd backend && ./mvnw test`
Expected: tudo verde.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/estudo/banco/email backend/src/test/java/com/estudo/banco/email/EmailSenderTest.java
git commit -m "feat: o entregador da fila de e-mail"
```

---

### Task 7: O gancho no cadastro

**Files:**
- Modify: `backend/src/main/java/com/estudo/banco/auth/RegistrationService.java`
- Test: `backend/src/test/java/com/estudo/banco/email/BoasVindasTest.java`

**Interfaces:**
- Consumes: `EmailQueue`, `EmailTemplates.boasVindas`.
- Produces: nada.

- [ ] **Step 1: Escrever o teste que falha**

```java
class BoasVindasTest extends PostgresTestBase {
    @Autowired RegistrationService cadastro;

    @Test
    void cadastroEnfileiraBoasVindas() {
        String email = "novo-" + UUID.randomUUID() + "@exemplo.com";

        Long conta = cadastro.register(email, "senha-de-acesso", "senha-transacional");
        Long usuario = jdbc.queryForObject(
                "SELECT owner_id FROM accounts WHERE id = ?", Long.class, conta);

        assertThat(jdbc.queryForObject(
                "SELECT to_address FROM email_messages WHERE idempotency_key = ?",
                String.class, "welcome/" + usuario)).isEqualTo(email);
    }

    @Test
    void cadastroRecusadoNaoDeixaEmailNaFila() {
        String email = "repetido-" + UUID.randomUUID() + "@exemplo.com";
        cadastro.register(email, "senha-de-acesso", "senha-transacional");
        long antes = jdbc.queryForObject("SELECT count(*) FROM email_messages", Long.class);

        assertThatThrownBy(() -> cadastro.register(email, "outra-senha", "outra-transacional"))
                .isInstanceOf(CadastroInvalidoException.class);

        // Mesma transacao: nao existe "boas-vindas a um cadastro que nao houve".
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_messages", Long.class))
                .isEqualTo(antes);
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `cd backend && ./mvnw test -Dtest=BoasVindasTest`
Expected: FAIL — nenhuma linha com a chave `welcome/<id>`.

- [ ] **Step 3: Enfileirar as boas-vindas no `RegistrationService.register`**

Depois de criar a conta, dentro da mesma transação, com `EmailTemplates.boasVindas(email, titular, usuario.getId(), conta.getId(), SALDO_INICIAL)` — a chave leva o id do **usuário**, o corpo mostra o número da **conta**.

- [ ] **Step 4: Rodar e ver passar**

Run: `cd backend && ./mvnw test -Dtest=BoasVindasTest`
Expected: PASS, 2 testes.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/estudo/banco/auth/RegistrationService.java backend/src/test/java/com/estudo/banco/email/BoasVindasTest.java
git commit -m "feat: boas-vindas no cadastro, na mesma transacao da conta"
```

---

### Task 8: Ambiente, tela e documentação

**Files:**
- Modify: `.env.example`
- Modify: `docker-compose.prod.yml`
- Modify: `frontend/src/screens/Vitrine.tsx`
- Modify: `README.md`

**Interfaces:**
- Consumes: os nomes de variável da Tarefa 5.
- Produces: nada.

- [ ] **Step 1: Acrescentar as variáveis ao `.env.example`**

Bloco comentado explicando que a chave vem do Resend, que o domínio precisa estar verificado, e que `BANCO_EMAIL_ENABLED=false` deixa a fila acumulando sem mandar nada:

```
# E-mail transacional (Resend). A fila e sempre escrita; esta chave liga so a
# entrega, entao desligada em desenvolvimento nada sai e nada se perde.
BANCO_EMAIL_ENABLED=false
BANCO_EMAIL_API_KEY=
BANCO_EMAIL_FROM=Banco <banco@bank.devfly.net>
```

- [ ] **Step 2: Repassar as variáveis ao serviço `backend` no `docker-compose.prod.yml`**

Nas três, o mesmo padrão das `BANCO_DEMO_*`: `BANCO_EMAIL_ENABLED: ${BANCO_EMAIL_ENABLED:-false}` e as outras duas com default vazio.

- [ ] **Step 3: Acrescentar a linha na vitrine**

Um item a mais em `.vitrine-lista`, no estilo das outras frases: que o comprovante chega no e-mail do cadastro, e que ver a ida e a volta pede dois cadastros — porque conta de demonstração não transfere para fora do par.

- [ ] **Step 4: Atualizar o README**

Um parágrafo na seção que descreve o que o app faz: o e-mail é o primeiro consumidor do evento de transferência que vive fora do processo, e é por isso que ele tem fila própria com reentrega. Mencionar que as contas de demonstração não recebem e-mail, e por quê.

- [ ] **Step 5: Verificar que o frontend compila**

Run: `cd frontend && npm run build`
Expected: build sem erro.

- [ ] **Step 6: Rodar a suíte inteira**

Run: `cd backend && ./mvnw test`
Expected: tudo verde.

- [ ] **Step 7: Commit**

```bash
git add .env.example docker-compose.prod.yml frontend/src/screens/Vitrine.tsx README.md
git commit -m "docs: as variaveis do e-mail, e o que a tela de entrada passa a dizer"
```

---

## Depois do plano

Ligar em produção não é tarefa deste plano, porque depende de um passo que não é do implementador: o Gabriel verificar `bank.devfly.net` no Resend e gerar a chave. Com a chave em mãos, o deploy é o de sempre — `docker compose -f docker-compose.prod.yml -f docker-compose.proxy.yml --env-file .env.prod up -d --build` — com `BANCO_EMAIL_ENABLED=true` e `BANCO_EMAIL_API_KEY` no `.env.prod` do servidor, que não está no repositório.
