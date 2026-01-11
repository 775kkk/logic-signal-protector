# virtual-broker-service

## Назначение

Каркас "виртуального брокера". Сейчас он используется как пример защищённых бизнес-ручек,
которые требуют прав от gateway.

Если очень просто: это «тренировочный брокер», который не торгует по‑настоящему,
но даёт понять, как будут проверяться права на операции.

## Как это работает

1) Клиент вызывает защищённую ручку.
2) Spring Security валидирует JWT токен доступа (подпись + issuer).
3) `JwtAuthConverter` превращает claims `roles`/`perms` в authorities.
4) `@PreAuthorize` проверяет нужный `PERM_*`.

## Как реализовано (карта кода)

Для ориентира достаточно посмотреть `SecurityConfig` и `SecureDemoController` —
это две ключевые точки, где видно проверку токена и прав.

- `security/SecurityConfig` — конфигурация ресурсного сервера.
- `security/JwtAuthConverter` — маппинг claims в authorities.
- `api/SecureDemoController` — защищённые ручки.
- `api/PingController` — открытый `GET /ping`.

## API

- `GET /ping` — проверка доступности.
- `GET /api/broker/secure-sample` — требует `PERM_BROKER_READ`.
- `POST /api/broker/trade-sample` — требует `PERM_BROKER_TRADE`.

## Конфигурация и env

- `JWT_SECRET` — общий секрет с gateway (не менее 32 байт).
- `JWT_ISSUER` — по умолчанию `lsp-api-gateway`.

Порт по умолчанию: `8083`.

## Локальный запуск

```bash
mvn -pl services/virtual-broker-service spring-boot:run
```

Проверка (нужен валидный токен доступа от gateway):

```bash
curl -H "Authorization: Bearer <ACCESS_TOKEN>" \
  http://localhost:8083/api/broker/secure-sample
```

## Ограничения

Реальной торговой логики нет, ручки демонстрационные.
