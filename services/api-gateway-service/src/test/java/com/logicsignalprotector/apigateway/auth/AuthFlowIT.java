package com.logicsignalprotector.apigateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;

import com.logicsignalprotector.apigateway.auth.api.dto.LoginRequest;
import com.logicsignalprotector.apigateway.auth.api.dto.RefreshRequest;
import com.logicsignalprotector.apigateway.auth.api.dto.RegisterRequest;
import com.logicsignalprotector.apigateway.auth.api.dto.TokensResponse;
import com.logicsignalprotector.apigateway.common.ratelimit.RedisRateLimitService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthFlowIT {

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("lsp_gateway")
          .withUsername("lsp_gateway_app")
          .withPassword("1111");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
    r.add("security.jwt.secret", () -> "dev-only-change-me-dev-only-change-me");
    // Avoid accidental Redis calls in ITs; rate limiting is mocked.
    r.add("spring.data.redis.host", () -> "127.0.0.1");
    r.add("spring.data.redis.port", () -> "1");
  }

  @LocalServerPort int port;

  @Autowired TestRestTemplate http;

  @MockBean RedisRateLimitService rateLimit;

  @Test
  void register_login_refresh_happyPath() {
    doNothing()
        .when(rateLimit)
        .checkRegister(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    doNothing()
        .when(rateLimit)
        .checkLogin(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());

    String base = "http://localhost:" + port;

    // 1) register
    ResponseEntity<String> reg =
        http.postForEntity(
            base + "/api/auth/register",
            new RegisterRequest("demo_user", "demo_pass123"),
            String.class);
    assertThat(reg.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    // 2) login
    ResponseEntity<TokensResponse> login =
        http.postForEntity(
            base + "/api/auth/login",
            new LoginRequest("demo_user", "demo_pass123"),
            TokensResponse.class);

    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(login.getBody()).isNotNull();
    assertThat(login.getBody().accessToken()).isNotBlank();
    assertThat(login.getBody().refreshToken()).isNotBlank();

    String oldRefresh = login.getBody().refreshToken();

    // 3) refresh
    ResponseEntity<TokensResponse> refresh =
        http.postForEntity(
            base + "/api/auth/refresh", new RefreshRequest(oldRefresh), TokensResponse.class);

    assertThat(refresh.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(refresh.getBody()).isNotNull();
    assertThat(refresh.getBody().accessToken()).isNotBlank();
    assertThat(refresh.getBody().refreshToken()).isNotBlank();
    assertThat(refresh.getBody().refreshToken()).isNotEqualTo(oldRefresh);
  }
}
