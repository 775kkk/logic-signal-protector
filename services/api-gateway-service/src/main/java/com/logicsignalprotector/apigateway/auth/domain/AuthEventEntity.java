package com.logicsignalprotector.apigateway.auth.domain;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.Hibernate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "auth_events")
public class AuthEventEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id")
  private UserEntity user;

  @Column(name = "type", nullable = false, length = 32)
  private String type;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "ip", length = 64)
  private String ip;

  @Column(name = "user_agent", length = 256)
  private String userAgent;

  @Column(name = "details_json", columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String detailsJson;

  protected AuthEventEntity() {}

  public AuthEventEntity(
      UserEntity user,
      String type,
      Instant createdAt,
      String ip,
      String userAgent,
      String detailsJson) {
    this.user = user;
    this.type = type;
    this.createdAt = createdAt;
    this.ip = ip;
    this.userAgent = userAgent;
    this.detailsJson = detailsJson;
  }

  @PrePersist
  public void prePersist() {
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) return false;
    AuthEventEntity that = (AuthEventEntity) o;
    return id != null && id.equals(that.id);
  }

  @Override
  public int hashCode() {
    return getClass().hashCode();
  }
}
