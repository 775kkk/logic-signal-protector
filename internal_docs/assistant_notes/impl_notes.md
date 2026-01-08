# Implementation notes (assistant)

Эти заметки — «карта проекта» для разработчика: где что лежит и как быстро найти нужную точку расширения.

## 1) Общая архитектура (текущая, шаг 1.4)

**Поток Telegram-команды (транспорт = Telegram message):**

1. Telegram → `api-telegram-service` (`POST /telegram/webhook`)
2. `api-telegram-service` извлекает `{provider=telegram, externalUserId, chatId, messageId, text}`
3. `api-telegram-service` вызывает `logic-commands-center-service` (HTTP)
4. `logic-commands-center-service`:
   - парсит команду и алиасы
   - при необходимости запрашивает identity/права через internal API gateway
   - вызывает бизнес-сервисы (market-data/alerts/virtual-broker)
   - формирует `ChatResponse` (список сообщений)
5. `api-telegram-service` отправляет ответы пользователю через Bot API (сейчас: только `sendMessage`).

**Identity и RBAC — источник истины:** `api-gateway-service` (Postgres + Flyway).

## 2) Internal API gateway (для адаптеров и command-center)

Все ручки `/internal/**` защищены shared token:
- HTTP header: `X-Internal-Token: <INTERNAL_API_TOKEN>`
- Значение задаётся в env и должно совпадать у клиентов.

Ключевые эндпойнты:
- `/internal/identity/resolve` — найти внутреннего пользователя по внешнему аккаунту.
- `/internal/auth/register-and-link` — регистрация + привязка внешнего аккаунта.
- `/internal/auth/login-and-link` — логин + привязка внешнего аккаунта.
- `/internal/auth/issue-access` — выдача access token по уже привязанному внешнему аккаунту.
- `/internal/identity/unlink` — отвязка внешнего аккаунта (шаг 1.4, logout семантика).

## 3) Где добавлять новые команды

- Парсер/роутинг команд: `logic-commands-center-service`.
- Если команда требует права:
  - права объявляются в gateway (Flyway: `permissions`, `roles`, ...)
  - `logic` проверяет `perms` (эффективные) перед выполнением.

## 4) Документация

Правило проекта: документация должна отвечать на «что делает» и «как именно реализовано», без чтения кода.

- `docs/step-*.md` — дневник по шагам.
- `services/*/README.md` — подробная документация по каждому сервису.
- `internal_docs/assistant_notes/*` — рабочие заметки.
