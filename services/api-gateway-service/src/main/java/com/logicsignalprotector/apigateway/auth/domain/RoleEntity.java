package com.logicsignalprotector.apigateway.auth.domain;

import jakarta.persistence.*;
import org.hibernate.Hibernate;

@Entity
@Table(name = "roles")
public class RoleEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "code", nullable = false, unique = true, length = 32)
  private String code;

  @Column(name = "name", nullable = false, length = 64)
  private String name;

  protected RoleEntity() {
    // for JPA
  }

  public RoleEntity(String code, String name) {
    this.code = code;
    this.name = name;
  }

  public Long getId() {
    return id;
  }

  public String getCode() {
    return code;
  }

  public String getName() {
    return name;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) return false;
    RoleEntity that = (RoleEntity) o;
    return id != null && id.equals(that.id);
  }

  @Override
  public int hashCode() {
    return getClass().hashCode();
  }
}
