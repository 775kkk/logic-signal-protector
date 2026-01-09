# Implementation notes (assistant)

Эти заметки — «карта проекта» для разработчика: где что лежит и как быстро найти нужную точку расширения.
Цель — чтобы можно было разобраться без чтения всего кода.

## 1) Общая архитектура (шаг 1.5)

**Основной поток Telegram-команды:**

1. Telegram → `api-telegram-service` (`/telegram/webhook` или polling).
2. `api-telegram-service` извлекает `{channel=telegram, externalUserId, chatId, messageId, text|callbackData}`.
3. `api-telegram-service` вызывает `logic-commands-center-service` (HTTP).
4. `logic-commands-center-service`:
   - парсит команду,
   - запрашивает identity/права в `api-gateway-service`,
   - проверяет тумблеры команд,
   - вызывает downstream-сервисы,
   - формирует `ChatResponse`.
5. `api-telegram-service` отправляет ответы (send/edit/delete + inline keyboard + PRE).

**Источник истины по пользователям и правам:** `api-gateway-service` (Postgres + Flyway).

## 2) Контракты между сервисами

### 2.1 ChatMessageEnvelope (logic input)

Поля:
- `channel` — имя канала (`telegram`).
- `externalUserId` — ID пользователя в канале.
- `chatId` — ID чата.
- `messageId` — ID сообщения (нужен для edit/delete).
- `text` — текст команды.
- `callbackData` — данные кнопки (inline keyboard).

`callbackData` имеет приоритет над `text`. Формат кнопок: `cmd:<command>:<arg>`.
Пример: `cmd:commands:page=2`.

### 2.2 ChatResponse (logic output)

`ChatResponse.messages[]` содержит `OutgoingMessage` с `uiHints`:
- `preferEdit` — редактировать сообщение, если возможно.
- `deleteSourceMessage` — удалить исходное сообщение пользователя.
- `renderMode=PRE` — рисовать моноширинную таблицу (`<pre>` в Telegram).
- `inlineKeyboard` — набор кнопок.

## 3) api-gateway-service (identity, RBAC, dev console)

### Где смотреть реализацию

- Auth API: `auth/api/AuthController`.
- Auth core: `auth/service/*` (`UserService`, `TokenService`, `RefreshTokenService`, `PermissionService`).
- Security: `auth/security/*` (`SecurityConfig`, `JwtAuthConverter`, `InternalApiAuthFilter`).
- Internal API: `internal/api/*Controller`.
- Dev console: `internal/service/DevConsoleBootstrapper`, `CommandSwitchService`, `UserHardDeleteService`.

### Важные решения

- **Raw vs Effective permissions**:
  - raw = роли + overrides (deny отменяет allow),
  - effective = raw + разворачивание `DEVGOD` во все права.
- Для **hard delete** проверяются **raw** `DEVGOD + USERS_HARD_DELETE`.
- `/internal/commands/list` доступен только по internal token (без actor/perms) — нужно для logic.

### База и миграции

Миграции лежат в `services/api-gateway-service/src/main/resources/db/migration`.
V4 добавляет `command_switches` и новые permissions.

## 4) logic-commands-center-service (обработка команд)

### Ключевые точки кода

- `domain/ChatCommandHandler` — основной обработчик.
- `domain/CommandRegistry` — декларативный список команд (код, права, toggleable).
- `domain/CommandSwitchCache` — TTL-кеш тумблеров (fail-open при сбое).
- `domain/ChatStateStore` — in-memory state (login/register/logout/hard delete).
- `client/GatewayInternalClient` — вызовы internal API gateway.
- `client/DownstreamClients` — вызовы market/alerts/broker.

### Логика команд

- `/help` и `/helpdev` формируются из `CommandRegistry` + фильтр по правам и тумблерам.
- `/commands` показывает все команды с состоянием тумблера и делает пагинацию через inline keyboard.
- `/command enable|disable` вызывает gateway `internal/commands/set-enabled`.
- `/user delete` требует подтверждение `DELETE <login|id>` (TTL), затем вызывает gateway hard-delete.
- `/login` и `/register` могут запрашивать логин/пароль отдельным сообщением;
  после успеха выставляется `deleteSourceMessage=true` (чтобы удалить пароль).

### Состояния чата (ChatState)

- `AWAIT_LOGIN_CREDENTIALS`
- `AWAIT_REGISTER_CREDENTIALS`
- `AWAIT_LOGOUT_CONFIRM`
- `AWAIT_USER_HARD_DELETE_CONFIRM`

Ключ состояния: `channel|externalUserId|chatId`.

## 5) api-telegram-service (адаптер Telegram)

### Основные классы

- `TelegramWebhookController` — обработка webhook и callback_query.
- `TelegramPollingRunner` — long-polling режим.
- `TelegramBotClient` — низкоуровневый клиент Bot API.

### Что важно

- `preferEdit=true` → `editMessageText` вместо `sendMessage`.
- `renderMode=PRE` → текст экранируется и оборачивается в `<pre>`.
- `inlineKeyboard` → преобразуется в Telegram `inline_keyboard`.
- `deleteSourceMessage=true` → удаляется сообщение пользователя.

## 6) Downstream-сервисы (market/alerts/broker)

- Все используют Spring Security resource-server.
- `JwtAuthConverter` маппит claims `roles`/`perms` в `ROLE_*/PERM_*`.
- Ручки защищены через `@PreAuthorize`.

## 7) Где расширять систему

- Новая команда: `CommandRegistry` + реализация в `ChatCommandHandler`.
- Новое permission: миграция в gateway + логика в `PermissionService`.
- Новый канал (адаптер): отдельный сервис, формирующий `ChatMessageEnvelope`.
