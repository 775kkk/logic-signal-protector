# alerts-service

## Назначение

Это каркас сервиса алертов/уведомлений. Сейчас он нужен как демонстрация того,
как downstream-сервисы проверяют JWT от gateway и требуют нужные права.

Человеческое объяснение: это «учебная площадка» для проверки безопасности.
Здесь нет реальной бизнес‑логики, но есть все ключевые механики доступа.

## Как это работает

1) Клиент вызывает защищённую ручку.
2) Spring Security проверяет JWT токен доступа:
   - подпись (HMAC, общий секрет с gateway),
   - issuer.
3) `JwtAuthConverter` превращает claims `roles` и `perms` в authorities:
   - `ROLE_<role>`
   - `PERM_<perm>`
4) Контроллеры используют `@PreAuthorize` для проверки прав.

## Как реализовано (карта кода)

Если нужно понять механику, достаточно открыть `SecurityConfig` и `SecureDemoController` —
там видно, как JWT превращается в доступ.

- `security/SecurityConfig` — конфигурация ресурсного сервера.
- `security/JwtAuthConverter` — маппинг claims в authorities.
- `api/SecureDemoController` — защищённая ручка с `@PreAuthorize`.
- `api/PingController` — открытый `GET /ping`.

## API

- `GET /ping` — проверка доступности.
- `GET /api/alerts/secure-sample` — требует `PERM_ALERTS_READ`, возвращает данные из JWT.

## Конфигурация и env

- `JWT_SECRET` — общий секрет с gateway (не менее 32 байт).
- `JWT_ISSUER` — по умолчанию `lsp-api-gateway`.

Порт по умолчанию: `8082`.

## Локальный запуск

```bash
mvn -pl services/alerts-service spring-boot:run
```

Проверка (нужен валидный токен доступа от gateway):

```bash
curl -H "Authorization: Bearer <ACCESS_TOKEN>" \
  http://localhost:8082/api/alerts/secure-sample
```

## Ограничения

Бизнес-логики нет, ручки демонстрационные.
