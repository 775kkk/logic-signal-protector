# logic-commands-center-service

## Назначение

Это "мозг" чат-бота. Сервис получает **сырое сообщение** от любого внешнего канала (Telegram и будущие адаптеры),
разбирает команду, проверяет права в gateway, вызывает бизнес-сервисы и возвращает набор ответов,
которые адаптер потом отправит пользователю.

Проще говоря: пользователь написал `/help`, а этот сервис решает, что именно ответить и можно ли вообще
выполнять команду. Он как диспетчер: получает запрос, сверяет «допуск», вызывает нужный сервис
и собирает понятный ответ.

## Как это работает

Ниже — обычный жизненный путь одного сообщения. Это помогает новичку понять, где и что происходит.

1) Адаптер (например `api-telegram-service`) отправляет `ChatMessageEnvelope` в `POST /internal/chat/message`.
2) `ChatController` передаёт сообщение в `ChatCommandHandler`.
3) `ChatCommandHandler`:
   - определяет команду (`/help`, `/market`, `/command ...`),
   - при необходимости запрашивает идентификацию и права в `api-gateway-service` (внутреннее API),
   - проверяет включённость команды через `CommandSwitchCache` (fail-open + TTL),
   - при многошаговых сценариях хранит состояние в `ChatStateStore`.
4) Формирует `ChatResponse` — список `OutgoingMessage`.
5) Адаптер читает `uiHints` и решает, как показать ответ (edit/delete/pre/inline keyboard).

### Важно

- Этот сервис **не** общается с Telegram напрямую — только с gateway и бизнес-сервисами.
- Он не хранит пользователей — только запрашивает их у gateway.
- Состояние диалога хранится **в памяти** (TTL), это нормальная заглушка для шага 1.x.

## Контракты (input/output)

### Вход
`POST /internal/chat/message`
(это метаданные, нужные в основном адаптеру для edit/delete)
```json
{
  "channel": "telegram", --- откуда, в logic-commands-center это используется для вычисления providerCode -> дальше этим кодом вызывается gateway (gateway.resolve(providerCode, externalUserId)).
  "externalUserId": "123456", --- это то ключ, по которому gateway понимает, кто это привязан ли пользователь (linked), какие роли/права у него и т.д. ; также часть rate-limit ключей (у вас rateKey строится из provider + externalUserId).
  "chatId": "7890", --- id чата/диалога во внешнем канале.
  "messageId": "optional", --- id конкретного сообщения в канале(если есть) - api-telegram-service использует messageId при доставке ответа:для preferEdit=true -> чтобы сделать editMessageText(chatId, sourceMessageId, ...); для deleteSourceMessage=true -> чтобы сделать deleteMessage(chatId, sourceMessageId)
  "text": "/help",
  "callbackData": null, --- данные из нажатой inline-кнопки (callback_query.data) - строка которая формируется в inlineKeyboard - если callbackData задан и не пустой, то в logic-commands-center он имеет приоритет над text (в коде сначала проверяется callbackData, потом text).
  "correlationId": "uuid-or-any-string", --- корреляционный id одного входящего события (сообщение/кнопка) для трассировки. Генерируется в api-telegram-service (UUID) и прокидывается дальше в downstream (например market-data-service) как query param correlationId. В market-data-service возвращается эхом в ответе.
  "sessionId": "123456|7890", --- идентификатор сессии/контекста UI(UUID — только если нет fromId/chatId) (пагинация, состояние диалога)(UUID на каждое событие), если sessionId задан — он становится ключом состояния (stateKey), НЕ задан — stateKey строится как: channel|externalUserId|chatId ; прокидывается через сервисы, чтобы связывать:входящее сообщение (update/callback),обработку команды в logic-commands-center,вызовы downstream-сервисов (например market-data-service),и (в v2) сам ответ (прикольно для отладки/трассировки цепочки вызовов, плюс его удобно эхом возвращать клиенту (в v2 и в market api) Идентификатор контекста UI/сессии.
  "locale": "ru" --- локализация
}
```

- `text` и `callbackData` взаимоисключающие. (Обычно заполнено одно из двух: text или callbackData если заполнены оба — будет обработан callbackData.)
- `callbackData` приходит от inline-кнопок (например `cmd:commands:page=2`).

### Выход

```json
{
  "messages": [
    {
      "text": "...",
      "uiHints": {
        "preferEdit": true, --- желательно ли отредактировать сообщение вместо отправки нового
        "deleteSourceMessage": false, --- желетаельно ли удалять или не удалять исходное пользовательское сообщение (команду)
        "renderMode": "PRE", --- из-за консольного позиционирования логического центра (в каком формате отправлять соо рендеренное\нет) оформить текст как консольный вывод (<pre>...</pre>)
        "parseModeHint": "HTML", --- какой parse mode использовать при отправке сообщения в telegram
        "inlineKeyboard": { --- описание инлайн клавиатуры
          "rows": [[{"text": "Дальше", ---текст на кнопке
           "callbackData": "cmd:commands:page=2"}]] --- строка которая придёт обратно в callback_query.data при нажатии
        }
      }
    }
  ]
}
```

- `preferEdit=true` - адаптер может отредактировать предыдущее сообщение вместо отправки нового.
- `renderMode=PRE` - таблицы в монопространстве (`<pre>` для Telegram).
- `inlineKeyboard` - кнопки для callback_query.

### Вариант v2 (блочные ответы)

В v1 логика возвращает уже “почти готовый для Telegram” текст + `uiHints.inlineKeyboard`. Это удобно на старте, но плохо масштабируется: таблицы/меню получаются как конкатенация строк, форматирование легко ломается, а перенос на другой канал (web/mini-app/другой чат) требует переписывать бизнес-логику рендера.

В v2 `logic-commands-center-service` возвращает структурированный ответ `ChatResponseV2`: это массив блоков (`TEXT`, `NOTICE`, `LIST`, `TABLE`, `SECTIONS`, `ERROR`, `ACTIONS`) плюс минимальные `uiHints` (предпочтение редактировать/удалять, TTL). Важно: в v2 inline-кнопки НЕ задаются через `uiHints` — они описываются отдельным блоком `ACTIONS`, а конкретный адаптер (например Telegram) уже решает, как превратить действия в `inlineKeyboard` и как организовать UX (редактирование сообщения, пейджинг секций и т.п.).

Это даёт два эффекта:
1) “Консольное ядро” (logic) отдаёт смысл и структуру ответа, не привязываясь к Telegram-форматированию.
2) Адаптер (Telegram/Web/…) получает свободу рендера: таблицы можно показывать `<pre>`, в web — как настоящую таблицу, секции — как страницы, и т.д., без изменения бизнес-логики.

`POST /internal/chat/message/v2` возвращает `ChatResponseV2` и используется для сценариев, где важны меню/таблицы/пагинация и редактирование сообщений (например: `/help`, `/menu`, `/market`, `/db`, `/db_menu`).


```json
  {
    "blocks": [ --- список блоков ответа (порядок важен: как идут, так и рендерятся) (типы блоков)
      { "type": "TEXT",
      "text": "..." },
      { "type": "NOTICE",
      "text": "..." },
      { "type": "LIST",
      "items": ["...", "..."] },
      { "type": "TABLE", "columns": ["..."], "rows": [["..."]], "format": null },
      { "type": "SECTIONS", "sections": [ { "title": "...", "description": "...", "items": ["..."] } ] },
      { "type": "ERROR", "code": "...", "message": "...", "hint": "...", "details": null },
      { "type": "ACTIONS", "actions": [ { "actionId": "...", "title": "...", "payload": "..." } ] }
    ],
    "correlationId": "uuid", --- (UUID — только если нет fromId/chatId)
    "sessionId": "123|7890",
    "locale": "ru",
    "uiHints": {
      "preferEdit": true,
      "deleteSourceMessage": false,
      "ttlSeconds": null
    }
  }
```

#### Развернутая версяи json

```json
    {
    "blocks": [ --- список блоков ответа (порядок важен: как идут, так и рендерятся)
      {
        "type": "TEXT",                   --- тип блока (варианты: TEXT, NOTICE, LIST, TABLE, SECTIONS, ERROR, ACTIONS)
        "text": "Пример обычного текста"  --- текстовый контент блока TEXT
      },
      {
        "type": "NOTICE",    --- блок-уведомление/системная вставка (часто используется как ключ шаблона в Telegram-рендере)
        "text": "help_intro" --- либо готовый текст, либо ключ в FriendlyMessageTemplates (в зависимости от рендера)
      },
      {
        "type": "LIST", --- маркированный список
        "items": [      --- элементы списка (каждый элемент — строка)
          "Пункт 1",
          "Пункт 2"
        ]
      },
      {
        "type": "TABLE", --- табличный вывод (консольный стиль)
          "columns": [     --- заголовки колонок
            "col1",
            "col2"
          ],
          "rows": [ --- строки таблицы (каждая строка — массив значений по колонкам)
            ["v11", "v12"],
            ["v21", "v22"]
          ],
          "format": "PRETTY" --- формат таблицы (например: PRETTY/RAW; рендерер решает как именно выводить)
      },
      {
        "type": "SECTIONS", --- набор секций (используется для /help, /menu: страницы/категории)
        "sections": [
          {
            "title": "Раздел 1",       --- заголовок секции
            "description": "Описание", --- краткое описание секции (опционально)
            "items": [                 --- пункты внутри секции (список строк)
              "/help",
              "/menu"
            ]
          }
        ]
      },
      {
        "type": "ERROR",                  --- ошибка (рендерер может применять шаблоны / делать "дружелюбный" текст)
        "code": "FORBIDDEN",              --- код ошибки (используется для выбора шаблона/логики)
        "message": "Нет прав",            --- основное сообщение
        "hint": "Нужны dev-права",        --- подсказка пользователю (опционально)
        "details": null                  --- технические детали (обычно null/пусто для пользователя)
      },
      {
        "type": "ACTIONS",                --- действия/кнопки (именно отсюда Telegram-рендер строит inlineKeyboard)
        "actions": [
          {
            "actionId": "next",           --- внутренний id действия (для читаемости/логов; не обязан быть уникальным глобально)
            "title": "Дальше",            --- текст на кнопке
            "payload": "cmd:help:page=2"  --- то, что станет callback_query.data при нажатии (callbackData)
          }
        ]
      }
    ],
    "correlationId": "uuid-or-any-string", --- корреляция “одного события” через сервисы:
                                           --- генерируется в api-telegram-service на каждое входящее сообщение/колбэк,
                                           --- прокидывается в logic-commands-center и дальше в market-data-service как query-param,
                                           --- возвращается обратно в ответах (эхо) для склейки логов/трассировки.
    "sessionId": "123456|7890",            --- идентификатор “сессии/контекста UI” (для пагинации/состояния):
                                           --- используется как ключ в ChatStateStore (Redis/in-memory),
                                           --- обычно строится как fromId|chatId на стороне telegram-адаптера.
    "locale": "ru",                        --- язык пользователя (Telegram from.language_code); в 1.7 i18n частичная
    "uiHints": {                           --- подсказки адаптеру, КАК доставить ответ (без кнопок!)
      "preferEdit": true,                  --- предпочтительно редактировать предыдущее сообщение вместо отправки нового
      "deleteSourceMessage": false,        --- предпочтительно удалить исходное пользовательское сообщение (если возможно)
      "ttlSeconds": null                   --- TTL для UI/состояния (если используется; часто null)
    }
  }
```

В Telegram-кнопки берутся из: ACTIONS.actions[].payload -> становится callbackData и (опционально) из пагинации секций, которую делает рендерер (TelegramRendererV2.buildPagerKeyboard(...)).
NOTICE.text в v2 часто используется как ключ шаблона (например "help_intro"), который уже на стороне api-telegram-service превращается в реальный текст через FriendlyMessageTemplates.

### Механика V2: как logic парсит вход (text/callbackData) и что считается “командой”

В v2 обработчик (`ChatCommandHandlerV2`) сначала выбирает входную строку так:

1) если `callbackData` не пустой → **используется callbackData**;
2) иначе используется `text`.

Это сделано, чтобы inline-кнопки могли быть полноценным источником команд/действий.

#### 1) Форматы callbackData, которые понимает v2

**A) Paging (служебные “действия”)**
- `h:<sessionId>:<page>` → показать help (страница выбирается в адаптере)
- `m:<sessionId>:<page>` → показать menu (страница выбирается в адаптере)
- `mi:<sessionId>:<page>` → instruments paging (страница обрабатывается в ядре)

В ядре это распознаётся через `parsePageRequest()`:
- если строка начинается с `h:`/`m:`/`mi:` и имеет 3 части (`kind:sessionId:page`),
  то handler вызывает соответствующий сценарий:
  - `h` → `/help`
  - `m` → `/menu`
  - `mi` → `/market instruments page=<page>`

**B) “Команда в callbackData”**
- `cmd:<command>:arg1:arg2:...`

В ядре это нормализуется в вид обычной команды:
- `cmd:db:tables` → `/db tables`
- `cmd:market:quote:SBER` → `/market quote SBER`

#### 2) Нормализация команд (для удобства UX)

Перед разбором команды выполняются преобразования:
- `cmd:` → превращение в `/command args...` (см. выше)
- `_`-команды:
  - `/market_quote SBER` → `/market quote SBER`
  - `/db_tables` → `/db tables`
  - `/db_menu` остаётся отдельной командой (не превращается в `/db menu`)

Это позволяет держать “старые” короткие формы команд и одновременно иметь единый обработчик `/market ...` и `/db ...`.

#### 3) Где выбирается страница help/menu

Важно: ядро для `/help` и `/menu` возвращает `SECTIONS` как “полный список секций”.
Страница (какую секцию показать) выбирается **адаптером** по callback `h:/m:` — это часть UX-логики канала (TelegramRendererV2).


## Команды и ограничения

Основные команды:

- `/help` — справка по доступным командам (фильтруется по правам и тумблерам).
- `/helpdev` — dev-команды (только при `DEV_CONSOLE_ENABLED=true` и наличии `DEVGOD`).
- `/register <login> <password>` — регистрация + привязка внешнего аккаунта.
- `/login <login> <password>` — логин + привязка.
- `/logout` — отвязка (с подтверждением).
- `/me` — статус привязки.
- `/market_*` - доступ к рыночным данным (нужен `MARKETDATA_READ`).
  - `/market_instruments`, `/market_quote`, `/market_candles`, `/market_orderbook`, `/market_trades`.
  - пример: `/market_quote *Id*` или `/market_candles *Id* interval=60 from=2024-01-01 till=2024-01-31`.
- `/alerts` — демо-вызов alerts (нужен `ALERTS_READ`).
- `/broker` — демо-вызов broker (нужен `BROKER_READ`).
- `/trade` — демо-торговля (нужен `BROKER_TRADE`).

Dev/админ команды:

- `/commands` - список команд с состоянием тумблеров (нужен `COMMANDS_TOGGLE` или `DEVGOD`).
- `/command enable|disable <code>` - включить/выключить команду.
- `/user delete <login|id>` - полное удаление (нужны `DEVGOD` + `USERS_HARD_DELETE`, с подтверждением).
- `/adminlogin <code>` - служебная выдача роли ADMIN (rate-limit).
- `/db_menu` - меню доступа к БД (dev-admin).
- `/db <SQL>` - SQL консоль gateway DB (dev-admin).
- `/db_tables` - список таблиц.
- `/db_describe <schema.table>` - структура таблицы.
- `/db_history` - `flyway_schema_history`.
- Для таблиц доступна кнопка `Форматировать` (сырой/pretty вид) и навигация по столбцам
  с фиксированным первым столбцом (`Столбец назад/дальше`).
- `/users`, `/user <login>`, `/roles`, `/perms`, `/grantrole`, `/revokerole`, `/grantperm`, `/denyperm`, `/revokeperm`.

**Нельзя отключить (toggleable=false):** `/help`, `/helpdev`, `/commands`, `/command`.

## Как реализовано (карта кода)

Если нужно быстро «пощупать» логику — откройте `ChatCommandHandler`: там видно разбор команд,
проверки прав и формирование ответа.

- `api/ChatController` - входная точка `/internal/chat/message`.
- `api/ChatControllerV2` - входная точка `/internal/chat/message/v2`.
- `domain/ChatCommandHandler` - основной обработчик команд и сценариев (V1).
- `domain/v2/ChatCommandHandlerV2` - V2 обработчик с блоками `/help`, `/menu`, `/market`, `/db`.
- `domain/CommandRegistry` — декларативный список команд (код, текст, права, toggleable).
- `domain/CommandSwitchCache` — кеш тумблеров (TTL, fail-open при сбое gateway).
- `domain/ChatStateStore` + `domain/ChatState` — хранение состояния диалога (login/register/logout/hard delete).
- `domain/TextTable` — форматирование таблиц для `renderMode=PRE`.
- `client/GatewayInternalClient` - вызовы internal API gateway (resolve/issueAccess/dbQuery).
- `client/DownstreamClients` — вызовы market/alerts/broker.
- DTO: `api/dto/*` и `api/dto/v2/*` (V1/V2 контракты).

## Конфигурация и env

- `GATEWAY_INTERNAL_BASE_URL` (по умолчанию `http://localhost:8086`)
- `INTERNAL_API_TOKEN` (shared token для /internal)
- `MARKET_DATA_BASE_URL`, `ALERTS_BASE_URL`, `BROKER_BASE_URL`
- `CHAT_STATE_TTL` (например `PT10M`)
- `CHAT_HARD_DELETE_CONFIRM_TTL` (например `PT60S`)
- `COMMAND_SWITCH_CACHE_TTL` (например `PT10S`)
- `DEV_ADMINLOGIN_RATE_WINDOW`, `DEV_ADMINLOGIN_RATE_MAX_ATTEMPTS`
- `DEV_CONSOLE_ENABLED`

Порт по умолчанию: `8085`.

## Локальный запуск

```bash
mvn -pl services/logic-commands-center-service spring-boot:run
```

Тест без Telegram:

```bash
curl -X POST http://localhost:8085/internal/chat/message \
  -H "Content-Type: application/json" \
  -H "X-Internal-Token: <INTERNAL_API_TOKEN>" \
  -d '{"channel":"telegram","externalUserId":"1","chatId":"1","text":"/help"}'
```

## Ограничения и заметки

- Состояние диалога в памяти — для нескольких реплик нужен Redis.
- При недоступности gateway тумблеры считаются включёнными (fail-open).
- Ответы без `uiHints` будут отправлены обычным сообщением.
