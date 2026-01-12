package com.logicsignalprotector.apigateway.auth.repository;

import com.logicsignalprotector.apigateway.auth.domain.UserEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserEntity, Long> {
  boolean existsByLogin(String login);

  @EntityGraph(attributePaths = "roles")
  Optional<UserEntity> findByLogin(String login);
}
