# Журнал разработки (ассистент)

Формат записи:
- Дата/время
- Что сделано
- Какие решения/договорённости зафиксированы
- Где лежит код/документация

---

## 2026-01-11

### Что сделано (шаг 1.7)

  - Логика:
    - добавлено меню работы с SQL в `/menu` (кнопка и строка в тексте);
    - `/db_menu` сохраняет контекст и добавляет таблицы снизу, не теряя кнопки;
    - добавлено форматирование таблиц через кнопку `Форматировать` (переключение сырой/pretty);
    - форматирование БД теперь листает столбцы: фиксированный первый + текущий, навигация кнопками;
    - кнопка из таблицы ведёт в `БД меню`, `/db_menu` больше не схлопывается в `/db menu`;
    - убраны заголовки «БД: ...» в табличных ответах;
    - в `/menu_account` скрыты User ID/роли/права для неадминов; имя убрано;
    - в успешном `/login`/`/register` добавлена кнопка в главное меню;
    - поддержаны алиасы `/db_tables`, `/db_history`, `/db_describe`;
    - примеры тикеров приведены к виду `*Id*`.
  - Telegram:
    - рендер таблиц поддерживает формат `pretty` (ASCII рамки).
    - DB-ответы по callback (`cmd:db:*`) объединяются в одно сообщение (редактирование вместо нового).
- Документация:
  - обновлены внутренние заметки и README под новые команды и причины V2.

### Принятые решения

- Для форматирования таблиц используется хранение последнего SQL в `ChatStateStore` (ключ `sessionId|db`).
- В `/db_menu` таблицы отрисовываются внутри одного сообщения (меню + таблица + кнопки).
- Форматированный вид таблицы показывает первый столбец + один текущий с пагинацией по столбцам.

### Где искать

- `/menu` и кнопки: `services/logic-commands-center-service/src/main/java/com/logicsignalprotector/commandcenter/domain/v2/ChatCommandHandlerV2.java`.
- DB консоль и форматирование: `services/logic-commands-center-service/src/main/java/com/logicsignalprotector/commandcenter/domain/v2/ChatCommandHandlerV2.java`.
- Рендер `pretty` таблиц: `services/api-telegram-service/src/main/java/com/logicsignalprotector/apitelegram/render/TelegramRendererV2.java`.

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
  - переписаны `README.md` каждого сервиса с описанием "что делает" + "как работает" + "как реализовано";
  - добавлены/обновлены `docs/step-1.5.md`, `patch.md`, `для себя/dev_log.md`, `для себя/impl_notes.md`;
  - обновлены внутренние заметки `internal_docs/assistant_notes/*`.

### Принятые решения (зафиксированы в документации)

- `DEVGOD` разворачивается только в **effective** permissions; hard delete проверяет **raw**.
- `/internal/commands/list` доступен по internal token без actor/RBAC.
- Command switches: fail-open + короткий TTL.
- Команды управления (`/help`, `/helpdev`, `/commands`, `/command`) - `toggleable=false`.
- Hard delete самого себя запрещён.

## 2026-01-10

### Что сделано (шаг 1.6)

- Market Data:
  - добавлена интеграция с MOEX ISS (WebClient + Caffeine cache);
  - реализован слой use-case и REST API `/api/market/v1/**`;
  - включены валидации параметров и RBAC (`PERM_MARKETDATA_READ`).
- Logic:
  - `/market` переведён на подкоманды (`instruments`, `quote`, `candles`, `orderbook`, `trades`) и вызывает реальные эндпоинты market-data.
- Документация:
  - обновлены README (root + market-data + logic);
  - обновлён ТЗ шага 1.6 (про прямой доступ и отсутствие proxy в gateway);
  - обновлены internal notes.

### Принятые решения (зафиксированы в документации)

- API market-data в шаге 1.6 не проксируется через gateway; клиенты ходят напрямую с ????? ???????.
- `MARKETDATA_ADMIN` не вводим до появления админ-ручек.

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
