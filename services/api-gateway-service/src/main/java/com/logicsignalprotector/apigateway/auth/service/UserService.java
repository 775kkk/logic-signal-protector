package com.logicsignalprotector.apigateway.auth.service;

import com.logicsignalprotector.apigateway.auth.domain.UserEntity;
import com.logicsignalprotector.apigateway.auth.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;

  public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
  }

  @Transactional
  public UserEntity register(String login, String rawPassword) {
    if (userRepository.existsByLogin(login)) {
      throw new IllegalArgumentException("Пользователь с таким логином уже существует");
    }

    String hash = passwordEncoder.encode(rawPassword);
    UserEntity user = new UserEntity(login, hash);
    return userRepository.save(user);
  }
}
