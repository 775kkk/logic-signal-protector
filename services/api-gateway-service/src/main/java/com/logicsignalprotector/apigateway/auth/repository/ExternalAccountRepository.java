package com.logicsignalprotector.apigateway.auth.repository;

import com.logicsignalprotector.apigateway.auth.domain.ExternalAccountEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExternalAccountRepository extends JpaRepository<ExternalAccountEntity, Long> {

  Optional<ExternalAccountEntity> findByProviderCodeAndExternalId(
      String providerCode, String externalId);

  List<ExternalAccountEntity> findByUser_Id(Long userId);

  boolean existsByUser_IdAndProviderCode(Long userId, String providerCode);

  Optional<ExternalAccountEntity> findByUser_IdAndProviderCode(Long userId, String providerCode);
}
