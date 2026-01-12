package com.logicsignalprotector.apigateway.auth.service;

import com.logicsignalprotector.apigateway.auth.domain.UserEntity;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TokenService {

  private final JwtEncoder jwtEncoder;
  private final PermissionService permissionService;

  @Value("${security.jwt.issuer:lsp-api-gateway}")
  private String issuer;

  @Value("${security.jwt.access-ttl:PT15M}")
  private Duration accessTtl;

  public TokenResult issueAccessToken(UserEntity user) {
    Instant now = Instant.now();
    Duration ttl = accessTtl;

    // Step 1.3: compute effective permissions from DB (role-permissions + user overrides)
    var roleCodes = user.getRoles().stream().map(r -> r.getCode()).collect(Collectors.toList());
    var permCodes =
        permissionService
            .getEffectivePermissionCodes(user.getId() == null ? -1L : user.getId())
            .stream()
            .sorted()
            .collect(Collectors.toList());

    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(issuer) // из security.jwt.issuer (по умолчанию lsp-api-gateway)
            .issuedAt(now) // текущее время
            .expiresAt(
                now.plus(
                    ttl)) // текущее время + TTL (security.jwt.access-ttl, по умолчанию 15 минут
            // PT15M)
            .subject(user.getLogin())
            .claim("uid", user.getId()) // userId
            .claim("roles", roleCodes)
            .claim("perms", permCodes)
            .build();

    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();

    return new TokenResult(
        jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue(), ttl);
  }

  public record TokenResult(String token, Duration ttl) {}
}
