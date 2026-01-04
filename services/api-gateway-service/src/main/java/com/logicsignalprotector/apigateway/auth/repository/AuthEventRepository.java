package com.logicsignalprotector.apigateway.auth.repository;

import com.logicsignalprotector.apigateway.auth.domain.AuthEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthEventRepository extends JpaRepository<AuthEventEntity, Long> {}
