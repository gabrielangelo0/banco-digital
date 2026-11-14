# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Estagio 1: build do React
#
# `npm ci` e nao `npm install`: ci instala exatamente o que esta no
# package-lock.json e falha se o lock divergir do package.json. Build de
# producao nao resolve versao, ele obedece o lock.
# ---------------------------------------------------------------------------
FROM node:22-alpine AS build
WORKDIR /build

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# ---------------------------------------------------------------------------
# Estagio 2: a borda
#
# Caddy em vez de nginx por um motivo concreto: HTTPS automatico. Ele pede o
# certificado ao Let's Encrypt na primeira subida e renova sozinho. Sem isso
# nao existe PWA - service worker so roda em contexto seguro.
# ---------------------------------------------------------------------------
FROM caddy:2-alpine

COPY Caddyfile /etc/caddy/Caddyfile
COPY --from=build /build/dist /srv

EXPOSE 80 443
