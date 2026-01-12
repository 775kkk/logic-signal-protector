package com.logicsignalprotector.apigateway.auth.domain;

import jakarta.persistence.*;
import java.util.Objects;

@Entity
@Table(name = "auth_provider")
public class AuthProviderEntity {

  @Id
  @Column(name = "code", length = 32)
  private String code;

  @Column(name = "name", nullable = false, length = 64)
  private String name;

  protected AuthProviderEntity() {
    // for JPA
  }

  public AuthProviderEntity(String code, String name) {
    this.code = code;
    this.name = name;
  }

  public String getCode() {
    return code;
  }

  public String getName() {
    return name;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    AuthProviderEntity that = (AuthProviderEntity) o;
    return Objects.equals(code, that.code);
  }

  @Override
  public int hashCode() {
    return Objects.hash(code);
  }
}
