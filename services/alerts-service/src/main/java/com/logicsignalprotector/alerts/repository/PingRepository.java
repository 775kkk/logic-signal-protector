package com.logicsignalprotector.alerts.repository;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PingRepository extends JpaRepository<PingEntity, Long> {
    // Базовых методов JpaRepository достаточно: save, findAll, count, findById и т.д.
}
