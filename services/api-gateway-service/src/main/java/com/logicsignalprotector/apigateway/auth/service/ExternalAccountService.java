package com.logicsignalprotector.apigateway.auth.service;

import com.logicsignalprotector.apigateway.auth.domain.ExternalAccountEntity;
import com.logicsignalprotector.apigateway.auth.domain.UserEntity;
import com.logicsignalprotector.apigateway.auth.repository.ExternalAccountRepository;
import com.logicsignalprotector.apigateway.common.web.ConflictException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ExternalAccountService {

  public static final String PROVIDER_TELEGRAM =
      "TELEGRAM"; // reserved for Telegram adapter (step 1.3)

  private final ExternalAccountRepository externalAccountRepository;

  /** Step 1.3: generic method (needed for multiple chat providers in the future). */
  @Transactional
  public void link(UserEntity user, String providerCode, String externalId) {

    // 1) External id must be globally unique per provider.
    Optional<ExternalAccountEntity> byExternal =
        externalAccountRepository.findByProviderCodeAndExternalId(providerCode, externalId);
    if (byExternal.isPresent() && !byExternal.get().getUser().getId().equals(user.getId())) {
      throw new ConflictException(
          "External account is already linked to another user (provider=" + providerCode + ")");
    }

    // 2) User can have at most one account per provider.
    Optional<ExternalAccountEntity> byUserProvider =
        externalAccountRepository.findByUser_IdAndProviderCode(user.getId(), providerCode);

    if (byUserProvider.isPresent()) {
      String existingExternalId = byUserProvider.get().getExternalId();
      if (!existingExternalId.equals(externalId)) {
        throw new ConflictException(
            "User already has another external account linked (provider=" + providerCode + ")");
      }
      // Idempotent: already linked to the same external id.
      return;
    }

    ExternalAccountEntity entity = new ExternalAccountEntity(user, providerCode, externalId);
    externalAccountRepository.save(entity);
  }
}
