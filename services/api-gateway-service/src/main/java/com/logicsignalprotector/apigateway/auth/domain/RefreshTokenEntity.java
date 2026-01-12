package com.logicsignalprotector.apigateway.auth.domain;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.Hibernate;

@Entity
@Table(name = "refresh_tokens")
public class RefreshTokenEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private UserEntity user;

  @Column(name = "token_hash", nullable = false, unique = true, length = 255)
  private String tokenHash;

  @Column(name = "issued_at", nullable = false)
  private Instant issuedAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "device_id", length = 128)
  private String deviceId;

  @Column(name = "ip", length = 64)
  private String ip;

  @Column(name = "user_agent", length = 256)
  private String userAgent;

  protected RefreshTokenEntity() {}

  public RefreshTokenEntity(
      UserEntity user,
      String tokenHash,
      Instant issuedAt,
      Instant expiresAt,
      String deviceId,
      String ip,
      String userAgent) {
    this.user = user;
    this.tokenHash = tokenHash;
    this.issuedAt = issuedAt;
    this.expiresAt = expiresAt;
    this.deviceId = deviceId;
    this.ip = ip;
    this.userAgent = userAgent;
  }

  public UserEntity getUser() {
    return user;
  }

  public void revoke(Instant at) {
    this.revokedAt = at;
  }

  public boolean isActive(Instant now) {
    return revokedAt == null && expiresAt.isAfter(now);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) return false;
    RefreshTokenEntity that = (RefreshTokenEntity) o;
    return id != null && id.equals(that.id);
  }

  @Override
  public int hashCode() {
    return getClass().hashCode();
  }
}
