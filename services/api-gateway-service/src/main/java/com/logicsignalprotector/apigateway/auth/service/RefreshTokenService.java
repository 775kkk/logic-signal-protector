package com.logicsignalprotector.apigateway.auth.service;

import com.logicsignalprotector.apigateway.auth.domain.RefreshTokenEntity;
import com.logicsignalprotector.apigateway.auth.domain.UserEntity;
import com.logicsignalprotector.apigateway.auth.repository.RefreshTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

  private static final SecureRandom RNG = new SecureRandom();

  private final RefreshTokenRepository refreshTokenRepository;

  @Value("${security.jwt.refresh-ttl:30d}")
  private Duration refreshTtl;

  @Value("${security.jwt.refresh-pepper:dev-only-refresh-pepper}")
  private String refreshPepper;

  @Transactional
  public IssuedRefreshToken issue(UserEntity user, String deviceId, String ip, String userAgent) {
    String raw = generateRawToken();
    String hash = hash(raw);

    Instant now = Instant.now();
    Instant exp = now.plus(refreshTtl);

    RefreshTokenEntity entity =
        new RefreshTokenEntity(user, hash, now, exp, deviceId, ip, userAgent);
    refreshTokenRepository.save(entity);

    return new IssuedRefreshToken(raw, refreshTtl);
  }

  @Transactional
  public UserEntity rotate(String rawRefreshToken) {
    String hash = hash(rawRefreshToken);
    RefreshTokenEntity token =
        refreshTokenRepository
            .findByTokenHash(hash)
            .orElseThrow(() -> new AuthUnauthorizedException("Invalid refresh token"));

    Instant now = Instant.now();
    if (!token.isActive(now)) {
      throw new AuthUnauthorizedException("Refresh token expired or revoked");
    }

    token.revoke(now);
    return token.getUser();
  }

  @Transactional
  public void revoke(String rawRefreshToken) {
    String hash = hash(rawRefreshToken);
    refreshTokenRepository.findByTokenHash(hash).ifPresent(t -> t.revoke(Instant.now()));
  }

  private static String generateRawToken() {
    byte[] bytes = new byte[32];
    RNG.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private String hash(String raw) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] dig = md.digest((raw + refreshPepper).getBytes(StandardCharsets.UTF_8));
      return toHex(dig);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot hash refresh token", e);
    }
  }

  private static String toHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(Character.forDigit((b >> 4) & 0xF, 16));
      sb.append(Character.forDigit(b & 0xF, 16));
    }
    return sb.toString();
  }

  public record IssuedRefreshToken(String refreshToken, Duration ttl) {}
}
