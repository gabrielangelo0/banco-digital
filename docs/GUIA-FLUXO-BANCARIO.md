# Guia do fluxo bancário — as quatro camadas de consistência

> Leitura de ~20 minutos. Cada seção termina com a pergunta de entrevista
> que ela responde. A última seção é uma colinha de 2 minutos para reler
> antes de entrar.

---

## 0. O mapa: o que acontece quando alguém aperta "Transferir"

```
  App                API                      Banco de dados
   │                  │                              │
   │ POST /transfers  │                              │
   │ Idempotency-Key ─┤                              │
   │                  │                              │
   │                  │ 1. a chave já foi usada? ───▶│
   │                  │◀── sim ──▶ devolve a resposta antiga, FIM
   │                  │                              │
   │                  │ ══════ BEGIN TRANSACTION ════│
   │                  │ 2. trava conta MENOR id ────▶│  SELECT ... FOR UPDATE
   │                  │ 3. trava conta MAIOR id ────▶│  SELECT ... FOR UPDATE
   │                  │ 4. tem saldo? ───────────────│
   │                  │ 5. grava 2 lançamentos ─────▶│  −100 e +100
   │                  │ 6. atualiza os 2 saldos ────▶│
   │                  │ 7. grava a transferência ───▶│
   │                  │ 8. grava a chave usada ─────▶│
   │                  │ 9. grava o evento (outbox) ─▶│
   │                  │ ═══════ COMMIT ══════════════│
   │                  │                              │
   │◀── 200 OK ───────│                              │
                      │ (depois, assíncrono)         │
                      │ 10. publisher lê o outbox e notifica
```

Os passos 2 a 9 são **tudo ou nada**. Os passos 1, 2–3 e 10 são,
respectivamente, as camadas de idempotência, concorrência e consistência
eventual. Guarde esse desenho — dá pra reproduzi-lo num quadro branco.

---

## 1. O razão de dupla entrada

**A ideia:** saldo não é um número que você edita. Saldo é a **soma de um
histórico de lançamentos**.

Toda movimentação de dinheiro cria **dois lançamentos que somam zero**:

| conta | valor | direção |
|---|---|---|
| Gabriel | −100,00 | DEBIT |
| Maria | +100,00 | CREDIT |

Some os dois: zero. Essa é a lei. Dinheiro nunca nasce nem morre dentro do
sistema — só muda de lugar. Se a soma de **todos** os lançamentos do banco
não der zero, você tem um bug, e dá pra detectar isso com uma única query.

**Por que não simplesmente `UPDATE contas SET saldo = saldo - 100`?**

Porque um saldo editável é um número sem procedência. Quando ele estiver
errado — e um dia estará — você não tem como saber *por quê*. Com o razão,
o saldo é derivável: se ele divergir da soma dos lançamentos, você sabe na
hora, e o histórico te diz onde quebrou.

**A concessão prática.** Somar o histórico inteiro a cada consulta de saldo
fica caro quando a conta tem 10 anos de movimento. Então mantemos as duas
coisas: a tabela `entries` é a verdade, e `accounts.balance` é um **saldo
materializado**, escrito na mesma transação. E existe um teste de
reconciliação afirmando que os dois concordam. Esse teste é o que impede a
otimização de virar mentira.

> **Pergunta de entrevista:** *"Como você modelaria o saldo de uma conta?"*
> A resposta fraca é "um campo decimal na tabela de contas". A resposta
> forte é "um razão de lançamentos como fonte da verdade, com saldo
> materializado para leitura e reconciliação testada entre os dois".

---

## 2. Camada 1 — Atomicidade

**O sintoma que ela previne:** o dinheiro sai de uma conta e não entra na
outra.

**O mecanismo:** débito e crédito acontecem dentro da **mesma transação de
banco de dados**. Em Spring, um `@Transactional` no método de serviço. Se
qualquer coisa estourar no meio — exceção, queda do processo, falta de
saldo descoberta no passo 4 — o banco desfaz tudo. Não existe estado
intermediário visível.

```java
@Transactional
public TransferResult execute(TransferCommand cmd) {
    // débito, crédito, lançamentos, transferência, outbox.
    // Ou tudo isso vira realidade junto, ou nada disso aconteceu.
}
```

**O detalhe que separa júnior de sênior:** `@Transactional` do Spring é
implementado com proxy. Se você chamar o método a partir de **outro método
da mesma classe**, o proxy não é atravessado e a transação **não abre** —
seu código roda sem transação nenhuma, em silêncio. Essa é a pegadinha
clássica. Também: por padrão, Spring só faz rollback em `RuntimeException`,
não em exceção checada.

> **Pergunta de entrevista:** *"E se o sistema cair no meio da
> transferência?"* Resposta: o banco desfaz a transação não commitada; o
> cliente recebe erro ou timeout e repete com a mesma chave de
> idempotência. Nunca existe meio-caminho persistido.

---

## 3. Camada 2 — Concorrência: o *lost update*

**O sintoma que ela previne:** duas operações simultâneas, e uma apaga o
efeito da outra. Dinheiro sendo criado ou destruído do nada.

Conta com R$ 1.000. Duas transferências de R$ 100 chegam no mesmo instante:

```
tempo    Thread A                      Thread B
  1      lê saldo → 1000
  2                                    lê saldo → 1000
  3      calcula 1000 − 100 = 900
  4                                    calcula 1000 − 100 = 900
  5      grava 900
  6                                    grava 900   ← sobrescreve
```

Saíram R$ 200 da conta. O saldo diz R$ 900. **R$ 100 apareceram do nada.**
Isso é o *lost update*, e é o bug mais comum em sistema financeiro mal
feito. Note que a atomicidade não te salva: as duas transações commitaram
com sucesso.

**O mecanismo:** `SELECT ... FOR UPDATE`. Ele lê **e trava** a linha. A
thread B fica bloqueada no passo 2 até A commitar, e então lê 900, não 1000.

```sql
SELECT * FROM accounts WHERE id = ? FOR UPDATE;
```

Em Spring Data JPA:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT a FROM Account a WHERE a.id = :id")
Optional<Account> findByIdForUpdate(@Param("id") Long id);
```

**Como eu provo que funciona:** `TransferConcurrencyTest` dispara 100
threads transferindo R$ 1,00 da mesma conta ao mesmo tempo, com
`CountDownLatch` para largada simultânea, e afirma que o saldo final é
exatamente R$ 900,00. Sem o lock, esse teste falha.

> **Pergunta de entrevista:** *"Duas transferências da mesma conta chegam
> ao mesmo tempo. O que acontece?"* Se você responder só "a transação
> garante", está errado — atomicidade e isolamento são coisas diferentes.
> A resposta é o lock pessimista, ou versionamento otimista.

---

## 4. Deadlock — e por que ordenar os locks resolve

**O sintoma:** duas transações se travam mutuamente e nenhuma anda. O
Postgres detecta depois de um tempo e **mata uma delas**.

Gabriel (id 1) e Maria (id 2). Duas transferências cruzadas, ao mesmo tempo:

```
tempo    Thread A (1 → 2)              Thread B (2 → 1)
  1      trava conta 1  ✓
  2                                    trava conta 2  ✓
  3      quer travar a 2 … espera B
  4                                    quer travar a 1 … espera A
         ───────────── abraço mortal ─────────────
```

Cada uma segura o que a outra quer. Ninguém solta. Isso é deadlock.

**O mecanismo:** **sempre travar na mesma ordem global.** Ordene os ids e
trave o menor primeiro, não importa quem é origem e quem é destino:

```java
Long primeiro = Math.min(cmd.sourceAccountId(), cmd.targetAccountId());
Long segundo  = Math.max(cmd.sourceAccountId(), cmd.targetAccountId());

Account a = repo.findByIdForUpdate(primeiro).orElseThrow(...);
Account b = repo.findByIdForUpdate(segundo).orElseThrow(...);
```

Agora a mesma cena vira:

```
tempo    Thread A (1 → 2)              Thread B (2 → 1)
  1      trava conta 1  ✓
  2                                    quer a conta 1 … espera  ← ordenada!
  3      trava conta 2  ✓
  4      commit, solta tudo
  5                                    trava 1, trava 2, segue
```

B espera um instante em vez de morrer. **Deadlock exige um ciclo de
espera; uma ordem total elimina a possibilidade do ciclo.** Não é
mitigação, é impossibilidade estrutural.

**Complementos que um sênior menciona:**

- `lock_timeout` configurado. Esperar para sempre é pior que falhar rápido.
- Mantenha a transação **curta**. Nunca chame API externa com lock na mão.
- Deadlock não some com retry — retry é remendo. A ordenação é a cura.

**Como eu provo:** `transferenciasCruzadasNaoCausamDeadlock` dispara 50
threads A→B e 50 B→A ao mesmo tempo e afirma que as 100 completam sem
`DeadlockLoserDataAccessException`, e que a soma dos dois saldos é a mesma
do início.

> **Pergunta de entrevista:** *"Como você evita deadlock em transferência?"*
> Essa é a pergunta que o seu projeto inteiro existe para responder. A
> resposta é uma frase: **ordem total de aquisição de locks**.

---

## 5. Lock pessimista × otimista

| | Pessimista (`FOR UPDATE`) | Otimista (`@Version`) |
|---|---|---|
| Como funciona | trava a linha, os outros esperam | ninguém trava; no `UPDATE` confere se a versão mudou |
| Em conflito | serializa, todo mundo passa | a segunda leva exceção e precisa refazer |
| Bom quando | disputa alta, conflito é esperado | disputa baixa, conflito é raro |
| Custo | contenção, risco de deadlock | retrabalho, complexidade de retry |

**Como o otimista funciona na prática:** a linha tem uma coluna `version`.
O `UPDATE` vira `... WHERE id = ? AND version = ?`, e incrementa. Se outra
transação já mexeu, a versão mudou, o `UPDATE` afeta zero linhas e o JPA
lança `OptimisticLockException`.

**Meu critério:** conta corrente movimentada tem disputa alta — duas
operações na mesma conta é o caso normal, não a exceção. Então escolhi
**pessimista**, que serializa em vez de fazer todo mundo refazer trabalho.
A coluna `version` existe no schema e fica inerte sob o lock pessimista:
está ali de propósito, para eu conseguir mostrar as duas estratégias lado a
lado.

**Onde o otimista brilharia aqui:** atualizar o perfil do cliente, o limite
do cartão, preferências — coisas que raramente colidem.

> **Pergunta de entrevista:** *"Pessimista ou otimista?"* A resposta errada
> é ter uma favorita. A certa é o critério: **probabilidade de conflito**.

---

## 6. Camada 3 — Idempotência

**O sintoma que ela previne:** o dinheiro vai duas vezes.

E repare: **nenhum lock do mundo previne isso.** O celular manda a
requisição, perde o sinal antes de receber a resposta, o app tenta de novo.
Chegaram duas requisições legítimas, bem formadas, uma depois da outra. O
lock faz exatamente o que deve: serializa as duas e executa **as duas**.

Lock resolve *simultaneidade*. Idempotência resolve *repetição*. São
problemas diferentes, e confundi-los é escorregão clássico de entrevista.

**O mecanismo:** o cliente gera um identificador único **por intenção** e
manda no header:

```
POST /api/transfers
Idempotency-Key: 9f1c3e7a-5b2d-4e8f-a1c6-0d3b9e4f7a21
```

No servidor, uma tabela com `PRIMARY KEY (key)`. Grava a chave **na mesma
transação** da transferência. Se a chave já existe, não executa de novo:
**devolve a resposta original**. Repare que o certo é devolver 200 com o
mesmo resultado, não um erro — do ponto de vista do app, a operação deu
certo, e deu mesmo.

**O detalhe fino:** e se as duas chegarem *ao mesmo tempo*, antes de
qualquer uma commitar? Um `SELECT` não vê a outra. Quem resolve é a
**unique constraint**: a segunda leva `DataIntegrityViolationException` no
commit, e aí você captura, relê a chave e devolve a resposta gravada. A
garantia mora no banco, não no `if` do seu código.

**O detalhe do cliente:** a chave tem que ser gerada **uma vez por intenção
do usuário**, não por clique do botão. Se o botão "tentar de novo" gerar
uma chave nova, você acabou de reinventar o problema.

> **Pergunta de entrevista:** *"O cliente perdeu a conexão e repetiu a
> requisição. Como você evita cobrar duas vezes?"* Se a resposta mencionar
> lock, está errada.

---

## 7. Camada 4 — Consistência eventual e o padrão Outbox

**O sintoma que ela previne:** o dinheiro saiu daqui e não chegou lá — mas
agora "lá" é **outro banco**, e você não tem transação que abrace os dois.

Dentro do seu Postgres, `@Transactional` resolve tudo. Entre dois sistemas
diferentes, não existe commit compartilhado. (Existiu: two-phase commit,
XA. Na prática ninguém usa em escala — trava recurso dos dois lados
enquanto a rede decide, e se o coordenador cai, todo mundo fica pendurado.)

**O problema concreto:** você precisa gravar a transferência **e** avisar
alguém — publicar num Kafka, chamar o SPI do Banco Central, notificar o
app. Duas coisas, dois sistemas:

- Commita e **depois** publica? Se o processo morre no meio, a
  transferência existe e ninguém foi avisado.
- Publica e **depois** commita? Se o commit falha, você avisou sobre uma
  transferência que não existe. Pior ainda.

**O mecanismo — Outbox:** grave o evento numa tabela **do seu próprio
banco**, dentro da mesma transação da transferência.

```
BEGIN
  ... lançamentos, saldos, transferência ...
  INSERT INTO outbox_events (event_type, payload, published)
       VALUES ('TRANSFER_COMPLETED', '{...}', false);
COMMIT
```

Agora é uma transação só, e o problema sumiu: ou os dois existem, ou
nenhum. Um publisher separado varre a tabela, publica e marca como
`published`. Se ele cair, tenta de novo quando voltar — o evento está
guardado, seguro.

**A consequência que você precisa saber dizer:** isso te dá entrega
**pelo menos uma vez**, não exatamente uma vez. O publisher pode publicar e
morrer antes de marcar. Por isso o consumidor do evento também precisa ser
idempotente. "Exactly-once" de ponta a ponta é essencialmente marketing —
o que existe é *at-least-once* com consumidor idempotente.

**Compensação.** Quando a etapa remota falha de verdade, você não desfaz
com rollback — você emite um **lançamento de estorno**. É mais uma entrada
no razão, não um apagão do histórico. Isso é o padrão Saga, e é também por
isso que o razão nunca é editado: a correção de um erro é um novo
lançamento.

> **Pergunta de entrevista:** *"Como você garante que a transferência e a
> publicação do evento acontecem juntas?"* A palavra que eles querem ouvir
> é **outbox**. E o ponto de bônus é você mencionar at-least-once.

---

## 8. Dinheiro não é `double`

```java
System.out.println(0.1 + 0.2);        // 0.30000000000000004
System.out.println(1.03 - 0.42);      // 0.6100000000000001
```

`double` é ponto flutuante binário. Ele **não consegue representar** 0,1
exatamente, do mesmo jeito que decimal não representa 1/3. Em dinheiro,
esse erro acumula e vira divergência de centavos — que em banco é
incidente, não arredondamento.

**O que usar:** `BigDecimal` em Java, `NUMERIC(19,4)` no Postgres. Nunca
`float`, `double`, nem `FLOAT`/`REAL` na coluna.

**Três pegadinhas de `BigDecimal`:**

1. Use `compareTo()`, não `equals()`. `equals` compara escala também:
   `new BigDecimal("1.0").equals(new BigDecimal("1.00"))` é **false**.
   `compareTo` dá zero, que é o que você quer.
2. Construa a partir de `String`, não de `double`.
   `new BigDecimal(0.1)` carrega o lixo binário junto.
3. Divisão exige `RoundingMode` explícito, senão `ArithmeticException` em
   dízima. E a regra de arredondamento é decisão de negócio, não técnica.

**Por que 4 casas e não 2:** cálculos intermediários — juros, câmbio, taxas
proporcionais — precisam de casas a mais antes do arredondamento final.
Guardar com 4 e arredondar na apresentação evita erro sistemático.

> **Pergunta de entrevista:** *"Que tipo você usa para valor monetário?"*
> É pergunta de eliminação. Responder `double` encerra a conversa.

---

## 9. Colinha de 2 minutos

| Camada | Previne | Mecanismo | Frase-chave |
|---|---|---|---|
| 1. Atomicidade | saiu e não entrou | `@Transactional` | "tudo ou nada no commit" |
| 2. Concorrência | *lost update*, deadlock | `SELECT FOR UPDATE` **ordenado por id** | "ordem total de aquisição de locks" |
| 3. Idempotência | foi duas vezes | chave única na mesma transação | "lock resolve simultaneidade, chave resolve repetição" |
| 4. Eventual | não chegou no outro banco | **Outbox** + compensação | "at-least-once com consumidor idempotente" |

**As cinco frases que valem a entrevista inteira:**

1. "Saldo é a soma do razão; o campo é só materialização, com teste de
   reconciliação."
2. "Deadlock precisa de um ciclo de espera. Travo sempre na ordem
   crescente de id, então o ciclo não pode existir."
3. "Escolho pessimista aqui porque conta corrente tem disputa alta.
   Otimista seria melhor onde conflito é raro."
4. "Lock não resolve retry de cliente — isso é idempotência, e a garantia
   mora na unique constraint, não no `if`."
5. "Entre dois bancos não existe transação compartilhada, então gravo o
   evento no outbox dentro da mesma transação e publico depois."

**Se te perguntarem o que você faria diferente em produção:**
particionamento do razão por período, snapshot periódico de saldo, limite
de taxa por conta, trilha de auditoria imutável, e reconciliação diária
batendo o razão contra o extrato do banco liquidante.
