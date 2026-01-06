# market-data-service

## Назначение

Сервис рыночных данных (пока каркас). В шаге 1.3 добавлена валидация JWT access token, чтобы сервисы умели проверять токен, выданный gateway.

- Проверка access token (Spring Security resource-server) (шаг 1.3)
- Демонстрация permission-check на заглушке ручки (шаг 1.3)

## Что есть сейчас

- `GET /ping` — открытая проверка доступности (шаг 1.2/1.3)
- `GET /api/market-data/secure-sample` — защищённая ручка
  - требует `PERM_MARKETDATA_READ` (шаг 1.3)
  - возвращает claims из JWT для наглядности

## Конфигурация

- `security.jwt.secret` берётся из `JWT_SECRET` (должен совпадать с gateway) (шаг 1.3)
- `security.jwt.issuer` по умолчанию `lsp-api-gateway` (шаг 1.3)

## Ограничения

Это пока демонстрационный каркас: реальная бизнес-логика/контракты будут добавляться позже.
