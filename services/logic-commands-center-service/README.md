# logic-commands-center-service

## Назначение (простыми словами)

Это "мозг" чат-бота. Сервис получает **сырое сообщение** от любого внешнего канала (Telegram и будущие адаптеры),
разбирает команду, проверяет права в gateway, вызывает бизнес-сервисы и возвращает набор ответов,
которые адаптер потом отправит пользователю.

Проще говоря: пользователь написал `/help`, а этот сервис решает, что именно ответить и можно ли вообще
выполнять команду. Он как диспетчер: получает запрос, сверяет «допуск», вызывает нужный сервис
и собирает понятный ответ.

## Как это работает (пошагово)

Ниже — обычный жизненный путь одного сообщения. Это помогает новичку понять, где и что происходит.

1) Адаптер (например `api-telegram-service`) отправляет `ChatMessageEnvelope` в `POST /internal/chat/message`.
2) `ChatController` передаёт сообщение в `ChatCommandHandler`.
3) `ChatCommandHandler`:
   - определяет команду (`/help`, `/market`, `/command ...`),
   - при необходимости запрашивает identity и права в `api-gateway-service` (internal API),
   - проверяет включённость команды через `CommandSwitchCache` (fail-open + TTL),
   - при многошаговых сценариях хранит состояние в `ChatStateStore`.
4) Формирует `ChatResponse` — список `OutgoingMessage`.
5) Адаптер читает `uiHints` и решает, как показать ответ (edit/delete/pre/inline keyboard).

### Что важно понять новичку

- Этот сервис **не** общается с Telegram напрямую — только с gateway и бизнес-сервисами.
- Он не хранит пользователей — только запрашивает их у gateway.
- Состояние диалога хранится **в памяти** (TTL), это нормальная заглушка для шага 1.x.

## Контракты (input/output)

### Вход
`POST /internal/chat/message`

```json
{
  "channel": "telegram",
  "externalUserId": "123456",
  "chatId": "7890",
  "messageId": "optional",
  "text": "/help",
  "callbackData": null
}
```

- `text` и `callbackData` взаимоисключающие.
- `callbackData` приходит от inline-кнопок (например `cmd:commands:page=2`).

### Выход

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

- `preferEdit=true` — адаптер может отредактировать предыдущее сообщение вместо отправки нового.
- `renderMode=PRE` — таблицы в монопространстве (`<pre>` для Telegram).
- `inlineKeyboard` — кнопки для callback_query.

## Команды и ограничения

Основные команды:

- `/help` — справка по доступным командам (фильтруется по правам и тумблерам).
- `/helpdev` — dev-команды (только при `DEV_CONSOLE_ENABLED=true` и наличии `DEVGOD`).
- `/register <login> <password>` — регистрация + привязка внешнего аккаунта.
- `/login <login> <password>` — логин + привязка.
- `/logout` — отвязка (с подтверждением).
- `/me` — статус привязки.
- `/market <subcommand>` - доступ к рыночным данным (нужен `MARKETDATA_READ`).
  - `instruments`, `quote`, `candles`, `orderbook`, `trades` (см. `/market help`).
  - пример: `/market quote SBER` или `/market candles SBER interval=60 from=2024-01-01 till=2024-01-31`.
- `/alerts` — demo вызов alerts (нужен `ALERTS_READ`).
- `/broker` — demo вызов broker (нужен `BROKER_READ`).
- `/trade` — demo trade (нужен `BROKER_TRADE`).

Dev/админ команды:

- `/commands` — список команд с состоянием тумблеров (нужен `COMMANDS_TOGGLE` или `DEVGOD`).
- `/command enable|disable <code>` — включить/выключить команду.
- `/user delete <login|id>` — hard delete (нужны `DEVGOD` + `USERS_HARD_DELETE`, с подтверждением).
- `/adminlogin <code>` — dev-backdoor выдачи роли ADMIN (rate-limit).
- `/users`, `/user <login>`, `/roles`, `/perms`, `/grantrole`, `/revokerole`, `/grantperm`, `/denyperm`, `/revokeperm`.

**Нельзя отключить (toggleable=false):** `/help`, `/helpdev`, `/commands`, `/command`.

## Как реализовано (карта кода)

Если нужно быстро «пощупать» логику — откройте `ChatCommandHandler`: там видно разбор команд,
проверки прав и формирование ответа.

- `api/ChatController` — входная точка `/internal/chat/message`.
- `domain/ChatCommandHandler` — основной обработчик команд и сценариев.
- `domain/CommandRegistry` — декларативный список команд (код, текст, права, toggleable).
- `domain/CommandSwitchCache` — кеш тумблеров (TTL, fail-open при сбое gateway).
- `domain/ChatStateStore` + `domain/ChatState` — хранение состояния диалога (login/register/logout/hard delete).
- `domain/TextTable` — форматирование таблиц для `renderMode=PRE`.
- `client/GatewayInternalClient` — вызовы internal API gateway.
- `client/DownstreamClients` — вызовы market/alerts/broker.
- DTO: `api/dto/*` (`ChatMessageEnvelope`, `ChatResponse`, `OutgoingMessage`, `UiHints`, `InlineKeyboard`).

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
