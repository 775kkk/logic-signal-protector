package com.logicsignalprotector.apigateway.auth.domain;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.Hibernate;

@Entity
@Table(name = "external_accounts")
public class ExternalAccountEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  // связь с нашим пользователем
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private UserEntity user;

  // код провайдера ('TELEGRAM', 'WEB', ...)
  @Column(name = "provider_code", nullable = false, length = 32)
  private String providerCode;

  // внешний id (chatId, userId и т.п.)
  @Column(name = "external_id", nullable = false, length = 128)
  private String externalId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected ExternalAccountEntity() {
    // for JPA
  }

  public ExternalAccountEntity(UserEntity user, String providerCode, String externalId) {
    this.user = user;
    this.providerCode = providerCode;
    this.externalId = externalId;
  }

  @PrePersist
  public void prePersist() {
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }

  public Long getId() {
    return id;
  }

  public UserEntity getUser() {
    return user;
  }

  public void setUser(UserEntity user) {
    this.user = user;
  }

  public String getProviderCode() {
    return providerCode;
  }

  public void setProviderCode(String providerCode) {
    this.providerCode = providerCode;
  }

  public String getExternalId() {
    return externalId;
  }

  public void setExternalId(String externalId) {
    this.externalId = externalId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) {
      return false;
    }
    ExternalAccountEntity that = (ExternalAccountEntity) o;
    return id != null && id.equals(that.id);
  }

  @Override
  public int hashCode() {
    return getClass().hashCode();
  }
}
