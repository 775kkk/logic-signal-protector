# Шаг 1.5 — Dev Console, Command Switches, /help по ролям, Telegram UI

## Что сделано

### api-gateway-service
- Добавлены роли/права DEVONLYADMIN, DEVGOD, COMMANDS_TOGGLE, USERS_HARD_DELETE (Flyway V4).
- Таблица `command_switches` для тумблеров команд.
- Internal API:
  - `GET /internal/commands/list`
  - `POST /internal/commands/set-enabled`
  - `POST /internal/users/hard-delete`
- Bootstrap dev console: выдача роли DEVONLYADMIN по `DEV_CONSOLE_ENABLED` + `DEV_CONSOLE_USER_IDS`.
- `PermissionService`: raw vs effective perms (DEVGOD разворачивается только в effective).
- `/internal/rbac/users/list` сортирует по id.

### logic-commands-center-service
- Единый registry команд + фильтрация для `/help` и `/helpdev`.
- Проверка command switches (fail-open) + кэш с TTL.
- Новые команды:
  - `/helpdev`
  - `/commands` (список тумблеров)
  - `/command enable|disable <code>`
  - `/user delete <login|id>` (с подтверждением)
- Табличный рендер (`RenderMode.PRE`) для списков.
- CallbackData поддержка (`cmd:commands:page=N`) + inline keyboard.

### api-telegram-service
- Обработка `callback_query`.
- `editMessageText`, `deleteMessage`, `answerCallbackQuery`.
- Рендер таблиц через `<pre>` + `parse_mode=HTML`.
- Inline keyboard поддержка.

---

## Новые роли/права

**Роли**:
- `DEVONLYADMIN` (bootstrap через env)

**Permissions**:
- `DEVGOD`
- `COMMANDS_TOGGLE`
- `USERS_HARD_DELETE`

---

## Команды (кратко)

**Базовые**:
- `/help`, `/login`, `/register`, `/logout`, `/me`, `/market`, `/alerts`, `/broker`, `/trade`

**Admin (RBAC)**:
- `/users`, `/user <login>`, `/roles`, `/perms`
- `/grantrole`, `/revokerole`, `/grantperm`, `/denyperm`, `/revokeperm`

**Dev**:
- `/helpdev`
- `/commands`
- `/command enable|disable <code>`
- `/user delete <login|id>`

---

## Спорные решения (зафиксировано)

1. LICENSE не добавляем (явное требование владельца).
2. `/internal/commands/list` — только internal token, без actor/RBAC.
3. DEVGOD: raw vs effective; hard-delete проверяет RAW (DEVGOD + USERS_HARD_DELETE).
4. Command switches: fail-open, TTL 5–15 сек.
5. `toggleable=false` для `/help`, `/helpdev`, `/commands`, `/command`.
6. Запрет hard delete самого себя.

---

## Переменные окружения

**Gateway**:
- `DEV_CONSOLE_ENABLED`
- `DEV_CONSOLE_USER_IDS`

**Logic**:
- `DEV_CONSOLE_ENABLED`
- `COMMAND_SWITCH_CACHE_TTL` (например `PT10S`)
- `CHAT_HARD_DELETE_CONFIRM_TTL` (например `PT60S`)

---

## Telegram UI

- Inline keyboard через `callback_query`.
- Pagination для `/commands`.
- Prefer edit: ответы могут редактировать предыдущее сообщение.
- Парольные сообщения удаляются после успешного login/register.
