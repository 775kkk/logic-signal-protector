package com.logicsignalprotector.apigateway.auth.repository;

import com.logicsignalprotector.apigateway.auth.domain.ExternalAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

public interface ExternalAccountRepository extends JpaRepository<ExternalAccountEntity, Long> {

    Optional<ExternalAccountEntity> findByProviderCodeAndExternalId(String providerCode, String externalId);

    List<ExternalAccountEntity> findByUserId(Long userId);
}
