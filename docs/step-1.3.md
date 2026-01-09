# Отчёт 1.3

## 1) Что добавили в шаге 1.3

### 1.1 RBAC модель прав (permissions)

**Цель:** не просто роли, а «роли → права» + «пользователь → переопределения прав» (allow/deny, deny важнее) — чтобы дальше на сервисах можно было проверять доступ к операциям.

Схема уже была заведена (таблицы `permissions`, `role_permissions`, `user_permission_overrides`), но в 1.3:

- добавлена миграция `V2__seed_rbac_stub.sql` с **6 заглушками** прав и маппингом на роли USER/ADMIN (шаг 1.3);
- реализован расчёт эффективных прав пользователя (`PermissionService`): права из ролей + переопределения; если override = deny — право убирается (шаг 1.3);
- access token теперь содержит:
  - `uid` — id пользователя
  - `roles` — роли
  - `perms` — эффективные права (шаг 1.3)

Заглушки прав:

- `MARKETDATA_READ`
- `ALERTS_READ`
- `AUDIT_READ`
- `BROKER_READ`
- `BROKER_TRADE`
- `RBAC_MANAGE`

Маппинг:

- USER: `MARKETDATA_READ`, `ALERTS_READ`, `BROKER_READ`
- ADMIN: все 6

### 1.2 Межсервисная безопасность (проверка access token в остальных сервисах)

**Цель:** downstream сервисы должны уметь валидировать access token от gateway.

Сделано:

- в каждом сервисе добавлен readme.md
- в `market-data-service`, `alerts-service`, `virtual-broker-service` добавлен Spring Security resource-server (шаг 1.3);
- реализован конвертер `JwtAuthConverter`: claims `roles`/`perms` → authorities `ROLE_*` / `PERM_*` (шаг 1.3);
- добавлены демонстрационные защищённые ручки:
  - Market Data: `GET /api/market-data/secure-sample` (требует `PERM_MARKETDATA_READ`, в шаге 1.6 заменено на `/api/market/v1/**`)
  - Alerts: `GET /api/alerts/secure-sample` (требует `PERM_ALERTS_READ`)
  - Virtual Broker: `GET /api/broker/secure-sample` (требует `PERM_BROKER_READ`), `POST /api/broker/trade-sample` (требует `PERM_BROKER_TRADE`)

### 1.3 Telegram как портал: bot-adapter + command-center + external_accounts

**Идея:** Telegram - дополнительный портал входа и взаимодействия, но **логин остаётся единым**.
Telegram id (externalUserId) **привязывается** к пользователю через таблицу `external_accounts`.

Ключевая архитектурная правка (шаг 1.3):

- **gateway не занимается Telegram транспортом** (webhook/polling) и не парсит команды
- Telegram вынесен в отдельный сервис-адаптер `api-telegram-service`
- единая обработка команд вынесена в `logic-commands-center-service`

Что где находится:

1) `api-telegram-service` (адаптер канала)
   - принимает сообщения (webhook) или через dev-endpoint
   - отправляет в command-center **сырое** `who + text`
   - отправляет текст ответа обратно пользователю через Bot API
   - здесь же хранится `TELEGRAM_BOT_TOKEN` (env)

2) `logic-commands-center-service` (мозг)
   - парсит команды (`/login`, `/register`, `/market` и т.д.)
   - при необходимости вызывает gateway internal API, чтобы:
     - определить, привязан ли внешний аккаунт (resolve)
     - выполнить login/register + link
     - получить access token
   - затем вызывает downstream сервисы с JWT и формирует ответ

3) `api-gateway-service` (identity)
   - хранит `external_accounts(providerCode, externalUserId)`
   - выдаёт access token с `uid`, `roles`, `perms`
   - предоставляет internal API (под shared-token `INTERNAL_API_TOKEN`):
     - `POST /internal/identity/resolve`
     - `POST /internal/auth/register-and-link`
     - `POST /internal/auth/login-and-link`
     - `POST /internal/auth/issue-access`

Почему bot token нужен, даже если мы тестируем через `/dev/telegram/message`:

- для реальной работы через Telegram (webhook или long-polling) нужно уметь отправлять ответ через Bot API (`sendMessage`) и/или получать апдейты (`getUpdates`)
- dev-endpoint позволяет учиться без реального Telegram (и без токена), но это только режим отладки

### 1.4 Интеграционные тесты (Testcontainers)

Добавлен минимальный IT-тест в gateway: `AuthFlowIT`.

- поднимает PostgreSQL через Testcontainers
- прогоняет сценарий: register → login → refresh (шаг 1.3)
- rate limit сервис замокан, чтобы не зависеть от Redis

### 1.5 Готовность к Kafka (точка расширения)

В `api-gateway-service` добавлен интерфейс `AuthEventPublisher`.

- `AuthAuditService` сохраняет событие в БД и вызывает publisher (шаг 1.3)
- сейчас стоит `NoopAuthEventPublisher` (ничего не публикует)
- дальше можно заменить на Kafka/Rabbit реализацию без переделки бизнес-кода

## 2) Проверить руками

1) Поднять инфраструктуру:

```bash
cd infra
docker compose up -d
```

2) Задать переменные окружения (минимум):

- `JWT_SECRET` (одинаковый для всех сервисов)
- `INTERNAL_API_TOKEN` (одинаковый для gateway и command-center) (шаг 1.3)

Опционально:

- `TELEGRAM_BOT_TOKEN` — нужен только если вы подключили реальный Telegram webhook и хотите отправлять ответы через Bot API.

3) Запустить сервисы.

- `start-all.cmd` — поднимает infra и все сервисы (шаг 1.3)(.gitignore)

4) Проверка сценария через dev-endpoint (без Telegram):

```bash
curl -X POST http://localhost:8084/dev/telegram/message \
  -H "Content-Type: application/json" \
  -d '{"telegramUserId": 1001, "chatId": 2001, "text": "/help"}'
```

Можно пройти полный поток:

- `/register` -> затем сообщением: `login password`
- `/me`
- `/market` / `/alerts` / `/broker` / `/trade`

## 3) Что сознательно оставили «заглушками»

- permissions — пока демонстрационные: реальные бизнес-операции downstream сервисов ещё не готовы.
- Telegram:
  - реализован "тонкий" адаптер + command-center, но полноценное подключение webhook требует публичного URL
  - state диалога хранится в памяти (TTL), для нескольких реплик нужно вынести в Redis
  - Mini Apps пока не реализованы; если добавлять, логично держать их в `api-telegram-service` (шаг 1.3)
