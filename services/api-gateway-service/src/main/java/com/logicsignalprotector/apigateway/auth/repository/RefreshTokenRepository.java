package com.logicsignalprotector.apigateway.auth.repository;

import com.logicsignalprotector.apigateway.auth.domain.RefreshTokenEntity;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, Long> {

  Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

  @Modifying
  @Query(
      "update RefreshTokenEntity t set t.revokedAt = ?2 where t.user.id = ?1 and t.revokedAt is null and t.expiresAt > ?2")
  int revokeAllActiveForUser(Long userId, Instant now);
}
