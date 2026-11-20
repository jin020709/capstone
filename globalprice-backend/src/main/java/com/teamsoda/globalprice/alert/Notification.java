package com.teamsoda.globalprice.alert;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notification_id")
    private Long id;

    @Column(name = "alert_id", nullable = false)
    private Long alertId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    protected Notification() {
    }

    public Notification(Long alertId, String message, LocalDateTime sentAt) {
        this.alertId = alertId;
        this.message = message;
        this.sentAt = sentAt;
    }

    public void markRead(LocalDateTime at) {
        if (readAt == null) readAt = at;
    }

    public Long getId() { return id; }
    public Long getAlertId() { return alertId; }
    public String getMessage() { return message; }
    public LocalDateTime getSentAt() { return sentAt; }
    public LocalDateTime getReadAt() { return readAt; }
}
