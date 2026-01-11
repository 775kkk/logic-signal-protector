# api-telegram-service

## Назначение

Адаптер Telegram Bot API. Он:

- принимает входящие update (webhook или polling),
- превращает их в универсальный `ChatMessageEnvelope`,
- отправляет в `logic-commands-center-service`,
- получает `ChatResponse` (v1) или `ChatResponseV2` (v2) и отправляет/редактирует сообщения в Telegram.

Это "переводчик" между Telegram и внутренним форматом.

logic-service — мозг, а telegram-service — аутпут в Telegram:
он слушает входящие сообщения, переводит их в понятный внутренний формат и затем показывает ответ пользователю.

## Как это работает

Ниже описан самый типичный сценарий: пользователь пишет в Telegram, ответ приходит обратно через этого же адаптера.

### Вариант 1: webhook

1) Telegram делает `POST /telegram/webhook`.
2) Контроллер читает update:
   - если это `message`, берёт `chat.id`, `from.id`, `message_id`, `text`;
   - если это `callback_query`, берёт `callback_query.data` + `callback_query.message.message_id`.
3) Формирует `ChatMessageEnvelope` и отправляет в command-center:
   - v1: `POST /internal/chat/message`
   - v2: `POST /internal/chat/message/v2` (см. правила маршрутизации ниже)
4) Получает `ChatResponse`/`ChatResponseV2` и отправляет ответ в Telegram:
   - `sendMessage` или `editMessageText` (если `preferEdit=true` и это возможно),
   - `deleteMessage` при `deleteSourceMessage=true`,
   - inline-кнопки через `inline_keyboard`,
   - `answerCallbackQuery` для callback-кнопок (чтобы Telegram убрал “часики”).

### Вариант 2: long-polling (локальная разработка — на сервер отправится вариант с вебхуком)

1) Включить `TELEGRAM_POLLING_ENABLED=true`.
2) `TelegramPollingRunner` регулярно вызывает `getUpdates`.
3) Обрабатывает updates так же, как webhook.

## Что делает UI-слой (uiHints)

`OutgoingMessage.uiHints` управляет тем, как сообщение будет показано (v1):

- `preferEdit=true` — адаптер *попытается* отредактировать предыдущее сообщение вместо отправки нового.
  - Важно: в Telegram edit возможен только когда есть `messageId` сообщения бота и включён `allowEdit=true` (это callback-сценарии).
- `deleteSourceMessage=true` — удалить исходное сообщение пользователя (полезно для логина/пароля).
- `renderMode=PRE` — оборачивает текст в `<pre>` и экранирует HTML (консольный вывод).
- `parseModeHint` — какой parseMode использовать при отправке сообщения в Telegram (`HTML`, `MarkdownV2` и т.п.).
- `inlineKeyboard` — список кнопок (Telegram callback_query).

## Механика V2: маршрутизация входа и обработка callback

### 1) Как выбирается v1 / v2 (TelegramWebhookController)

**Text-сообщение** (`update.message.text`) идёт в v2, если текст начинается с:
- `/help`, `/start`, `/menu`, `/market`, `/db`
- русские алиасы: `/помощь`, `/меню`, `/рынок`, `/хелп`, `/команды`

Иначе — используется v1 (`POST /internal/chat/message`).

**Callback** (`update.callback_query.data`) идёт в v2, если data начинается с:
- `h:` / `m:` — paging help/menu (страницы)
- `mi:` — paging instruments (страницы)
- `cmd:market...`, `cmd:menu...`, `cmd:db...` — “команда в callbackData” (см. ниже)

> Важно: правило `startsWith("/help")` означает, что `/helpdev` тоже будет отнесён к v2. Если `/helpdev` должен жить в v1 — это место требует уточнения в коде.

### 2) Что кладём в ChatMessageEnvelope (зачем эти поля)

На каждое входящее событие (сообщение или callback) генерируется:
- `correlationId` = UUID — корреляция “одно событие → цепочка вызовов” (удобно склеивать логи сервисов).
- `sessionId`:
  - обычно `fromId|chatId` (стабильный контекст UI диалога),
  - если from/chat отсутствуют — UUID.

При callback `sessionId` дополнительно пытаемся извлечь из `callbackData` (формат `h:<sessionId>:<page>`, `m:<sessionId>:<page>`, `mi:<sessionId>:<page>`), чтобы paging работал даже если по каким-то причинам from/chat отличаются.

`allowEdit` для рендера:
- `false` для обычного text-сообщения (редактировать “сообщение пользователя” нельзя),
- `true` для callback (редактируется сообщение бота, по `callback_query.message.message_id`).

После рендера callback всегда подтверждается через `answerCallbackQuery` (чтобы Telegram убрал “часики”).

### 3) Как v2-ответ превращается в Telegram сообщения (TelegramRendererV2)

Рендерер идёт по `blocks` сверху вниз и собирает список отправок:

- `TEXT`, `NOTICE`, `LIST`, `ERROR` → текстовые сообщения.
- `TABLE` → текст в `<pre>...</pre>` + `parseMode=HTML`.
  - `format=pretty` рисует ASCII-таблицу с разделителями.
- `SECTIONS`:
  - если включён контекст paging (см. ниже), рендерит **одну** секцию (страницу) и добавляет pager-кнопки `Назад/Дальше`;
  - если paging не включён — рендерит все секции подряд.
- `ACTIONS` → inline-клавиатура:
  - `actions[].title` → текст кнопки,
  - `actions[].payload` → `callback_data`.

Как `ACTIONS` прикрепляется к сообщениям:
- если `ACTIONS` встретился **после** текста — клавиатура “мерджится” в клавиатуру **последнего** сообщения;
- если `ACTIONS` встретился **до** текста — клавиатура временно кладётся в `pendingKeyboard` и прикрепляется к **первому следующему** сообщению.

### 4) Paging help/menu (почему кнопки не в ACTIONS)

Paging help/menu в 1.7 сделан “в два шага”:
1) кнопка `h:<sessionId>:<page>` или `m:<sessionId>:<page>` прилетает как callback;
2) адаптер вызывает logic v2 как обычно, но рендерер:
   - форсит `preferEdit=true` (чтобы редактировать одно “экранное” сообщение),
   - пропускает блоки `NOTICE` и `ACTIONS` (не дублировать интро/кнопки),
   - из `SECTIONS` выбирает нужную страницу по `page` и добавляет pager-кнопки.

Это сделано специально, чтобы “ядро” отдавалo структуру, а UX-поведение (страницы, edit) было в адаптере.

## Как реализовано (карта кода)

Если вы смотрите код впервые, начните с `TelegramWebhookController` — там видно весь «сквозной» сценарий: вход, конвертация, вызов logic и отправка ответа.

- `api/TelegramWebhookController` — обработка webhook + callback_query.
- `polling/TelegramPollingRunner` — long-polling режим.
- `api/DevTelegramController` — локальная ручка `/dev/telegram/message`.
- `client/CommandCenterClient` — отправка `ChatMessageEnvelope` в logic-service (v1/v2).
- `client/TelegramBotClient` — вызовы Bot API (`sendMessage`, `editMessageText`, `deleteMessage`, `answerCallbackQuery`).
- `render/TelegramRendererV2` — рендер блоков `ChatResponseV2`.
- `render/FriendlyMessageTemplates` — шаблоны сообщений для Notice/Error.
- DTO в `model/*` и `model/v2/*`: `ChatMessageEnvelope`, `ChatResponse`, `ChatResponseV2`, `OutgoingMessage`, `UiHints`, `InlineKeyboard`.

## Контракты

### Вход (Telegram Update)

Поддерживаются два типа:

- обычное сообщение: `update.message.text`
- callback_query: `update.callback_query.data`

### Вход (ChatMessageEnvelope → command-center)

Адаптер приводит Telegram update к универсальному конверту и отправляет его в command-center:

- v1: `POST /internal/chat/message`
- v2: `POST /internal/chat/message/v2`

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

* `callbackData` имеет приоритет над `text` (если он не пустой).
* `correlationId` генерируется на каждое событие (message/callback) и используется для трассировки цепочки вызовов.
* `sessionId` — контекст UI (обычно `fromId|chatId`), используется для paging и диалогового состояния.

### Выход (ChatResponse от command-center) — v1

```json
{
  "messages": [
    {
      "text": "...",
      "uiHints": {
        "preferEdit": true, --- желательно ли отредактировать сообщение вместо отправки нового
        "deleteSourceMessage": false, --- желательно ли удалять или не удалять исходное пользовательское сообщение (команду)
        "renderMode": "PRE", --- оформить текст как консольный вывод (<pre>...</pre>)
        "parseModeHint": "HTML", --- какой parse mode использовать при отправке сообщения в Telegram
        "inlineKeyboard": { --- описание inline-клавиатуры
          "rows": [[{"text": "Дальше", --- текст на кнопке
          "callbackData": "cmd:commands:page=2"}]] --- строка, которая придёт обратно в callback_query.data при нажатии
        }
      }
    }
  ]
}
```

### Выход (ChatResponseV2 от command-center) — v2

В v2 command-center возвращает блоки. Адаптер рендерит их в Telegram сообщения (и клавиатуру).

```json
  {
    "blocks": [ --- список блоков ответа (порядок важен: как идут, так и рендерятся) (типы блоков, подробнее в services\logic-commands-center-service\README.md)
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

* Кнопки в Telegram берутся из блока `ACTIONS` (`title` → текст, `payload` → callback_data).
* Для help/menu paging (`h:`/`m:`) кнопки “Назад/Дальше” строит сам рендерер на основе `SECTIONS`.

## Эндпоинты

* `POST /telegram/webhook`

  * принимает Telegram updates
  * при наличии `TELEGRAM_WEBHOOK_SECRET_TOKEN` проверяет заголовок `X-Telegram-Bot-Api-Secret-Token`
* `POST /dev/telegram/message`

  * локальная ручка для теста без Telegram
* `GET /ping`

## Конфигурация и env

* `TELEGRAM_BOT_TOKEN` — токен Telegram бота
* `TELEGRAM_WEBHOOK_SECRET_TOKEN` — опциональный секрет вебхука (сравнивается с `X-Telegram-Bot-Api-Secret-Token`)
* `TELEGRAM_POLLING_ENABLED` — включить polling
* `TELEGRAM_POLLING_DELAY_MS`, `TELEGRAM_POLLING_TIMEOUT_SECONDS`
* `LOGIC_COMMANDS_CENTER_BASE_URL` — адрес command-center

Порт по умолчанию: `8084`.

## Локальная проверка

### Через polling

1. `TELEGRAM_POLLING_ENABLED=true`
2. Запустить сервис.
3. Написать боту в Telegram.

### Без Telegram

```bash
curl -X POST http://localhost:8084/dev/telegram/message \
  -H "Content-Type: application/json" \
  -d '{"telegramUserId":1,"chatId":1,"text":"/help"}'
```

## Частые проблемы

* `TELEGRAM_BOT_TOKEN` пустой → webhook/polling игнорируется.
* Неверный `LOGIC_COMMANDS_CENTER_BASE_URL` → не приходит ответ.
* Inline кнопки не работают → нужен `callback_query` и обработчик.
* `preferEdit=true` “не редактирует” ответы → edit возможен только в callback-сценарии (там `allowEdit=true` и есть messageId сообщения бота).
* `/helpdev` уходит в v2 → потому что правило выбора v2 проверяет `startsWith("/help")`.

