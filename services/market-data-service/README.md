# market-data-service

## Назначение (простыми словами)

Каркас сервиса рыночных данных. Сейчас он нужен, чтобы показать,
как downstream-сервисы проверяют JWT и permission от gateway.

Проще говоря, это демонстрационный сервис‑макет: он показывает,
как будет выглядеть защищённый доступ к данным, когда появится реальная логика.

## Как это работает

1) Клиент вызывает защищённую ручку.
2) Spring Security валидирует JWT access token (подпись + issuer).
3) `JwtAuthConverter` превращает claims `roles`/`perms` в authorities.
4) `@PreAuthorize("hasAuthority('PERM_MARKETDATA_READ')")` блокирует доступ без права.

## Как реализовано (карта кода)

Самое полезное место для чтения — `SecurityConfig` и `SecureDemoController`:
там видно, как JWT проверяется и как вешаются права.

- `security/SecurityConfig` — resource-server конфигурация.
- `security/JwtAuthConverter` — маппинг claims в authorities.
- `api/SecureDemoController` — защищённая ручка.
- `api/PingController` — открытый `GET /ping`.

## API

- `GET /ping` — проверка доступности.
- `GET /api/market-data/secure-sample` — требует `PERM_MARKETDATA_READ`, возвращает claims из JWT.

## Конфигурация и env

- `JWT_SECRET` — общий секрет с gateway (не менее 32 байт).
- `JWT_ISSUER` — по умолчанию `lsp-api-gateway`.

Порт по умолчанию: `8081`.

## Локальный запуск

```bash
mvn -pl services/market-data-service spring-boot:run
```

Проверка (нужен валидный access token от gateway):

```bash
curl -H "Authorization: Bearer <ACCESS_TOKEN>" \
  http://localhost:8081/api/market-data/secure-sample
```

## Ограничения

Это демонстрационный каркас без реальной бизнес-логики.
