# patch.md — Step 1.5

Источник правды: `docs/tz-step-1.5.md`

## Статус

### Gateway
- [x] Flyway V4: DEVONLYADMIN/DEVGOD/COMMANDS_TOGGLE/USERS_HARD_DELETE + `command_switches`
- [x] raw/effective perms (DEVGOD)
- [x] dev console bootstrap по env
- [x] internal endpoints `/internal/commands/*`, `/internal/users/hard-delete`
- [x] `/internal/rbac/users/list` сортировка по id

### Logic
- [x] DTO: callbackData + uiHints + inline keyboard
- [x] registry команд + фильтрация `/help` и `/helpdev` (включая dev-команды)
- [x] switches cache (fail-open)
- [x] `/commands`, `/command enable|disable`, `/user delete` + подтверждение
- [x] вывод `/help`, `/helpdev`, `/commands`, `/users`, `/roles`, `/perms` в обычном списке (без PRE)

### Telegram
- [x] callback_query + answerCallbackQuery
- [x] editMessageText + deleteMessage
- [x] inline keyboard + поддержка PRE render (если приходит renderMode=PRE)

### Документация
- [x] `docs/step-1.5.md`
- [x] `для себя/dev_log.md`, `для себя/impl_notes.md`
- [x] README root + сервисов (gateway/logic/telegram)
- [x] удалить черновик `docs/t.md`

## Принятые решения
- LICENSE не добавляем.
- `/internal/commands/list` без actor/RBAC.
- DEVGOD: raw vs effective; hard-delete проверяет RAW (DEVGOD + USERS_HARD_DELETE).
- Switches: fail-open, TTL 5–15 сек.
- `toggleable=false` для `/help`, `/helpdev`, `/commands`, `/command`.
- Hard delete самого себя запрещён.
