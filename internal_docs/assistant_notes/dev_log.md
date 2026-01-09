# Dev log (assistant)

Формат записи:
- Дата/время
- Что сделано
- Какие решения/договорённости зафиксированы
- Где лежит код/документация

---

## 2026-01-09

### Что сделано (шаг 1.5)

- Gateway:
  - добавлены новые роли/права для dev-консоли (`DEVONLYADMIN`, `DEVGOD`, `COMMANDS_TOGGLE`, `USERS_HARD_DELETE`);
  - реализованы command switches (`command_switches`, internal API `/internal/commands/*`);
  - добавлен hard delete пользователя через internal API + проверки RAW perms + запрет self-delete;
  - bootstrap dev-консоли по env (`DEV_CONSOLE_ENABLED`, `DEV_CONSOLE_USER_IDS`);
  - обновлён PermissionService (raw vs effective perms).
- Logic:
  - добавлен registry команд + enforcement тумблеров (fail-open + TTL);
  - реализованы новые команды `/helpdev`, `/commands`, `/command enable|disable`, `/user delete` с подтверждением;
  - поддержаны UI hints (inline keyboard, preferEdit, deleteSourceMessage, PRE).
- Telegram:
  - добавлена обработка `callback_query` и inline buttons;
  - поддержаны `editMessageText`, `deleteMessage`, `answerCallbackQuery`;
  - рендер таблиц через `<pre>` с HTML-экранированием.
- Документация:
  - переписаны `README.md` каждого сервиса с описанием “что делает” + “как работает” + “как реализовано”;
  - добавлены/обновлены `docs/step-1.5.md`, `patch.md`, `для себя/dev_log.md`, `для себя/impl_notes.md`;
  - обновлены внутренние заметки `internal_docs/assistant_notes/*`.

### Принятые решения (зафиксированы в документации)

- `DEVGOD` разворачивается только в **effective** permissions; hard delete проверяет **raw**.
- `/internal/commands/list` доступен по internal token без actor/RBAC.
- Command switches: fail-open + короткий TTL.
- Команды управления (`/help`, `/helpdev`, `/commands`, `/command`) — `toggleable=false`.
- Hard delete самого себя запрещён.

### Где искать

- Код: `services/api-gateway-service`, `services/logic-commands-center-service`, `services/api-telegram-service`.
- Документация: `services/*/README.md`, `docs/step-1.5.md`, `internal_docs/assistant_notes/*`, `для себя/*`.

## 2026-01-08

- Старт подготовки шага **1.5** на базе архива проекта `logic-signal-protector-1.4step-commit+push.zip`.
- Наведён порядок в документации:
  - удалён черновой файл `docs/t.md` (пустой шаблон отчёта, не описывал реальную реализацию);
  - добавлено ТЗ шага 1.5 в `docs/step-1.5.md`;
  - добавлен корневой `README.md` с описанием архитектуры и потоков;
  - переработаны `README.md` каждого сервиса под единый подробный формат.
