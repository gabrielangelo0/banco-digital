# Banco digital — design

## Intenção e porquê

Estudo de consistência em transações bancárias. O objetivo **não é ter um app
grande** — é demonstrar, com testes que provam, como dinheiro se move entre
contas sem se perder, duplicar ou travar, e conseguir explicar cada garantia.

O cenário concreto que o projeto ataca: o dinheiro sai de uma conta, algo falha
no meio do caminho, e o valor fica inconsistente entre origem e destino.

## Objetivos

1. Demonstrar em código executável as quatro camadas de defesa do fluxo de
   transferência: **atomicidade, concorrência, idempotência, consistência eventual**.
2. Testes que **provam** as garantias — não que apenas exercitam o caminho feliz.
3. Um guia de leitura que se absorva em ~20 minutos e sirva de
   referência do projeto.
4. PWA React com visual inspirado em bancos digitais.

## Não-objetivos

- Autenticação robusta, KYC, compliance regulatório.
- Integração real com Pix/SPI ou qualquer banco externo.
- Microsserviços, Kafka, event sourcing completo.
- Cobertura ampla de testes — os testes aqui são argumentativos, não exaustivos.

## Anti-objetivos (fariam isso falhar mesmo funcionando)

- Tela bonita sobre um motor frágil.
- Não conseguir explicar o próprio código.
- App que não sobe na hora de mostrar.

## Restrições

- **Escopo deliberadamente enxuto.** Profundidade no motor de transferência vale
  mais aqui do que cobertura de funcionalidades: uma operação, feita com rigor.
- Java 21, Node 20, Docker. Maven via Maven Wrapper, sem instalação global.
- Projeto do zero, sem código legado.

## Abordagens consideradas

| | O que era | Troca |
|---|---|---|
| **A** | Só o motor de transferência, bem fundo, sem frontend | Máxima profundidade; nada visual pra mostrar |
| **B** ✅ | Motor + testes + guia primeiro; PWA depois | Visual mais simples, mas o risco fica contido no que importa |
| **C** | Só um documento de estudo denso, sem app | Mais tempo de leitura; não gera software |

**Escolhida: B.** Razão: construir o motor primeiro garante que, se o escopo
precisar encolher, o que já está pronto é justamente a parte que carrega o valor
do projeto. A ordem é a mitigação de risco.

## Decisões de arquitetura

### Modelo de dados — razão de dupla entrada + saldo materializado

Tabelas: `accounts` (com `balance` e `version`), `entries` (o livro-razão, duas
linhas por transferência: débito e crédito), `transfers`, `idempotency_keys`,
`outbox_events`.

Toda movimentação gera **dois lançamentos que somam zero**. O saldo vive em
`accounts.balance`, atualizado na mesma transação que insere os lançamentos, e
existe um teste de reconciliação que afirma `accounts.balance == SUM(entries)`.

*Por quê:* o ledger dá auditoria e rastreabilidade de graça, e é como bancos
reais funcionam. O saldo materializado evita agregar o histórico inteiro a cada
leitura. A reconciliação é o que impede os dois divergirem — e é um ótimo ponto
de conversa na entrevista.

### Concorrência e ausência de deadlock

- `SELECT ... FOR UPDATE` nas duas contas, **sempre ordenadas pelo id (menor
  primeiro)**. Ordem global determinística é o que elimina o abraço mortal.
- Teste que **provoca** o deadlock: duas threads transferindo A→B e B→A ao mesmo
  tempo. Sem ordenação, uma morre; com ordenação, as duas passam.
- Teste de *lost update*: N transferências simultâneas na mesma conta; o saldo
  final tem que bater exatamente.
- `lock_timeout` configurado — travar para sempre é pior que falhar rápido.
- Isolamento READ COMMITTED (padrão do Postgres) basta, dado o `FOR UPDATE`.
- A coluna `version` fica disponível como demonstração da alternativa otimista,
  documentada no guia com o critério de quando usar cada uma.

### Idempotência

- Header `Idempotency-Key` obrigatório no `POST /transfers`.
- Unique constraint na tabela; a segunda chamada com a mesma chave **devolve a
  resposta original** (replay), não um erro.
- *Por quê:* lock nenhum resolve retry de cliente. São problemas diferentes, e
  confundir os dois é erro comum em entrevista.

### Consistência eventual — padrão Outbox

Evento `TransferCompleted` gravado em `outbox_events` **na mesma transação** da
transferência. Um publisher lê e emite. Serve a notificação em tempo real e
demonstra o padrão que resolveria a transferência interbancária, onde não existe
uma transação só abraçando os dois bancos.

### Observabilidade

Logback com saída JSON (`logstash-logback-encoder`), `correlationId` por
requisição via filtro e MDC, log de auditoria dedicado por transferência.

### Frontend

React + Vite + TypeScript + Tailwind, PWA via `vite-plugin-pwa`. Telas: home com
cartão de saldo, extrato, transferência, e notificação chegando por SSE. Paleta
Nubank (roxo `#820AD1`).

### Estrutura

Monorepo: `backend/`, `frontend/`, `docs/`.

## O que fica a cargo de quem constrói

Nomes de pacotes e classes, formato exato dos DTOs, polimento visual, mensagens
de erro. Nada disso muda o que está decidido acima.

## Risco conhecido

Testcontainers baixa a imagem do Postgres na primeira execução (~1 min).
Mitigação: subir o `docker compose` antes, e os testes reaproveitam a imagem já
baixada.
