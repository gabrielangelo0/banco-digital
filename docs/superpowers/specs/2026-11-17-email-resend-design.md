# E-mail transacional com Resend

**Data:** 2026-11-17
**Estado:** aguardando aprovação do Gabriel

---

## Intenção

Quando uma transferência fecha, as duas pontas devem receber um e-mail com o
comprovante. Quem se cadastra deve receber um e-mail de boas-vindas na hora.

O porquê não é "o banco precisa mandar e-mail". É que este projeto existe para
demonstrar que dinheiro se move sem se perder, duplicar ou travar, e o e-mail é
o primeiro consumidor do evento de transferência que vive **fora** do processo.
A notificação por SSE desaparece se o navegador estiver fechado; o e-mail não.
Um provedor externo, que cai e devolve erro, é onde o padrão de caixa de saída
deixa de ser enfeite e passa a pagar o aluguel.

## Objetivos

1. Quem envia recebe um comprovante; quem recebe recebe o mesmo comprovante com
   o texto virado.
2. Quem se cadastra recebe um e-mail de boas-vindas.
3. O envio é auditável: dá para responder "esse e-mail saiu? quando? com qual
   identificador no provedor?" consultando uma tabela.
4. Falha do provedor não derruba a transferência, não derruba a notificação na
   tela e não some — é tentada de novo.
5. A mesma transferência nunca gera dois comprovantes, mesmo com o evento sendo
   entregue duas vezes.

## Não-objetivos

- **PDF anexo.** O comprovante é o corpo do e-mail. Anexo entra depois, se
  pedirem.
- **Confirmação de e-mail no cadastro.** Ninguém precisa clicar em link para a
  conta funcionar. O e-mail de boas-vindas é aviso, não portão.
- **Preferências de notificação / descadastro.** São dois e-mails transacionais,
  disparados por ato da própria pessoa. O rodapé diz por que ela recebeu.
- **Tela de administração da fila.** A tabela é consultada por SQL.
- **Recuperação de senha por e-mail.** Outro assunto, outra conversa.

## Anti-objetivos

O que tornaria isto um fracasso mesmo funcionando:

- **Mandar e-mail para as contas de demonstração.** Elas têm endereço falso
  (`demo-1@banco.local`). Cada tentativa é uma rejeição definitiva, e rejeição
  definitiva queima a reputação do domínio que manda. O reset horário passa pelo
  `TransferService` igual a qualquer transferência: sem a trava, o servidor
  manda dois e-mails inválidos por hora, para sempre.
- **Comprovante repetido.** A entrega da caixa de saída é "pelo menos uma vez".
  Um segundo comprovante da mesma transferência faz quem lê duvidar do saldo.
- **A chave da API no repositório.** O repositório é público.
- **A transferência esperar a rede.** Nenhuma chamada HTTP externa dentro da
  transação que segura a linha da conta.

## Restrições

- **Repositório público.** Credencial só por variável de ambiente, como
  `BANCO_DEMO_*` já faz.
- **Resend:** `POST https://api.resend.com/emails`, cabeçalhos `Authorization:
  Bearer <chave>`, `Content-Type: application/json` e **`User-Agent`
  obrigatório** — sem ele a resposta é 403, e cliente HTTP de Java nem sempre
  manda um. Corpo exige `from`, `to` e `subject`; `html` e `text` são opcionais.
  Sucesso devolve `{"id": "..."}`. Limite de **10 requisições por segundo na
  conta inteira**, 429 quando estoura.
- **`Idempotency-Key`:** até 256 caracteres, guardada por 24 h. Mesma chave com
  o mesmo corpo devolve a mesma resposta sem reenviar; com corpo diferente dá
  409.
- **Domínio verificado.** Sem ele o Resend só entrega para o e-mail da própria
  conta. Os registros DNS são passo do Gabriel, fora deste desenho.
- **Sem dependência nova.** O `RestClient` do Spring já vem no que está no
  `pom.xml`.
- **Deploy:** VPS da devfly, `docker compose -f docker-compose.prod.yml -f
  docker-compose.proxy.yml --env-file .env.prod`. Os dois arquivos, sempre.

## Abordagens consideradas

**1. Mandar direto do drenador.** O `OutboxDrainer` chama o Resend junto com o
SSE, na mesma transação. Menos peças, nenhuma tabela nova. Descartada: coloca
uma chamada de rede dentro de uma transação de banco, segurando a conexão do
pool; e a falha do provedor reverte o lote inteiro, reentregando o SSE junto.

**2. Caixa de saída própria para e-mail. — ESCOLHIDA.** O drenador continua só
com SSE e, ao tratar o evento, grava linhas numa tabela de mensagens. Um segundo
agendador entrega fora da transação de negócio. Mais peças, e em troca: a
transferência nunca espera a rede, a falha do provedor fica contida, e sobra um
registro do que saiu.

**3. SMTP com Spring Mail.** O Resend aceita SMTP, mas troca o identificador da
mensagem, a chave de idempotência e os erros estruturados por um envelope e
silêncio. Descartada.

O Gabriel escolheu a 2.

## Desenho

### A tabela

`email_messages`, criada por migração nova:

| coluna | o quê |
|---|---|
| `id` | identidade |
| `to_address` | destinatário |
| `subject` | assunto |
| `body_html`, `body_text` | as duas versões do corpo |
| `idempotency_key` | **índice único** |
| `status` | `PENDING`, `SENT`, `FAILED` |
| `attempts` | tentativas já feitas |
| `next_attempt_at` | quando pode tentar de novo |
| `provider_message_id` | o `id` devolvido pelo Resend |
| `last_error` | motivo da última falha |
| `created_at`, `sent_at` | quando nasceu e quando saiu |

**A trava contra duplicata é uma consulta pela chave antes de inserir, e não o
índice.** O índice fica como rede para corrida. A diferença importa: o drenador
é uma transação só sobre até cem eventos, e uma violação de unicidade envenena
essa transação inteira — o lote todo voltaria atrás, incluindo o `published` dos
outros noventa e nove, o SSE seria reentregue e o ciclo se repetiria a cada
segundo. Confiar no índice aqui causaria exatamente o que os anti-objetivos
proíbem. *(Decisão minha, depois da revisão.)*

A chave do Resend é a terceira trava, do lado de lá — ela existe porque dura
24 h, enquanto a nossa dura para sempre.

**Formato da chave**, legível de propósito:

- `transfer/<transferId>/DEBIT` e `transfer/<transferId>/CREDIT`
- `welcome/<userId>`

### Quem escreve na fila

**O drenador**, ao tratar `TRANSFER_COMPLETED`: resolve as duas contas, pula
cada ponta que for conta de demonstração (`DemoAccounts.ehDemonstracao`),
resolve `Account.ownerId → User.email` e enfileira uma mensagem por ponta que
sobrou. A escrita acontece na mesma transação do drenador, então o SSE e a
mensagem nascem juntos.

**O cadastro**, no `RegistrationService`, dentro da transação que já cria
usuário e conta.

Uma conta sem dono (`owner_id` nulo) não gera mensagem. Além da trava de
demonstração, qualquer endereço terminado em `.local` é recusado na escrita —
cinto e suspensório contra endereço sintético.

### Quem entrega

`EmailDispatcher`, agendador novo, separado do bean que tem a transação pelo
mesmo motivo que `OutboxPublisher` é separado de `OutboxDrainer`.

A cada ciclo pega até cinco mensagens `PENDING` com `next_attempt_at` vencido e
manda uma por vez. Cinco por ciclo fica muito abaixo das 10/s do Resend e não
precisa de controle de vazão.

**Não divide a linha de execução com o drenador.** Hoje todo `@Scheduled` do
projeto roda numa thread só; cinco chamadas HTTPS por ciclo na mesma thread
atrasariam a notificação na tela pelo tempo de rede do provedor, e uma conexão
pendurada travaria o drenador para sempre. Então: `spring.task.scheduling.pool-size: 2`
e **tempos-limite explícitos** no `RestClient` — 5 s para conectar, 10 s para
ler. O `RestClient` não põe nenhum por conta própria. *(Decisão minha, depois da
revisão.)*

- **Sucesso:** `status = SENT`, grava `sent_at` e `provider_message_id`.
- **Falha que passa** (429, 5xx, rede): `attempts++`, `next_attempt_at` com
  espera crescente, continua `PENDING`. Ao chegar ao teto de tentativas, vira
  `FAILED` com o motivo.
- **Falha que não passa** (4xx que não seja 429 — domínio não verificado,
  destinatário inválido): `FAILED` na hora. Insistir não conserta e só gasta
  reputação.

O agendador nunca deixa uma exceção subir: uma mensagem ruim não pode parar a
fila.

### O cliente

`RestClient` configurado com a URL base, `Authorization`, `Content-Type` e
`User-Agent: banco-digital/1.0`. A `Idempotency-Key` vai por mensagem. A URL
base é configurável para o teste apontar para um servidor de mentira.

A chave da API nunca aparece em log, nem em exceção.

### Os e-mails

Modelo **A · Documento**, escolhido entre três mostrados lado a lado: cabeçalho
com a marca e uma régua verde, o valor em destaque, e os campos rotulados — De,
Para, Quando, Identificador — numa tabela.

**Cada comprovante identifica pelo nome só o seu próprio dono; o outro lado
aparece apenas pelo número da conta.** No comprovante de quem enviou: "De:
Gabriel · conta 1 / Para: conta 7". No de quem recebeu: "De: conta 1 / Para:
Mariana · conta 7". A razão é que o app hoje não conta a ninguém quem é o dono
de uma conta — o extrato mostra só o identificador, a caixa de transferência
pede só um número, e não existe busca de contas justamente para não entregar
quem tem cadastro aqui. Além disso, o nome do titular é derivado do começo do
e-mail no cadastro (`RegistrationService.titular`), então exibi-lo devolveria um
pedaço do endereço de alguém a outra pessoa. O identificador já distingue a
transferência sem ambiguidade; o nome do outro lado não acrescentaria nada que
valha essa troca. Identificador em fonte monoespaçada,
com a frase que explica para que ele serve. Sóbrio, imprimível, e renderiza
igual em qualquer cliente.

Montado em Java, a partir de um bloco de texto com os valores interpolados e
escapados. Sem motor de template: são dois e-mails, e o `pom.xml` não tem
Thymeleaf.

HTML em tabelas, estilo embutido, sem imagem externa. Toda mensagem vai com
`text` além do `html`.

**O "Quando" sai de `transfers.created_at`, lido na hora de enfileirar** — não
de um campo novo no evento. Acrescentar campo mudaria o formato do payload, e os
eventos que já estão no `outbox_events` de produção voltariam com nulo ali.

**O horário é escrito em horário de Brasília, e o e-mail diz isso.** O servidor
grava em UTC e o extrato é formatado no fuso do navegador; um comprovante em UTC
ao lado de uma linha do extrato em UTC−3 é a mesma transferência com três horas
de desacordo. *(Decisões minhas, depois da revisão.)*

**O identificador vai inteiro**, não na forma curta que o extrato mostra: assim
dá para colar na busca do extrato, que filtra pelo identificador completo.

- **Comprovante enviado:** assunto `Comprovante de transferência — R$ 17,25`.
- **Comprovante recebido:** a mesma peça, "Transferência recebida", De e Para
  trocados — e o nome agora é o de quem recebeu, pela regra acima.
- **Boas-vindas:** mesma moldura, dizendo que a conta está aberta, com quanto
  ela começou, e que mover dinheiro pede a segunda senha.

Todo rodapé diz por que a pessoa recebeu aquilo e que é um projeto de estudo
onde o dinheiro não é real.

### Configuração

| variável | para quê |
|---|---|
| `BANCO_EMAIL_ENABLED` | liga a **entrega**. Falso por padrão |
| `BANCO_EMAIL_API_KEY` | a chave do Resend |
| `BANCO_EMAIL_FROM` | remetente, ex. `Banco <banco@bank.devfly.net>` |
| `BANCO_EMAIL_BASE_URL` | URL do provedor; só os testes mudam |

**A fila sempre é escrita; a chave liga apenas quem entrega** — espelhando o
`banco.outbox.auto-publish`, que não decide se o evento é gravado, só se o
agendador roda. Desligada, as mensagens se acumulam em `PENDING` e ninguém manda
e-mail sem querer em desenvolvimento; ligada, a fila drena sozinha, inclusive o
que ficou parado. Se a chave gateasse também a escrita, um incidente no provedor
custaria para sempre os comprovantes das transferências feitas durante a pausa —
não sobraria linha para tentar de novo. *(Decisão minha, depois da revisão.)*

Entra no `.env.example` com a chave em branco e no `docker-compose.prod.yml`
como as `BANCO_DEMO_*`.

**Proposta minha:** verificar o subdomínio `bank.devfly.net` no Resend, não o
`devfly.net` inteiro. Isola a reputação deste projeto dos outros sites da VPS —
se este aqui levar rejeição, não contamina o resto. Decisão do Gabriel, e os
registros DNS são passo dele.

### A tela de entrada

Uma linha a mais explicando que, para ver a ida e a volta do comprovante, são
dois cadastros com dois e-mails — porque cadastrado só transfere para
cadastrado, e demonstração só para demonstração.

## Testes

TDD, como o resto do projeto. O que precisa de prova:

- Transferência entre duas contas cadastradas enfileira duas mensagens, com as
  chaves certas.
- Transferência entre contas de demonstração **não** enfileira nada.
- O mesmo evento drenado duas vezes deixa uma mensagem só.
- Cadastro enfileira as boas-vindas.
- Sucesso do provedor marca `SENT` e guarda o `id`.
- 500 do provedor mantém `PENDING`, conta a tentativa e adia.
- 4xx que não é 429 marca `FAILED` na hora.
- O pedido leva `Authorization`, `User-Agent` e `Idempotency-Key`.
- O corpo gerado tem o valor, as contas e o identificador; o identificador bate
  com o do extrato.
- O comprovante traz o nome do dono daquele comprovante e **não** traz o nome da
  outra ponta — nos dois sentidos.
- O horário impresso é o de Brasília, e não o UTC gravado no banco.

O provedor é um servidor de mentira na URL base configurável. Nenhum teste
chama a internet.

## O que fica para quem constrói

- Nomes de classes e pacote (`com.estudo.banco.email`, provavelmente).
- O passo exato da espera entre tentativas e o teto.
- O intervalo do agendador.
- O HTML exato, respeitando o modelo A.
- Se o servidor de mentira é `MockRestServiceServer` ou um `HttpServer` do JDK.

## Passo que não é meu

Verificar o domínio no Resend e criar a chave da API. Sem isso, o envio só
chega ao e-mail da própria conta do Resend. Deixo o código pronto, desligado por
padrão, e ligo quando a chave existir.
