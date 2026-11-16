#!/usr/bin/env sh
# ---------------------------------------------------------------------------
# A restauracao da demonstracao agora acontece DENTRO da aplicacao, de hora em
# hora, por lancamento compensatorio - nao mais por TRUNCATE a partir daqui.
#
# A mudanca nao foi de lugar, foi de metodo. Truncar apagava o razao, que o
# projeto declara fonte da verdade; e com usuarios reais compartilhando as
# mesmas tabelas, apagaria o historico deles junto. Compensar devolve os
# saldos sem tirar uma linha do livro.
#
# Este script agora so MOSTRA o estado. Se voce tem uma entrada de crontab
# chamando ele, remova: ela nao e mais necessaria (ver docs/DEPLOY.md).
# ---------------------------------------------------------------------------
set -eu

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
ENV_FILE="${ENV_FILE:-./.env.prod}"

[ -f "$ENV_FILE" ] || { echo "nao achei $ENV_FILE - rode na raiz do projeto" >&2; exit 1; }
set -a
. "$ENV_FILE"
set +a

echo "Saldos das contas de demonstracao agora:"
docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" exec -T postgres \
    psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c \
    "SELECT id, holder_name, balance FROM accounts WHERE owner_id IS NOT NULL ORDER BY id LIMIT 2;"

echo
echo "A restauracao roda sozinha de hora em hora dentro da aplicacao, no minuto"
echo "zero (banco.demo.reset-cron). NAO existe gatilho manual: reiniciar o"
echo "backend nao dispara nada, so recoloca o agendador de pe."
echo
echo "Para antecipar, mude o cron e reinicie o backend:"
echo "  BANCO_DEMO_RESET_CRON='0 */5 * * * *'"
