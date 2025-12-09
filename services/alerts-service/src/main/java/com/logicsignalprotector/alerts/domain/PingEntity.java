package com.logicsignalprotector.alerts.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "pings")
public class PingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Когда был сделан ping
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    // Просто какое-то сообщение, чтобы было видно, что мы что-то пишем
    @Column(name = "message", nullable = false, length = 100)
    private String message;

    protected PingEntity() {
        // конструктор для JPA
    }

    public PingEntity(String message) {
        this.message = message;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
