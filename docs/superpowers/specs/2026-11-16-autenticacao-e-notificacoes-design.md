# Autenticação e notificações — design

## Intenção e porquê

O projeto prova uma coisa a fundo: dinheiro se move entre contas sem se perder,
duplicar ou travar, e cada garantia tem um teste que a sustenta. Até aqui,
"sem autenticação" era um não-objetivo declarado, e isso era coerente: um demo
com duas contas semeadas não precisa saber quem é quem.

Este trabalho acrescenta usuários reais — cadastro, login e notificação — **sem
transformar o projeto num clone raso de aplicativo bancário**. A direção foi
decidida explicitamente: profundidade em vez de amplitude.

A consequência central não é uma tela a mais. Hoje **o cliente declara quem é**:
`GET /api/accounts` devolve todas as contas e `GET /api/notifications/stream`
recebe `accountId` como parâmetro de query. Qualquer um assina o fluxo de
qualquer conta. Autenticar é mover a identidade do cliente para o servidor, e a
transferência deixa de ser "da conta 1 para a 2" e passa a ser "da minha conta
para a de outro".

### O que o benchmark mostrou

Levantamento da Somapay (conta digital brasileira ligada a folha de pagamento),
feito no site do produto, no FAQ e na ficha da Play Store:

- O fluxo de entrada se chama **"Criar Acesso"**, não "criar conta". A conta já
  existe, criada pelo empregador; o usuário prova identidade para ganhar acesso.
  Cadastro e abertura de conta são etapas separadas.
- Existem **duas senhas**. A de login, de 6 dígitos, recuperável por código SMS;
  e a **senha transacional**, exigida para desbloquear cartão e sacar.
  Autenticar não é autorizar.
- O app tem 3,8 estrelas em 29,6 mil avaliações. As duas críticas mais votadas
  não são sobre falta de recurso: são **acesso falhando no dia do pagamento**
  (163 marcações de "útil") e **erro de transferência sem mensagem inteligível**
  (156 marcações).

O segundo ponto é o que orienta este design. O que quebra num produto real desta
categoria é acesso sob pico e erro ilegível — não ausência de funcionalidade.

## Objetivos

1. A identidade passa a vir do servidor. `accountId` nunca mais chega do cliente.
2. Cadastro e login com hash lento e salgado, sessão revogável e expiração.
3. **Senha transacional separada da senha de login**, exigida para mover dinheiro.
4. Bloqueio progressivo e limite de tentativas, tanto no login quanto na senha
   transacional — é o modo de falha mais votado no benchmark.
5. Notificação que **sobrevive a mais de uma instância** do backend, encerrando a
   limitação hoje documentada no README.
6. Notificação que alcança o aplicativo **fechado**, via Web Push.
7. O demo continua acessível em um clique para quem não vai se cadastrar.
8. Erro de transferência com mensagem que diz o que aconteceu e o que fazer.

## Não-objetivos

- **Verificação real de identidade (KYC).** O modelo "Criar Acesso" da Somapay se
  apoia num vínculo empregatício que aqui não existe. Cadastro será aberto.
- **Pix, boleto, cartão, crédito, contracheque.** Amplitude, recusada de propósito.
- **Aplicativo nativo.** O front já é um PWA instalável; a decisão foi evoluí-lo.
- **Recuperação de senha por SMS ou e-mail.** Exigiria provedor externo e segredo
  em produção, sem acrescentar nada ao que o projeto se propõe a provar. Fica
  como limitação declarada, não como esquecimento.
- **Mais de uma conta por usuário.** A relação é 1:1 neste escopo.
- **Segundo fator.** Mesma razão da recuperação de senha.

## Anti-objetivos (fariam isso falhar mesmo funcionando)

- O projeto virar um app bancário raso e perder o que o distingue: provar o que
  afirma.
- Autenticação que parece certa mas guarda senha de forma reversível, compara em
  tempo variável, ou vaza existência de conta na mensagem de erro.
- Notificação que não chega **em silêncio** — a mesma classe de falha do
  buffering de SSE, em que o servidor está certo e só o cliente vê o problema.
- Um demo em que entrar exija cadastro. Visitante que não entra em segundos não
  entra.

## Restrições

- VPS compartilhada com outros projetos em produção. Todo container novo entra
  com teto de memória, e nenhum publica porta no host.
- O deploy fica atrás de um proxy que já existe na borda. O SSE precisa continuar
  passando sem bufferização.
- O repositório é **público**: nenhum segredo versionado, e o demo é alcançável
  por qualquer um, sem dinheiro real.
- Cada garantia nova precisa de teste, no mesmo padrão do que já existe.
- Web Push no iOS só funciona com o PWA instalado na tela inicial (16.4+). No
  Android funciona com o app apenas aberto no navegador.

## Abordagens consideradas

As três diferem no ponto que mais importa: **como uma notificação alcança um
usuário conectado em outra instância do backend**.

**1. Tudo no Postgres.** Sessões em tabela, `LISTEN/NOTIFY` do Postgres como
sinal entre instâncias, assinaturas de push em tabela. Nenhum serviço novo. O
`LISTEN/NOTIFY` não é durável e tem carga limitada a 8 kB, mas isso não machuca:
o registro durável já é a tabela de outbox, e o `NOTIFY` seria só a campainha.

**2. Redis no caminho.** Sessões e pub/sub no Redis. Arranjo convencional, que
escala melhor em volume alto. Custo: um segundo serviço com estado para operar,
limitar, fazer backup e monitorar.

**3. Stateless com JWT.** Token curto com refresh, notificação só por Web Push.
Menos peças, mas perde o tempo real com o app aberto e torna revogação difícil —
"sair de todos os dispositivos" deixa de ser trivial, o que num app de banco é
esperado.

**Escolhida: a 2 (Redis).**

A recomendação de quem desenhou era a 1, pelo argumento de coerência: o projeto
existe para mostrar que o Postgres já entrega garantias que se costuma buscar em
infraestrutura extra, e aplicar o mesmo raciocínio à notificação seria mais
defensável do que acrescentar um serviço. A decisão foi pela 2, e é o que vale.

O que essa escolha custa, registrado para não virar surpresa: um container com
estado a mais na VPS; persistência do Redis precisa ser configurada, senão um
restart desloga todo mundo; e o backup passa a ter duas fontes em vez de uma.
O que ela ganha: um caminho que não depende de o backend manter conexão dedicada
de `LISTEN` por instância, e que é o que se encontra na maioria das equipes.

## Decisões de arquitetura

### Identidade e sessão

- Hash de senha com **Argon2id**. Spring Security já traz o encoder.
- Sessão como **token opaco** em cookie `HttpOnly`, `Secure`, `SameSite=Lax`,
  guardado no Redis com TTL. Opaco e no servidor porque revogar precisa ser
  possível: sair de um dispositivo apaga uma chave, sair de todos apaga o
  conjunto indexado por usuário.
- Nada de JWT para sessão. O projeto precisa de revogação, e revogação é
  exatamente o que o JWT não dá sem reintroduzir estado.

### Autorizar não é autenticar

- A conta ganha uma **senha transacional**, com hash próprio, separado.
- `POST /api/transfers` passa a exigi-la. Estar logado não basta.
- Contador de tentativas e bloqueio próprios, independentes do login.

### Limite de tentativas

- Progressivo, por identidade e por IP, nas duas senhas.
- Resposta de login **indistinguível** entre "usuário não existe" e "senha
  errada", inclusive no tempo de resposta.

### Notificação

- O registro durável continua sendo a **tabela de outbox**. Nada no Redis é
  fonte de verdade.
- Cada instância assina um canal do Redis. Quem processa a transferência publica;
  quem tem o assinante de SSE em memória entrega.
- **Web Push** para o app fechado, com chaves VAPID. As assinaturas ficam no
  **Postgres**, não no Redis: são duráveis e não podem sumir num flush.
- O disparo de push sai do drenador do outbox, não do caminho da transferência.
  Transferência não pode ficar mais lenta nem falhar por causa de notificação.

### Infraestrutura

- Redis entra no compose com teto de memória, sem porta publicada, com
  persistência ligada e senha definida por variável de ambiente.
- Nome de projeto do compose continua isolando a stack das outras da VPS.

### Como se indica o destinatário

Por **número da conta**, digitado. Não existe diretório nem endpoint de busca —
logo não existe superfície para descobrir quem está cadastrado, o que é coerente
com o anti-objetivo de não vazar existência de conta. É também o que a Somapay
faz: transferência entre contas da plataforma.

`GET /api/accounts` deixa de listar todas as contas. Passa a devolver apenas a
conta do usuário autenticado.

### O demo

- Continuam existindo **duas contas de demonstração**, as mesmas semeadas hoje.
  A entrada de visitante deixa escolher em qual entrar.
- Em duas abas ou dois aparelhos, a notificação atravessa de uma pessoa para a
  outra de verdade. A demonstração passa a ser real, não encenada — e é o que
  substitui o botão "trocar de conta", que morre junto com a identidade vinda
  do cliente.
- Conta de demonstração só transfere para outra conta de demonstração. Sem essa
  regra o dinheiro escapa do par e o reset deixa de fechar.
- Cadastro novo nasce com saldo inicial de demonstração, para que quem se
  cadastra tenha o que mover sem depender de ninguém.
- O `reset-demo.sh` deixa de truncar: restaura as duas contas de demonstração
  pelo lançamento compensatório descrito adiante, e não toca em quem se
  cadastrou.

### Quantas instâncias, e onde

Produção continua com **uma instância**: a §9 do runbook permanece válida e a
VPS é compartilhada com outros projetos. A garantia de que a notificação
atravessa instâncias é provada **em teste**, subindo duas instâncias contra o
mesmo Redis e o mesmo Postgres e exigindo que um evento publicado por uma chegue
a um assinante conectado na outra.

É o teste que prova. Produção não precisa pagar o custo de uma topologia que
existe para demonstrar uma propriedade.

## Decisões de quem desenhou (confira enquanto lê)

Pontos levantados pela revisão e resolvidos sem consulta, por serem decisões de
construção e não de produto:

1. **Senha transacional no cadastro e na demo.** As duas senhas são definidas no
   cadastro. A tela de demonstração **mostra** a senha transacional da conta de
   demonstração em vez de dispensá-la. Não existe caminho de exceção para sessão
   de visitante: um bypass seria uma brecha que precisaria de teste próprio e
   que, mais cedo ou mais tarde, alguém acharia em produção.

2. **Contas já semeadas.** A migração acrescenta o dono como coluna anulável e
   faz backfill: as contas 1 e 2 viram as de demonstração. O histórico de
   produção é preservado. A credencial delas vem de variável de ambiente, nunca
   versionada — o repositório é público.

3. **De onde vem o IP.** Com dois proxies na frente, o backend só pode confiar
   em `X-Forwarded-For` vindo de uma lista de proxies conhecidos. Se o cabeçalho
   não for confiável, **todo visitante compartilha um IP** e um único atacante
   bloquearia todo mundo de uma vez — exatamente o modo de falha mais votado no
   benchmark. Por isso o bloqueio por identidade é o primário, e o por IP é
   secundário e desligável. Isso precisa ser **verificado contra o proxy real**,
   não presumido.

4. **Chaves VAPID.** A pública entra no bundle como argumento de build do Vite,
   via `build.args` do compose; a privada fica em variável de ambiente.
   Desenvolvimento e produção usam pares distintos. A pública não é versionada:
   é pública por definição, mas rotacioná-la invalida todas as assinaturas já
   gravadas, e isso é operação, não constante de código.

5. **Drenador do outbox com mais de uma instância.** Passa a usar
   `FOR UPDATE SKIP LOCKED`. Sem isso, duas instâncias disputam as mesmas
   linhas: a segunda espera, estoura o `lock_timeout` de 3 s e devolve 500 sem
   corpo — e o mesmo push sai duas vezes. O envio de push também passa a ser
   idempotente pelo id do evento. `OutboxScheduledTest` continua valendo e ganha
   um teste de disputa entre instâncias.

6. **O reset não apaga razão.** Em vez de truncar entradas e transferências, o
   reset lança um **lançamento compensatório** entre as duas contas de
   demonstração. Razão de dupla entrada não se corrige apagando linha, se
   corrige com contrapartida — e o histórico de quem se cadastrou nunca é
   tocado. É a diferença entre restaurar um demo e mutilar o livro que o projeto
   declara fonte da verdade.

## O que fica a cargo de quem constrói

Formato exato das tabelas e dos DTOs, configuração do Spring Security, desenho
das rotas protegidas no front, textos de erro, e a política numérica do bloqueio
progressivo (quantas tentativas, quanto tempo).

## Risco conhecido

O ponto mais provável de falha silenciosa é a notificação: agora são três saltos
(publicação no Redis, entrega por SSE, entrega por push) e nenhum deles avisa
quando não acontece. A verificação precisa provar entrega com duas instâncias de
backend no ar — em teste, já que produção segue com uma. Um teste que suba só
uma instância não prova nada sobre o problema que o Redis entrou para resolver.
