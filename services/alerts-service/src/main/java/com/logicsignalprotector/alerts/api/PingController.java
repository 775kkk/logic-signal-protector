package com.logicsignalprotector.alerts.api;

import com.logicsignalprotector.alerts.domain.PingEntity;
import com.logicsignalprotector.alerts.repository.PingRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class PingController {

    private final PingRepository pingRepository;

    public PingController(PingRepository pingRepository) {
        this.pingRepository = pingRepository;
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        // 1. Создаём сущность
        PingEntity ping = new PingEntity("ping from /ping");

        // 2. Сохраняем в БД (Hibernate сделает INSERT)
        PingEntity saved = pingRepository.save(ping);

        // 3. Считаем общее количество ping’ов
        long total = pingRepository.count();

        // 4. Возвращаем результат
        return Map.of(
                "service", "alerts-service",
                "status", "ok",
                "savedId", saved.getId(),
                "totalPings", total
        );
    }
}
