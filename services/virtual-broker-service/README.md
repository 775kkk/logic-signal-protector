# virtual-broker-service

## Назначение

Сервис виртуального брокера/исполнения сделок (пока каркас). На шаге 1.3 добавлена межсервисная безопасность: сервис валидирует JWT access token от gateway и проверяет permissions.

- Проверка access token (resource-server) (шаг 1.3)
- Демонстрация permission-check на заглушках (шаг 1.3)

## Ручки

- `GET /ping` — открытая проверка доступности
- `GET /api/broker/secure-sample` — требует `PERM_BROKER_READ` (шаг 1.3)
- `POST /api/broker/trade-sample` — требует `PERM_BROKER_TRADE` (шаг 1.3)

## Конфигурация

- `JWT_SECRET` должен совпадать с gateway (шаг 1.3)
- issuer по умолчанию `lsp-api-gateway` (шаг 1.3)

## Ограничения

Эндпоинты в сервисе пока демонстрационные. Реальные сделки/портфель/позиции будут добавляться позже.
