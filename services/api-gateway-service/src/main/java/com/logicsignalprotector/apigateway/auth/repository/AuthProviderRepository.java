package com.logicsignalprotector.apigateway.auth.repository;

import com.logicsignalprotector.apigateway.auth.domain.AuthProviderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthProviderRepository extends JpaRepository<AuthProviderEntity, String> {}
