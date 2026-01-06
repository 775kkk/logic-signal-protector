# logic-commands-center-service

Назначение: единый центр обработки чат-команд .
Получает **сырое** сообщение пользователя из любого внешнего канала (Telegram и будущие адаптеры),
парсит команду, при необходимости запрашивает identity/права в **api-gateway-service**, вызывает
бизнес-сервисы и формирует текст ответа.

Ссылки на шаги:
- (шаг 1.3) Централизация парсинга команд, чтобы новые чат-каналы не дублировали логику.
- (шаг 1.3) Stateful диалог (минимальная заготовка): ожидание логина/пароля.

## Входной формат (универсальный конверт)

`POST /internal/chat/message` (шаг 1.3)

```json
{
  "channel": "telegram",
  "externalUserId": "123456",
  "chatId": "7890",
  "messageId": "optional",
  "text": "/help"
}
```

Ответ (шаг 1.3):

```json
{
  "messages": [{"text": "..."}]
}
```

## Переменные окружения

- `GATEWAY_INTERNAL_BASE_URL` (шаг 1.3) - например `http://localhost:8086`
- `INTERNAL_API_TOKEN` (шаг 1.3) - общий секрет для внутренних запросов (command-center -> gateway)
- `MARKET_DATA_BASE_URL` (шаг 1.3) - например `http://localhost:8081`
- `ALERTS_BASE_URL` (шаг 1.3) - например `http://localhost:8082`
- `BROKER_BASE_URL` (шаг 1.3) - например `http://localhost:8083`

## Команды (заглушки)

- `/help` - справка
- `/register <login> <password>` - регистрация + привязка внешнего аккаунта (например Telegram)
- `/login <login> <password>` - логин + привязка
- `/me` - показать информацию о привязке
- `/market` - вызывает `market-data-service` защищённую ручку (`PERM_MARKETDATA_READ`)
- `/alerts` - вызывает `alerts-service` защищённую ручку (`PERM_ALERTS_READ`)
- `/broker` - вызывает `virtual-broker-service` защищённую ручку (`PERM_BROKER_READ`)
- `/trade` - вызывает `virtual-broker-service` (POST) (`PERM_BROKER_TRADE`)

## Замечание про state

Состояние диалога в шаге 1.3 хранится в памяти (TTL). Для масштаба/нескольких реплик
нужно перенести в Redis.

## Запуск

```bash
mvn -pl services/logic-commands-center-service spring-boot:run
```