# Заметки по реализации (ассистент)

Эти заметки - карта проекта: где что лежит и как быстро найти нужную точку расширения.
Цель - чтобы можно было разобраться без чтения всего кода.

## 1) Общая архитектура (шаги 1.5-1.7)

**V1 поток (текстовый ответ):**

1. Telegram -> `api-telegram-service` (`/telegram/webhook` или polling).
2. `api-telegram-service` извлекает `{channel=telegram, externalUserId, chatId, messageId, text|callbackData}`.
3. `api-telegram-service` вызывает `logic-commands-center-service`.
4. `logic-commands-center-service`:
   - парсит команду,
   - запрашивает идентификацию/права в `api-gateway-service`,
   - проверяет тумблеры команд,
   - вызывает downstream-сервисы,
   - формирует `ChatResponse`.
5. `api-telegram-service` отправляет ответы (send/edit/delete + inline keyboard + PRE).

**V2 поток (блочные ответы):**

1. Telegram -> `api-telegram-service`.
2. Адаптер формирует `ChatMessageEnvelope` с `correlationId/sessionId`.
3. `logic-commands-center-service` возвращает `ChatResponseV2` с блоками.
4. `TelegramRendererV2` рендерит блоки, кнопки и пагинацию.

**Почему V2 нужен:**
- логика ответа отделяется от Telegram-специфики (подготовка к новым каналам);
- блоки проще расширять и комбинировать (меню, подсказки, таблицы);
- единый контракт облегчает поддержку UX и навигации;
- структура блоков снижает зависимость от конкатенации строк при росте меню.

**Источник истины по пользователям и правам:** `api-gateway-service` (Postgres + Flyway).

## 2) Контракты между сервисами

### 2.1 ChatMessageEnvelope (вход logic)

Поля:
- `channel` - имя канала (`telegram`).
- `externalUserId` - ID пользователя в канале.
- `chatId` - ID чата.
- `messageId` - ID сообщения (нужен для edit/delete).
- `text` - текст команды.
- `callbackData` - данные кнопки (inline keyboard).
- `correlationId`, `sessionId`, `locale` - метаданные для трассировки и диалога.

`callbackData` имеет приоритет над `text`. Формат кнопок: `cmd:<command>:<arg>`.
Пример: `cmd:commands:page=2`.

### 2.2 ChatResponse (V1)

`ChatResponse.messages[]` содержит `OutgoingMessage` с `uiHints`:
- `preferEdit` - редактировать сообщение, если возможно.
- `deleteSourceMessage` - удалить исходное сообщение пользователя.
- `renderMode=PRE` - рисовать моноширинную таблицу (`<pre>` в Telegram).
- `inlineKeyboard` - набор кнопок.

### 2.3 ChatResponseV2 (V2)

Ответ состоит из блоков: `TEXT`, `NOTICE`, `LIST`, `TABLE`, `SECTIONS`, `ERROR`, `ACTIONS`.
Это позволяет разделить **содержание** и **отображение**, а адаптеру отрисовать нужный UI.
Для таблиц используется `TableBlock`, поле `format` может быть `pretty`.

## 3) api-gateway-service (идентификация, RBAC, консоль разработчика)

### Где смотреть реализацию

- API аутентификации: `auth/api/AuthController`.
- Сервисы аутентификации: `auth/service/*`.
- Безопасность: `auth/security/*`.
- Внутренние API: `internal/api/*Controller`.
- Консоль разработчика: `internal/service/DevConsoleBootstrapper`, `CommandSwitchService`, `UserHardDeleteService`, `DbConsoleService`.

### Важные решения

- **Сырые/эффективные права (raw/effective)**:
  - raw = роли + overrides (deny отменяет allow),
  - effective = raw + разворачивание `DEVGOD` во все права.
- Для полного удаления (hard delete) проверяются raw `DEVGOD + USERS_HARD_DELETE`.
- `/internal/commands/list` доступен только по internal token (без actor/perms).

### База и миграции

Миграции лежат в `services/api-gateway-service/src/main/resources/db/migration`.

## 4) logic-commands-center-service (обработка команд)

### Ключевые точки кода

- `domain/ChatCommandHandler` - основной обработчик V1.
- `domain/v2/ChatCommandHandlerV2` - обработчик V2.
- `domain/CommandRegistry` - декларативный список команд.
- `domain/CommandSwitchCache` - TTL-кеш тумблеров.
- `domain/ChatStateStore` - in-memory state.
- `client/GatewayInternalClient` - вызовы internal API gateway.
- `client/DownstreamClients` - вызовы market/alerts/broker.

### Логика команд

- `/help` и `/menu` в V2 строятся через `HelpBuilder` и `MenuBuilder`.
- `/market` поддерживает подкоманды `instruments`, `quote`, `candles`, `orderbook`, `trades`.
- `/db_menu` и `/db` - dev-консоль для SQL в gateway DB (таблица может отрисовываться внутри меню).
- `/db_tables`, `/db_describe`, `/db_history` - быстрые команды меню БД.
- Форматирование таблиц в `/db` переключается кнопками `Форматировать`/`Сырой вид`.
- В формате `pretty` показывается первый столбец + текущий, навигация кнопками `Столбец назад/дальше`.
- `/menu_account` и `/me` скрывают User ID/роли/права для неадминов; имя не выводится.
- Успешные `/login` и `/register` добавляют кнопку «Главное меню».

### Состояния чата (ChatState)

- `AWAIT_LOGIN_CREDENTIALS`
- `AWAIT_REGISTER_CREDENTIALS`
- `AWAIT_LOGOUT_CONFIRM`
- `AWAIT_USER_HARD_DELETE_CONFIRM`
- `MARKET_INSTRUMENTS_PAGE`
- `DB_LAST_QUERY`

Ключи состояния:
- для рынка: `sessionId|market`;
- для БД: `sessionId|db` (хранит SQL, формат, контекст меню и индекс столбца).

## 5) api-telegram-service (адаптер Telegram)

### Основные классы

- `TelegramWebhookController` - обработка webhook и callback_query.
- `TelegramPollingRunner` - режим длинного опроса (long-polling).
- `TelegramBotClient` - клиент Bot API.
- `TelegramRendererV2` - рендер блоков `ChatResponseV2`.

### Что важно

- `preferEdit=true` -> `editMessageText` вместо `sendMessage`.
- `renderMode=PRE` -> текст экранируется и оборачивается в `<pre>`.
- `inlineKeyboard` -> Telegram `inline_keyboard`.
- `deleteSourceMessage=true` -> удаляется сообщение пользователя.
- Для callback `cmd:db:*` блоки объединяются в одно сообщение (меню + таблица + кнопки), чтобы ответ редактировал текущее сообщение.

## 6) Нижестоящие сервисы (market/alerts/broker)

- Все используют Spring Security как ресурсный сервер.
- `JwtAuthConverter` маппит claims `roles`/`perms` в `ROLE_*/PERM_*`.
- Ручки защищены через `@PreAuthorize`.

## 7) market-data-service

- REST API: `/api/market/v1/**` (инструменты, котировки, свечи, стакан, сделки).
- Сервис валидирует JWT (ресурсный сервер); `PERM_MARKETDATA_READ` обязателен.
- Добавлена ручка статуса рынка: `/api/market/v1/status`.
- Интеграция с MOEX ISS: `client/MoexClient` + Caffeine cache TTL.

## 8) Где расширять систему

- Новая команда: `CommandRegistry` + реализация в `ChatCommandHandler` или `ChatCommandHandlerV2`.
- Новое permission: миграция в gateway + логика в `PermissionService`.
- Новый канал (адаптер): отдельный сервис, формирующий `ChatMessageEnvelope`.
