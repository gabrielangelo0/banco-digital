#!/usr/bin/env sh
# ---------------------------------------------------------------------------
# Devolve a demo ao estado inicial.
#
# A aplicacao nao tem autenticacao - e um nao-objetivo declarado. Num demo
# publico isso significa que qualquer visitante pode zerar os saldos e o
# proximo visitante encontra um app sem dinheiro para mover. Este script
# existe para desfazer isso.
#
# Uso, na raiz do projeto, na VPS:
#   ./scripts/reset-demo.sh
#
# Para agendar (todo dia as 4h), no crontab do usuario:
#   0 4 * * * cd /caminho/do/projeto && ./scripts/reset-demo.sh >> /var/log/banco-reset.log 2>&1
#
# NAO apaga a tabela accounts: os ids precisam continuar os mesmos, porque o
# frontend e as chaves estrangeiras apontam para eles. Apaga a MOVIMENTACAO e
# recoloca os saldos de partida.
# ---------------------------------------------------------------------------
set -eu

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
ENV_FILE="${ENV_FILE:-./.env.prod}"

# O compose le o .env.prod, mas este script tambem precisa dele: o psql
# roda com o usuario e o banco de lá. Sem isto, mudar POSTGRES_USER no
# .env.prod quebraria o reset em silencio.
[ -f "$ENV_FILE" ] || { echo "nao achei $ENV_FILE - rode na raiz do projeto" >&2; exit 1; }
set -a
. "$ENV_FILE"
set +a

docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" exec -T postgres \
    psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" <<'SQL'
BEGIN;

-- Uma transacao so: ou a demo volta inteira ao inicio, ou nada muda. O mesmo
-- principio que governa uma transferencia governa o reset dela.
TRUNCATE entries, outbox_events, idempotency_keys, transfers RESTART IDENTITY;

-- Os mesmos valores de V2__seed.sql.
UPDATE accounts SET balance = 1000.0000 WHERE id = 1;
UPDATE accounts SET balance =  500.0000 WHERE id = 2;

COMMIT;

SELECT id, holder_name, balance FROM accounts ORDER BY id;
SQL

echo "demo restaurada."
