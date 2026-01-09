# api-telegram-service

## Назначение (простыми словами)

Адаптер Telegram Bot API. Он:

- принимает входящие update (webhook или polling),
- превращает их в универсальный `ChatMessageEnvelope`,
- отправляет в `logic-commands-center-service`,
- получает `ChatResponse` и отправляет/редактирует сообщения в Telegram.

Это "переводчик" между Telegram и внутренним форматом.

Если представить, что logic‑service — это «мозг», то telegram‑service — это «рот и уши» в Telegram:
он слушает входящие сообщения, переводит их в понятный внутренний формат и затем говорит ответ
пользователю.

## Как это работает (основной поток)

Ниже описан самый типичный сценарий: пользователь пишет в Telegram, ответ приходит обратно
через этого же адаптера.

### Вариант 1: webhook

1) Telegram делает `POST /telegram/webhook`.
2) Контроллер читает update:
   - если это `message`, берёт `chat.id`, `from.id`, `message_id`, `text`;
   - если это `callback_query`, берёт `callback_query.data`.
3) Формирует `ChatMessageEnvelope` и отправляет в command-center.
4) Получает `ChatResponse` и отправляет ответ в Telegram:
   - `sendMessage` или `editMessageText` (если `preferEdit=true`),
   - `deleteMessage` при `deleteSourceMessage=true`,
   - inline-кнопки через `inline_keyboard`.

### Вариант 2: long-polling (локальная разработка)

1) Включить `TELEGRAM_POLLING_ENABLED=true`.
2) `TelegramPollingRunner` регулярно вызывает `getUpdates`.
3) Обрабатывает updates так же, как webhook.

## Что делает UI-слой (uiHints)

`OutgoingMessage.uiHints` управляет тем, как сообщение будет показано:

- `preferEdit=true` — попытаться отредактировать предыдущее сообщение.
- `deleteSourceMessage=true` — удалить сообщение пользователя (полезно для логина/пароля).
- `renderMode=PRE` — оборачивает текст в `<pre>` и экранирует HTML.
- `inlineKeyboard` — список кнопок (Telegram callback_query).

## Как реализовано (карта кода)

Если вы смотрите код впервые, начните с `TelegramWebhookController` — там видно весь
«сквозной» сценарий: вход, конвертация, вызов logic и отправка ответа.

- `api/TelegramWebhookController` — обработка webhook + callback_query.
- `polling/TelegramPollingRunner` — long-polling режим.
- `api/DevTelegramController` — локальная ручка `/dev/telegram/message`.
- `client/CommandCenterClient` — отправка `ChatMessageEnvelope` в logic-service.
- `client/TelegramBotClient` — вызовы Bot API (`sendMessage`, `editMessageText`, `deleteMessage`, `answerCallbackQuery`).
- DTO в `model/*`: `ChatMessageEnvelope`, `ChatResponse`, `OutgoingMessage`, `UiHints`, `InlineKeyboard`.

## Контракты

### Вход (Telegram Update)

Поддерживаются два типа:

- обычное сообщение: `update.message.text`
- callback_query: `update.callback_query.data`

### Выход (ChatResponse от command-center)

```json
{
  "messages": [
    {
      "text": "...",
      "uiHints": {
        "preferEdit": true,
        "deleteSourceMessage": false,
        "renderMode": "PRE",
        "parseModeHint": "HTML",
        "inlineKeyboard": {
          "rows": [[{"text": "Next", "callbackData": "cmd:commands:page=2"}]]
        }
      }
    }
  ]
}
```

## Эндпоинты

- `POST /telegram/webhook`
  - принимает Telegram updates
  - может требовать `X-Telegram-Bot-Api-Secret-Token`
- `POST /dev/telegram/message`
  - локальная ручка для теста без Telegram
- `GET /ping`

## Конфигурация и env

- `TELEGRAM_BOT_TOKEN` — токен Telegram бота
- `TELEGRAM_WEBHOOK_SECRET_TOKEN` — опциональный секрет вебхука
- `TELEGRAM_POLLING_ENABLED` — включить polling
- `TELEGRAM_POLLING_DELAY_MS`, `TELEGRAM_POLLING_TIMEOUT_SECONDS`
- `LOGIC_COMMANDS_CENTER_BASE_URL` — адрес command-center

Порт по умолчанию: `8084`.

## Локальная проверка

### Через polling

1) `TELEGRAM_POLLING_ENABLED=true`
2) Запустить сервис.
3) Написать боту в Telegram.

### Без Telegram

```bash
curl -X POST http://localhost:8084/dev/telegram/message \
  -H "Content-Type: application/json" \
  -d '{"telegramUserId":1,"chatId":1,"text":"/help"}'
```

## Частые проблемы

- `TELEGRAM_BOT_TOKEN` пустой → webhook/polling игнорируется.
- Неверный `LOGIC_COMMANDS_CENTER_BASE_URL` → не приходит ответ.
- Inline кнопки не работают → нужен `callback_query` и обработчик (реализован в шаге 1.5).
