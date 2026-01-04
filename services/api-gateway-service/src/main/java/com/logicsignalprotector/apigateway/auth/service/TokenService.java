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

  @Value("${security.jwt.issuer:lsp-api-gateway}")
  private String issuer;

  @Value("${security.jwt.access-ttl:PT15M}")
  private Duration accessTtl;

  public TokenResult issueAccessToken(UserEntity user) {
    Instant now = Instant.now();
    Duration ttl = accessTtl;

    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(issuer)
            .issuedAt(now)
            .expiresAt(now.plus(ttl))
            .subject(user.getLogin())
            .claim(
                "roles",
                user.getRoles().stream().map(r -> r.getCode()).collect(Collectors.toList()))
            .build();

    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();

    return new TokenResult(
        jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue(), ttl);
  }

  public record TokenResult(String token, Duration ttl) {}
}
