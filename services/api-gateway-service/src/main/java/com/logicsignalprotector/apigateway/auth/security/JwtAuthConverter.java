package com.logicsignalprotector.apigateway.auth.security;

import java.util.*;
import java.util.stream.Collectors;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Step 1.3: maps JWT claims to Spring Security authorities.
 *
 * <p>Expected claims: - roles: ["USER", "ADMIN", ...] -> ROLE_USER, ROLE_ADMIN - perms:
 * ["MARKETDATA_READ", ...] -> PERM_MARKETDATA_READ
 */
public class JwtAuthConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

  @Override
  public Collection<GrantedAuthority> convert(Jwt jwt) {
    List<String> roles = safeStringList(jwt.getClaim("roles"));
    List<String> perms = safeStringList(jwt.getClaim("perms"));

    Set<String> out = new LinkedHashSet<>();
    for (String r : roles) {
      if (r != null && !r.isBlank()) {
        out.add("ROLE_" + r);
      }
    }
    for (String p : perms) {
      if (p != null && !p.isBlank()) {
        out.add("PERM_" + p);
      }
    }

    return out.stream().map(SimpleGrantedAuthority::new).collect(Collectors.toUnmodifiableSet());
  }

  private static List<String> safeStringList(Object claim) {
    if (claim == null) {
      return List.of();
    }
    if (claim instanceof List<?> list) {
      List<String> out = new ArrayList<>(list.size());
      for (Object o : list) {
        if (o != null) {
          out.add(String.valueOf(o));
        }
      }
      return out;
    }
    // Fallback for wrong type
    return List.of(String.valueOf(claim));
  }
}
