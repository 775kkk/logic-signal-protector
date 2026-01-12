package com.logicsignalprotector.apigateway.auth.service;

import com.logicsignalprotector.apigateway.auth.domain.RoleEntity;
import com.logicsignalprotector.apigateway.auth.domain.UserEntity;
import com.logicsignalprotector.apigateway.auth.repository.ExternalAccountRepository;
import com.logicsignalprotector.apigateway.auth.repository.RoleRepository;
import com.logicsignalprotector.apigateway.auth.repository.UserRepository;
import com.logicsignalprotector.apigateway.common.web.ConflictException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {

  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final ExternalAccountRepository externalAccountRepository;
  private final PasswordEncoder passwordEncoder;

  public UserEntity register(String login, String rawPassword) {
    if (userRepository.existsByLogin(login)) {
      throw new ConflictException("Login already exists");
    }
    String hash = passwordEncoder.encode(rawPassword);
    UserEntity user = new UserEntity(login, hash);

    RoleEntity defaultRole =
        roleRepository
            .findByCode("USER")
            .orElseThrow(() -> new IllegalStateException("Default role USER not found in DB"));
    user.getRoles().add(defaultRole);

    return userRepository.save(user);
  }

  public UserEntity authenticate(String login, String rawPassword) {
    UserEntity user =
        userRepository
            .findByLogin(login)
            .orElseThrow(() -> new AuthUnauthorizedException("Invalid login or password"));

    if (!user.isActive()) {
      throw new AuthUnauthorizedException("User is disabled");
    }

    if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
      throw new AuthUnauthorizedException("Invalid login or password");
    }

    return user;
  }

  public UserEntity findByIdOrThrow(Long id) {
    return userRepository
        .findById(id)
        .orElseThrow(() -> new AuthUnauthorizedException("User not found"));
  }

  public UserEntity findByLoginOrThrow(String login) {
    return userRepository
        .findByLogin(login)
        .orElseThrow(() -> new AuthUnauthorizedException("User not found"));
  }

  /** Step 1.3: resolve user by external account (providerCode, externalUserId). */
  public Optional<UserEntity> findByExternalAccount(String providerCode, String externalUserId) {
    return externalAccountRepository
        .findByProviderCodeAndExternalId(providerCode, externalUserId)
        .map(ext -> ext.getUser());
  }
}
