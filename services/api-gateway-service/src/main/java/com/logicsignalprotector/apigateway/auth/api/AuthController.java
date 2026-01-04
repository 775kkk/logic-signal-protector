package com.logicsignalprotector.apigateway.auth.api;

import com.logicsignalprotector.apigateway.auth.api.dto.LoginRequest;
import com.logicsignalprotector.apigateway.auth.api.dto.LogoutRequest;
import com.logicsignalprotector.apigateway.auth.api.dto.RefreshRequest;
import com.logicsignalprotector.apigateway.auth.api.dto.RegisterRequest;
import com.logicsignalprotector.apigateway.auth.api.dto.RegisterResponse;
import com.logicsignalprotector.apigateway.auth.api.dto.TokensResponse;
import com.logicsignalprotector.apigateway.auth.api.mapper.AuthApiMapper;
import com.logicsignalprotector.apigateway.auth.domain.UserEntity;
import com.logicsignalprotector.apigateway.auth.service.AuthAuditService;
import com.logicsignalprotector.apigateway.auth.service.RefreshTokenService;
import com.logicsignalprotector.apigateway.auth.service.TokenService;
import com.logicsignalprotector.apigateway.auth.service.UserService;
import com.logicsignalprotector.apigateway.common.ratelimit.RedisRateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

  private final UserService userService;
  private final TokenService tokenService;
  private final RefreshTokenService refreshTokenService;
  private final AuthAuditService audit;
  private final RedisRateLimitService rateLimit;
  private final AuthApiMapper mapper;

  @PostMapping("/register")
  public ResponseEntity<RegisterResponse> register(
      @Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
    String ip = clientIp(http);
    String ua = userAgent(http);
    rateLimit.checkRegister(request.login(), ip);

    UserEntity user = userService.register(request.login(), request.password());
    audit.log(user, "REGISTER", ip, ua, null);

    return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toRegisterResponse(user));
  }

  @PostMapping("/login")
  public ResponseEntity<TokensResponse> login(
      @Valid @RequestBody LoginRequest request, HttpServletRequest http) {
    String ip = clientIp(http);
    String ua = userAgent(http);
    rateLimit.checkLogin(request.login(), ip);

    UserEntity user = userService.authenticate(request.login(), request.password());

    TokenService.TokenResult access = tokenService.issueAccessToken(user);
    RefreshTokenService.IssuedRefreshToken refresh = refreshTokenService.issue(user, null, ip, ua);

    audit.log(user, "LOGIN_SUCCESS", ip, ua, null);

    return ResponseEntity.ok(
        new TokensResponse(
            access.token(),
            "Bearer",
            access.ttl().toSeconds(),
            refresh.refreshToken(),
            refresh.ttl().toSeconds()));
  }

  @PostMapping("/refresh")
  public ResponseEntity<TokensResponse> refresh(
      @Valid @RequestBody RefreshRequest request, HttpServletRequest http) {
    String ip = clientIp(http);
    String ua = userAgent(http);

    UserEntity user = refreshTokenService.rotate(request.refreshToken());

    TokenService.TokenResult access = tokenService.issueAccessToken(user);
    RefreshTokenService.IssuedRefreshToken refresh = refreshTokenService.issue(user, null, ip, ua);

    audit.log(user, "TOKEN_REFRESH", ip, ua, null);

    return ResponseEntity.ok(
        new TokensResponse(
            access.token(),
            "Bearer",
            access.ttl().toSeconds(),
            refresh.refreshToken(),
            refresh.ttl().toSeconds()));
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(
      @Valid @RequestBody LogoutRequest request, HttpServletRequest http) {
    String ip = clientIp(http);
    String ua = userAgent(http);

    refreshTokenService.revoke(request.refreshToken());
    audit.logAnonymous("LOGOUT", ip, ua, null);

    return ResponseEntity.noContent().build();
  }

  private static String userAgent(HttpServletRequest http) {
    String ua = http.getHeader("User-Agent");
    return ua == null ? null : ua.substring(0, Math.min(ua.length(), 256));
  }

  private static String clientIp(HttpServletRequest http) {
    // If you later put gateway behind a reverse-proxy, handle X-Forwarded-For properly.
    String ip = http.getRemoteAddr();
    return ip == null ? "unknown" : ip;
  }
}
