# Шаг 1.7 - Консольное ядро v2 (блоки, /menu, V2-рендер)

## Что сделано

### logic-commands-center-service
- Добавлен контракт `ChatResponseV2` с блоками (`TEXT`, `NOTICE`, `TABLE`, `LIST`, `SECTIONS`, `ERROR`, `ACTIONS`) и метаданными `correlationId/sessionId/locale`.
- Введён V2 обработчик команд для `/help`, `/menu`, `/market` с генерацией структурных блоков.
- Добавлены `HelpBuilder` и `MenuBuilder` (группировка по секциям, базовые подсказки).
- Подключён новый endpoint: `POST /internal/chat/message/v2`.
- Добавлены `/db_menu` и `/db <SQL>` для dev-консоли с табличным выводом.
- Обновлено главное меню: кнопки и разделы формируются по правам, добавлена «Работа с SQL» для dev.
- В `/menu_account` скрыты User ID/роли/права для неадминов; имя не выводится.
- В `/db` добавлено форматирование таблиц (сырой/pretty) и пагинация столбцов с возвратом в БД меню.

### api-telegram-service
- Добавлены V2 модели DTO, `TelegramRendererV2` и поддержка блоков.
- Внедрены `FriendlyMessageTemplates` с шаблонами сообщений (стилистика Persona).
- Рендер таблиц поддерживает `format=pretty` (ASCII) и объединяет кнопки с текстом ответа.
- Telegram webhook/polling маршрутизирует `/help`, `/menu`, `/market` и соответствующие callback-и через V2.
- Генерация `correlationId` на каждый вход и `sessionId` для V2 сценариев (help/menu/market).
- Dev endpoint: `POST /dev/telegram/message/v2` для теста V2.

### api-gateway-service
- Добавлена internal ручка `POST /internal/db/query` для dev SQL консоли.

### market-data-service
- Добавлена ручка `GET /api/market/v1/status` для статуса торгов (MOEX).

---

## Контракт ответов V2

`ChatResponseV2` содержит:
- `blocks`: список блоков для отображения
- `correlationId`, `sessionId`, `locale`
- `uiHints` (резерв под preferEdit/deleteSourceMessage/ttlSeconds)

Блоки:
- `TEXT` и `NOTICE` - простые текстовые ответы
- `TABLE` - таблица колонок/строк (рендерится через `<pre>` в Telegram)
- `LIST` - список пунктов
- `SECTIONS` - секции для `/help` и `/menu`
- `ERROR` - ошибки (code/message/hint)
- `ACTIONS` - inline-кнопки

---

## Маршрутизация V2

- V2 включается для `/help`, `/menu`, `/market`, `/db`, `/db_menu` и связанных callback payloads.
- Остальные команды пока остаются на V1 (`ChatResponse`).

---

## /help и /menu

- `/help` и `/menu` возвращают `SECTIONS` + `NOTICE`.
- Пагинация через callback payloads:
  - `/help`: `h:<sessionId>:<pageIndex>`
  - `/menu`: `m:<sessionId>:<pageIndex>`
- Для `/menu` добавлены быстрые действия по модулям (Биржа/Dev/Аккаунт) через `ACTIONS`.

---

## /market (V2)

- Команды: `/market_instruments`, `/market_quote`, `/market_candles`, `/market_orderbook`, `/market_trades`
  (допускается формат `/market <subcommand>` для совместимости).
- Результаты возвращаются как `TABLE`/`SECTIONS` + `NOTICE`.
- При отсутствии данных возвращается `NOTICE` с шаблоном `market_empty`.

---

## Ограничения и ещё не закрыто

- `/help` и `/menu` пагинируются внутри ядра (возвращается одна секция), а не целиком с пагинацией в адаптере.
- Рендер `TABLE` остаётся через `<pre>` (включая `pretty`), карточного режима нет.
- `ErrorBlock` не добавляет спец-детали для `ADMIN_ANSWERS_LOG`.
- `sessionId` не интегрирован в `ChatStateStore` для многошаговых сценариев.
- `correlationId/sessionId` не проксируются для V1 команд.
- `displayName` из gateway и корреляция на downstream DTO пока не внедрены.
- Тесты (unit/integration/e2e) не добавлены.

---

## Шаблоны FriendlyMessageTemplates

Ключи (default):
- `welcome`
- `help_intro`
- `menu_intro`
- `login_success`
- `market_empty`
- `market_help`
- `error_default`

Шаблоны настраиваются в `services/api-telegram-service/src/main/resources/application.yml`.

---

## Тестирование

- Не запускалось


## Заметки об обновлениях (2026-01-10)
- Пагинация `/help` и `/menu` перенесена в Telegram-рендер; ядро возвращает полный `SectionsBlock`.
- Пагинация инструментов рынка использует stateful-параметры и callbacks `mi:<sessionId>:<offset>` в `ChatStateStore`.
- Telegram-адаптеры формируют стабильный `sessionId` как `fromId|chatId` и передают callback-данные в V2-рендер.
- Ответ resolve в gateway включает `displayName` (пока равен login).
- Навигация меню редактирует одно сообщение и добавляет кнопку возврата в главное меню.
- `/menu_account` показывает сводку аккаунта; для неадминов скрыты User ID/роли/права.
- Меню/справка по рынку включают статус MOEX; добавлена ручка `/api/market/v1/status`.
- Dev DB-консоль: `/db_menu`, `/db <SQL>`, `/db_tables`, `/db_describe`, `/db_history`,
  плюс форматирование таблиц (сырой/pretty) и листание столбцов.
- Для dev-прав добавлена кнопка «Работа с SQL» в главном меню; примеры рынка используют *Id*, быстрые кнопки рынка убраны.
