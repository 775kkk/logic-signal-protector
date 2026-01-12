package com.logicsignalprotector.apigateway.auth.repository;

import com.logicsignalprotector.apigateway.auth.domain.RoleEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<RoleEntity, Long> {
  Optional<RoleEntity> findByCode(String code);
}
