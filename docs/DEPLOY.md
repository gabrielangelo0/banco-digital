# Deploy na VPS

Sobe a aplicação inteira em Docker Compose, com HTTPS automático e sem expor
banco nem backend à internet.

---

## 1. O que esta stack sobe

```
                      ┌──────────────────────────────────────────┐
   internet ─── 443 ──▶│  web   (Caddy)                          │
                      │  • serve o build estático do React       │
                      │  • certificado Let's Encrypt automático  │
                      └───────────────┬──────────────────────────┘
                                      │  /api/*   (rede interna)
                      ┌───────────────▼──────────────────────────┐
                      │  backend  (Spring Boot, JRE 21)          │
                      │  • sem porta publicada                   │
                      └───────────────┬──────────────────────────┘
                                      │  5432     (rede interna)
                      ┌───────────────▼──────────────────────────┐
                      │  postgres  (volume persistente)          │
                      │  • sem porta publicada                   │
                      └──────────────────────────────────────────┘
```

**Só o container de borda tem porta publicada.** O backend e o Postgres existem
apenas na rede interna do compose — não há como alcançar o `5432` ou o `8080` de
fora, nem por engano nem por firewall mal configurado. O `docker-compose.yml` da
raiz (que publica o `5432` em `127.0.0.1`) é para **desenvolvimento**, não para
servidor; na VPS use sempre o `docker-compose.prod.yml`.

| Arquivo | Papel |
|---|---|
| `docker-compose.prod.yml` | A stack de produção |
| `backend/Dockerfile` | Build Maven → imagem JRE, usuário sem privilégio |
| `frontend/Dockerfile` | Build Vite → imagem Caddy com o estático dentro |
| `frontend/Caddyfile` | Roteamento, HTTPS, cache e o `flush_interval` do SSE |
| `.env.prod.example` | Modelo das variáveis — copie, não edite |
| `scripts/reset-demo.sh` | Devolve a demo ao estado inicial |

---

## 2. Pré-requisitos

Na VPS:

- **Docker Engine + plugin Compose.** `docker compose version` precisa responder.
- **Portas 80 e 443 abertas.** A 80 não é opcional: o Let's Encrypt usa ela para
  provar que o domínio é seu. Se o provedor tiver firewall próprio (Security
  Group, Cloud Firewall), libere nos dois lugares.
- **Memória:** 2 GB confortável, 1 GB suficiente. A JVM está limitada a 75% do
  cgroup; o build Maven é a parte pesada e pode ser feito em outra máquina
  (ver §7).

No DNS, **antes de subir**:

- Um registro `A` do seu domínio apontando para o IP da VPS. Se a emissão do
  certificado rodar antes do DNS propagar, o Let's Encrypt falha e tem limite de
  tentativas por semana — confirme primeiro:

```bash
dig +short banco.seudominio.com
```

---

## 3. Subir

### 3.1 Clonar

O repositório é privado, então o `git clone` precisa de credencial. A forma
limpa é uma **deploy key** (chave que só lê este repositório, e só nesta VPS):

```bash
ssh-keygen -t ed25519 -C "vps-banco-digital" -f ~/.ssh/banco_deploy -N ""
cat ~/.ssh/banco_deploy.pub
```

Cole a chave pública em **Settings → Deploy keys → Add deploy key** no
repositório, sem marcar *Allow write access*. Depois:

```bash
GIT_SSH_COMMAND="ssh -i ~/.ssh/banco_deploy" git clone git@github.com:gabrielangelo0/banco-digital.git
```

### 3.2 Configurar

```bash
cd banco-digital
cp .env.prod.example .env.prod
```

Gere a senha em vez de inventar uma, e escreva-a no `.env.prod`:

```bash
openssl rand -base64 32
```

O `.env.prod` precisa de quatro variáveis:

| Variável | Valor |
|---|---|
| `SITE_ADDRESS` | O domínio, ex. `banco.seudominio.com`. Com domínio, o Caddy emite o certificado sozinho |
| `POSTGRES_DB` | `banco` |
| `POSTGRES_USER` | `banco` |
| `POSTGRES_PASSWORD` | A senha gerada acima |

O compose **aborta** se qualquer uma faltar — uma variável esquecida não vira
string vazia silenciosamente.

### 3.3 Subir

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --build
```

O primeiro build baixa as dependências Maven e do npm; conte alguns minutos. Os
seguintes reaproveitam as camadas de dependência.

---

## 4. Verificar

Não confie em "subiu sem erro". Três checagens, em ordem de profundidade.

**Os três containers de pé, o backend saudável:**

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod ps
```

`backend` precisa aparecer como `healthy`, não apenas `running`.

**O roteamento do `/api` pegou:**

```bash
curl -s https://banco.seudominio.com/api/accounts
```

Agora toda rota de `/api` exige sessão, então a resposta correta aqui é
`{"code":"SEM_SESSAO","message":...}` com status 401 — e isso já responde o
que esta checagem sempre quis responder: se vier o **HTML do `index.html`** em
vez de JSON, o `/api` está caindo no servidor de estáticos. Foi assim que se
descobriu, uma vez, que a porta respondida era de outra aplicação.

**Uma sessão para as checagens seguintes.** Com `BANCO_DEMO_ENABLED=true`:

```bash
curl -s -c /tmp/banco.cookies -o /dev/null -w '%{http_code}\n' \
  -X POST https://banco.seudominio.com/api/auth/guest \
  -H 'Content-Type: application/json' -d '{"accountId":1}'
```

`200`. Com a demonstração desligada, use `/api/auth/register` e depois
`/api/auth/login` com um e-mail descartável.

**O cookie de sessão sai com `Secure`** — a outra falha silenciosa, porque o
TLS termina no proxy e o backend recebe HTTP puro:

```bash
grep -io 'set-cookie:.*' /tmp/banco.cookies >/dev/null; \
curl -s -D - -o /dev/null -X POST https://banco.seudominio.com/api/auth/guest \
  -H 'Content-Type: application/json' -d '{"accountId":1}' | grep -i '^set-cookie'
```

A linha precisa conter `Secure`, `HttpOnly` e `SameSite=Lax`. Se faltar
`Secure`, o proxy à frente não está mandando `X-Forwarded-Proto: https`, ou o
endereço dele não casa com `banco.proxies-confiaveis` — e aí uma única visita
a `http://` manda o token de sessão em texto claro.

**A API responde autenticada:**

```bash
curl -s -b /tmp/banco.cookies https://banco.seudominio.com/api/accounts
```

**Uma** conta, com saldo: a da sessão. Duas contas aqui seria um defeito de
autorização.

**O SSE realmente transmite** — esta é a checagem que importa, porque é a que
falha em silêncio:

```bash
curl -N -s -b /tmp/banco.cookies https://banco.seudominio.com/api/notifications/stream
```

Deve imprimir, **na hora**, um evento `ready`:

```
event:ready
data:{"accountId":1}
```

Se o comando ficar pendurado sem imprimir nada, o stream está sendo acumulado
por algum proxy no caminho. O `flush_interval -1` do `Caddyfile` resolve isso
para o Caddy; se você puser outro proxy na frente (nginx, Cloudflare, um load
balancer do provedor), ele precisa do equivalente — no nginx,
`proxy_buffering off`. Deixe o `curl -N` aberto, faça uma transferência pelo
app noutra aba e veja o evento `transfer` chegar.

Ao terminar, `rm -f /tmp/banco.cookies`: aquele arquivo é uma sessão válida.

Por último, abra o app no celular. Se o HTTPS estiver válido, o navegador
oferece instalar — é o PWA. Sem HTTPS não há service worker, e sem service
worker não há PWA.

---

## 5. Operar

**Ver log** (JSON estruturado, um documento por linha):

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod logs -f backend
```

Para ler com olho humano, com `jq` instalado:

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod logs --no-log-prefix backend | jq -r '"\(.timestamp) \(.level) \(.correlationId // "-") \(.message)"'
```

**Atualizar para a versão nova:**

```bash
git pull
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --build
```

O Flyway aplica as migrações pendentes na subida. O volume do Postgres não é
tocado: os dados sobrevivem ao redeploy.

**Backup do banco** — faça isto antes de qualquer alteração de schema:

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod exec -T postgres \
  pg_dump -U banco -d banco | gzip > backup-$(date +%F).sql.gz
```

**Restaurar:**

```bash
gunzip -c backup-2026-11-14.sql.gz | docker compose -f docker-compose.prod.yml \
  --env-file .env.prod exec -T postgres psql -U banco -d banco
```

**Restaurar a demo** (saldos de volta ao início, movimentação apagada):

```bash
./scripts/reset-demo.sh
```

**Parar:**

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod down
```

`down` preserva os volumes. **`down -v` apaga o banco e os certificados** — e
refazer os certificados conta contra o limite semanal do Let's Encrypt.

---

## 6. Quando algo der errado

| Sintoma | Causa provável | O que fazer |
|---|---|---|
| Certificado não emite | DNS ainda não propagou, ou porta 80 fechada | `dig +short seu.dominio` e `curl -I http://seu.dominio`. Veja `logs web` |
| `bind: address already in use` | Já existe um nginx/Apache na 80 | `sudo ss -lptn 'sport = :80'` e pare o serviço, ou ponha o Caddy atrás dele |
| `curl -N` do SSE fica mudo | Proxy acumulando a resposta | Veja §4. Proxy na frente acumulando a resposta: §8.1 |
| Backend reinicia em loop | Senha ou URL do banco errada | `logs backend`. Confira que `DB_URL` aponta para o host `postgres`, não `localhost` |
| `relation "accounts" does not exist` | Flyway não rodou | `logs backend` — procure a linha do Flyway. `ddl-auto` é `validate`: o schema vem da migração, não do Hibernate |
| HTTP 500 sem corpo numa transferência | `lock_timeout` de 3s estourou sob concorrência | Limitação conhecida, documentada no guia de estudo |
| Build Maven estoura memória | VPS pequena compilando | Ver §7 |

---

## 7. Build fora da VPS

Numa VPS de 1 GB o build Maven é a única etapa que aperta. Dá para construir a
imagem noutra máquina e enviar apenas o resultado:

```bash
# na máquina de desenvolvimento
docker build -t banco-backend:latest ./backend
docker save banco-backend:latest | gzip | \
  ssh usuario@vps 'gunzip | docker load'
```

Na VPS, troque `build: {context: ./backend}` por `image: banco-backend:latest`
no `docker-compose.prod.yml` do servidor.

---

## 8. Atrás de um proxy reverso que já existe

A §3 assume 80 e 443 livres. Numa VPS que já serve outros projetos elas não
estão: há um proxy na borda (Traefik, nginx, Nginx Proxy Manager, Caddy), e
derrubá-lo para liberar a porta derruba o que já está em produção.

Nesse caso o Caddy deste projeto **não** publica portas nem emite certificado.
Ele continua sendo quem serve o front e roteia `/api` — só passa a falar HTTP
puro com quem está na frente. O TLS fica com o proxy da borda.

```
internet ──443──▶ proxy da borda ──http──▶ web (Caddy) ──▶ backend ──▶ postgres
                  (o TLS fica aqui)        deste projeto    sem porta    sem porta
```

Suba com a sobreposição:

```bash
docker compose -f docker-compose.prod.yml -f docker-compose.proxy.yml up -d --build
```

No `.env.prod`:

```bash
SITE_ADDRESS=:80          # o TLS não é mais problema deste Caddy
EDGE_NETWORK=<rede onde o proxy está>
WEB_ALIAS=banco-web
```

Para descobrir a rede do proxy:

```bash
docker inspect <container-do-proxy> \
  --format '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}'
```

O apelido (`WEB_ALIAS`) é como o proxy encontra este projeto. Não use o IP do
container: ele muda a cada recriação.

### 8.1 A rota no proxy, e a armadilha

`/api/notifications/stream` é SSE. Um proxy que acumula a resposta antes de
repassar transforma o stream em nada, e a falha é **silenciosa**: sem erro, sem
log, a conexão fica aberta e muda. Ajuste o buffering antes de testar.

| Proxy na borda | O que ajustar |
|---|---|
| nginx / Nginx Proxy Manager | `proxy_buffering off;` na rota (no NPM, aba *Advanced*) |
| Caddy | `flush_interval -1` dentro do `reverse_proxy` |
| Traefik | não bufferiza por padrão — só não ative `buffering` nessa rota |

O Caddy deste projeto já manda `X-Accel-Buffering: no` na rota do stream, que o
nginx respeita e desliga o buffering sozinho. É rede de segurança, não
substituto da configuração: quem está na frente pode não ser nginx.

Exemplo com Caddy na borda:

```caddy
banco.exemplo.com {
	# Sem `encode` aqui: o Caddy deste projeto já comprime o estático, e
	# comprimir é acumular bytes — o que mataria o SSE.
	reverse_proxy banco-web:80 {
		flush_interval -1
	}
}
```

Recarregue o proxy e verifique pela §4. A do `curl -N` é a que importa.

### Ao atualizar um deploy que já existia

A restauração da demonstração passou a acontecer **dentro da aplicação**, de
hora em hora, por lançamento compensatório. Se você tem uma entrada de crontab
chamando `scripts/reset-demo.sh`, **remova**: o script agora só mostra o estado.

A mudança não foi de lugar, foi de método. Truncar apagava o razão, que o
projeto declara fonte da verdade — e com usuários reais compartilhando as
mesmas tabelas, apagaria o histórico deles junto.

```bash
crontab -l | grep -v reset-demo.sh | crontab -
```

O `.env.prod` precisa ganhar `REDIS_PASSWORD` e, se quiser a entrada de
visitante, `BANCO_DEMO_ENABLED`, `BANCO_DEMO_PASSWORD` e
`BANCO_DEMO_TRANSACTION_PASSWORD`. Sem as duas últimas a aplicação não sobe com
a demonstração ligada, de propósito: credencial previsível é pior do que falhar
na subida.

## 9. O que este deploy NÃO tem

Dito com clareza, porque um deploy honesto declara seus limites:

- **Verificação de identidade.** Há cadastro, login e senha transacional, mas
  ninguém confere se você é quem diz ser: o cadastro é aberto e não há KYC.
  Para um estudo de consistência isso basta; para dinheiro de verdade, não.
- **Recuperação de senha.** Exigiria provedor de SMS ou e-mail, e um segredo a
  mais em produção, sem acrescentar nada ao que o projeto se propõe a provar.
  Quem perder a senha cria outra conta.
- **Segundo fator.** Mesma razão.
- **Rate limiting de borda.** O Caddy padrão não traz. Há bloqueio progressivo
  por identidade na aplicação, que é o que contém força bruta em login e senha
  transacional, mais um teto por IP em login, cadastro e entrada de visitante.
  O teto por IP só vale quando o `X-Forwarded-For` vem de um endereço que casa
  com `banco.proxies-confiaveis` — cujo default cobre as faixas privadas, onde
  fica a rede interna do compose. Com outra topologia, confira esse valor: sem
  ele o cabeçalho é descartado e só o bloqueio por identidade continua valendo. Volume de borda, se um dia
  justificar, a Cloudflare resolve sem mexer na stack.
- **Backup automático.** O comando de `pg_dump` está na §5, mas nada o agenda.
  Para um demo, o `reset-demo.sh` cobre a necessidade real.
- **Observabilidade além do log.** O log sai em JSON com `correlationId`,
  pronto para um agregador — mas não há agregador, nem métrica, nem alerta.
- **Escala horizontal.** O compose roda **uma** instância do backend, e precisa
  ser assim: os inscritos de SSE vivem em memória no processo. Com duas réplicas,
  uma transferência processada na réplica A não notifica quem está conectado na
  réplica B — a transferência acontece certo, a notificação não chega. A saída é
  Redis pub/sub entre as instâncias; está na lista do README, não implementado.
  Não suba `--scale backend=2`.
- **Zero-downtime deploy.** `up -d --build` derruba e sobe o backend. São
  segundos, e para um demo isso é aceitável.

Nenhum destes é descuido: são escolhas de escopo, e é assim que devem ser
apresentadas.
