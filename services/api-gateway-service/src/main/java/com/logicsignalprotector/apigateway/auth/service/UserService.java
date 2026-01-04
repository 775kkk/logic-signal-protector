package com.logicsignalprotector.apigateway.auth.service;

import com.logicsignalprotector.apigateway.auth.domain.RoleEntity;
import com.logicsignalprotector.apigateway.auth.domain.UserEntity;
import com.logicsignalprotector.apigateway.auth.repository.RoleRepository;
import com.logicsignalprotector.apigateway.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {

  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final PasswordEncoder passwordEncoder;

  public UserEntity register(String login, String rawPassword) {
    if (userRepository.existsByLogin(login)) {
      throw new IllegalArgumentException("Login already exists");
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
}
