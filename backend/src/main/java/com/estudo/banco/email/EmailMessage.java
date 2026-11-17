package com.estudo.banco.email;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * Uma mensagem na fila, com o historico das tentativas.
 *
 * As colunas de tentativa existem para responder, depois do fato, "esse e-mail
 * saiu? quando? com qual identificador no provedor?" sem cacar log. Num
 * provedor externo essa pergunta aparece sempre, e log rotaciona.
 */
@Entity
@Table(name = "email_messages")
public class EmailMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "to_address", nullable = false, updatable = false)
    private String toAddress;

    @Column(nullable = false, updatable = false)
    private String subject;

    @Column(name = "body_html", nullable = false, updatable = false)
    private String bodyHtml;

    @Column(name = "body_text", nullable = false, updatable = false)
    private String bodyText;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EmailStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private OffsetDateTime nextAttemptAt;

    @Column(name = "provider_message_id")
    private String providerMessageId;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    protected EmailMessage() {
    }

    public EmailMessage(EmailParaEnviar mensagem, OffsetDateTime agora) {
        this.toAddress = mensagem.destinatario();
        this.subject = mensagem.assunto();
        this.bodyHtml = mensagem.html();
        this.bodyText = mensagem.texto();
        this.idempotencyKey = mensagem.chave();
        this.status = EmailStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = agora;
    }

    /** Devolve a mensagem ao formato que o cliente do provedor consome. */
    public EmailParaEnviar paraEnviar() {
        return new EmailParaEnviar(toAddress, subject, bodyHtml, bodyText, idempotencyKey);
    }

    public void marcarEnviado(String providerMessageId, OffsetDateTime quando) {
        this.status = EmailStatus.SENT;
        this.providerMessageId = providerMessageId;
        this.sentAt = quando;
        this.attempts = this.attempts + 1;
        this.lastError = null;
    }

    /** Falha que passa: conta a tentativa e marca quando tentar de novo. */
    public void adiar(OffsetDateTime quando, String erro) {
        this.attempts = this.attempts + 1;
        this.nextAttemptAt = quando;
        this.lastError = erro;
    }

    /** Desistencia. Conta a tentativa porque ela aconteceu. */
    public void marcarFalho(String erro) {
        this.status = EmailStatus.FAILED;
        this.attempts = this.attempts + 1;
        this.lastError = erro;
    }

    /** Desistencia sem ter tentado: a mensagem foi descartada antes do envio. */
    public void descartar(String motivo) {
        this.status = EmailStatus.FAILED;
        this.lastError = motivo;
    }

    public Long getId() {
        return id;
    }

    public String getToAddress() {
        return toAddress;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public EmailStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public OffsetDateTime getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getProviderMessageId() {
        return providerMessageId;
    }

    public String getLastError() {
        return lastError;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getSentAt() {
        return sentAt;
    }
}
