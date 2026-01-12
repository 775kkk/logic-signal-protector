package com.logicsignalprotector.apigateway.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Step 1.3: protects /internal/** endpoints with a shared token header. */
@Component
@Slf4j
public class InternalApiAuthFilter extends OncePerRequestFilter {

  private final String expectedToken;

  public InternalApiAuthFilter(@Value("${internal.auth.token:}") String expectedToken) {
    this.expectedToken = expectedToken == null ? "" : expectedToken.trim();
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getServletPath();
    return path == null || !path.startsWith("/internal/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    if (expectedToken.isBlank()) {
      response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
      response.setContentType("application/json");
      response.getWriter().write("{\"error\":\"internal.auth.token is not configured\"}");
      return;
    }

    String provided = request.getHeader("X-Internal-Token");
    if (provided == null || !expectedToken.equals(provided)) {
      log.warn("Internal token mismatch for {} {}", request.getMethod(), request.getRequestURI());
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType("application/json");
      response.getWriter().write("{\"error\":\"unauthorized\"}");
      return;
    }

    filterChain.doFilter(request, response);
  }
}
