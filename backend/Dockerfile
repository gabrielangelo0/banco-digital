# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Estagio 1: build
#
# Os testes NAO rodam aqui, e isso e deliberado. A suite de concorrencia usa
# Testcontainers, que precisa de um daemon Docker de verdade para subir um
# Postgres - dentro de um `docker build` nao existe um. Os testes sao porta de
# entrada do COMMIT, nao da imagem: rodam na maquina e na CI, onde ha Docker.
#
# Construir a imagem com -DskipTests nao e "pular o teste": e reconhecer que
# imagem e artefato de deploy, nao de verificacao.
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# O pom vem antes do codigo. Enquanto as dependencias nao mudarem, o Docker
# reaproveita esta camada e o rebuild nao baixa a internet outra vez - o que
# numa VPS pequena e a diferenca entre 30 segundos e 4 minutos.
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -DskipTests dependency:go-offline

COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -DskipTests clean package \
    && mv target/banco-*.jar target/app.jar

# ---------------------------------------------------------------------------
# Estagio 2: runtime
#
# JRE, nao JDK: nada aqui precisa de compilador. A imagem final nao carrega o
# Maven, o repositorio ~/.m2, nem o codigo-fonte.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Usuario sem privilegio. Se a aplicacao for comprometida, o atacante nao
# herda root dentro do container.
RUN addgroup -S banco && adduser -S banco -G banco

COPY --from=build --chown=banco:banco /build/target/app.jar app.jar

USER banco

# A JVM moderna le o limite de memoria do cgroup, mas o default reserva so 25%
# dele para o heap. Numa VPS de 1-2 GB isso desperdica metade da maquina.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

EXPOSE 8080

# O compose usa este healthcheck para decidir quando o backend esta pronto.
# start-period generoso porque o Flyway roda as migracoes na subida.
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=6 \
    CMD wget -qO- http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"'

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
