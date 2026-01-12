# market-data-service

## Назначение

Сервис рыночных данных, который обращается к MOEX ISS и отдаёт
универсальный REST API для клиентов (бот, web, mini-app).

## Важно про gateway

В шаге 1.6 API **не проксируется** через `api-gateway-service`. Сервис
сам валидирует JWT как ресурсный сервер и проверяет право
`PERM_MARKETDATA_READ`. Клиенты обращаются напрямую к
`market-data-service` с токеном доступа.

## Архитектура (карта кода)

- `client/MoexClient` — интеграция с MOEX ISS (WebClient + кэш).
- `usecase/MarketDataUseCase` — бизнес-операции сервиса.
- `api/*Controller` — REST-адаптер `/api/market/v1/**`.
- `security/SecurityConfig` — конфигурация ресурсного сервера и RBAC.
- `api/PingController` — открытый `GET /ping`.

## Описание API (v1)

Все ручки требуют `PERM_MARKETDATA_READ`.

  * params: `engine` (default `stock`), `market` (default `shares`), `board` (default `TQBR`), `filter`,
    `limit` (1..5000, default 100), `offset` (default 0), `correlationId` (optional).
  * Пример запроса:

    ```bash
    curl -sS -H "Authorization: Bearer $ACCESS_TOKEN" \
      "$BASE_URL/api/market/v1/instruments?board=TQBR&filter=sber&limit=5&offset=0&correlationId=demo-1" | jq
    ```
  * Пример ответа:

    ```json
    {
      "correlationId": "demo-1",
      "instruments": [
        {
          "secId": "SBER",
          "shortName": "Сбербанк",
          "name": "Сбербанк России ПАО ао",
          "lotSize": 10,
          "prevPrice": 270.12,
          "lastPrice": 271.05,
          "currency": "RUB",
          "board": "TQBR"
        }
      ],
      "offset": 0,
      "limit": 5,
      "total": 1
    }
    ```

* `GET /api/market/v1/quotes` — котировка по тикеру.

  * params: `engine` (default `stock`), `market` (default `shares`), `board` (default `TQBR`),
    `sec` (тикер), `correlationId` (optional).
  * Пример запроса:

    ```bash
    curl -sS -H "Authorization: Bearer $ACCESS_TOKEN" \
      "$BASE_URL/api/market/v1/quotes?sec=SBER&board=TQBR&correlationId=demo-2" | jq
    ```
  * Пример ответа:

    ```json
    {
      "correlationId": "demo-2",
      "quote": {
        "secId": "SBER",
        "board": "TQBR",
        "lastPrice": 271.05,
        "change": 0.93,
        "changePercent": 1.0034,
        "volume": 123456789.0,
        "time": "2026-01-11T11:35:00Z"
      }
    }
    ```
  * Примечание: `quote.changePercent` в текущей реализации — это значение MOEX `LASTTOPREVPRICE` (отношение last/prev), а не “процент изменения” в классическом виде.


* `GET /api/market/v1/candles` — свечи.

  * params: `engine` (default `stock`), `market` (default `shares`), `board` (default `TQBR`),
    `sec`, `interval` (только 1, 10, 60, 1440), `from`, `till`, `correlationId` (optional).
  * `from` (если задан как `YYYY-MM-DD`) трактуется как `00:00:00 UTC`,
    `till` (если задан как `YYYY-MM-DD`) — как `23:59:59 UTC`.
  * Поддерживаемые форматы `from/till`: `YYYY-MM-DD`, `yyyy-MM-dd HH:mm:ss`, ISO (`YYYY-MM-DDTHH:mm:ssZ`).

  * Пример запроса:

    ```bash
    curl -sS -H "Authorization: Bearer $ACCESS_TOKEN" \
      "$BASE_URL/api/market/v1/candles?sec=SBER&board=TQBR&interval=60&from=2026-01-10&till=2026-01-11&correlationId=demo-3" | jq
    ```
  * Пример ответа:

    ```json
    {
      "correlationId": "demo-3",
      "secId": "SBER",
      "board": "TQBR",
      "interval": 60,
      "from": "2026-01-10T00:00:00Z",
      "till": "2026-01-11T23:59:59Z",
      "candles": [
        {
          "begin": "2026-01-10T07:00:00Z",
          "end": "2026-01-10T08:00:00Z",
          "open": 269.50,
          "close": 270.10,
          "high": 270.30,
          "low": 269.20,
          "volume": 12345.0
        }
      ]
    }
    ```

* `GET /api/market/v1/orderbook` — стакан.

  * params: `engine` (default `stock`), `market` (default `shares`), `board` (default `TQBR`),
    `sec`, `depth` (1..50, default 10; обрезка выполняется локально), `correlationId` (optional).
  * Пример запроса:

    ```bash
    curl -sS -H "Authorization: Bearer $ACCESS_TOKEN" \
      "$BASE_URL/api/market/v1/orderbook?sec=SBER&board=TQBR&depth=5&correlationId=demo-4" | jq
    ```
  * Пример ответа:

    ```json
    {
      "correlationId": "demo-4",
      "orderBook": {
        "secId": "SBER",
        "board": "TQBR",
        "time": null,
        "bids": [
          { "side": "B", "price": 271.00, "quantity": 100.0 },
          { "side": "B", "price": 270.99, "quantity": 50.0 }
        ],
        "asks": [
          { "side": "S", "price": 271.01, "quantity": 80.0 },
          { "side": "S", "price": 271.02, "quantity": 120.0 }
        ]
      }
    }
    ```

* `GET /api/market/v1/trades` — сделки.

  * params: `engine` (default `stock`), `market` (default `shares`), `board` (default `TQBR`),
    `sec`, `from` (строка: MOEX трактует как номер сделки или дату/время — зависит от ISS),
    `limit` (только 1, 10, 100, 1000, 5000), `correlationId` (optional).
  * Пример запроса:

    ```bash
    curl -sS -H "Authorization: Bearer $ACCESS_TOKEN" \
      "$BASE_URL/api/market/v1/trades?sec=SBER&board=TQBR&limit=10&correlationId=demo-5" | jq
    ```
  * Пример ответа:

    ```json
    {
      "correlationId": "demo-5",
      "from": null,
      "limit": 10,
      "trades": [
        {
          "tradeNo": 1234567890,
          "time": "2026-01-11T11:34:56Z",
          "price": 271.01,
          "quantity": 10.0,
          "side": "B"
        }
      ]
    }
    ```

* `GET /api/market/v1/status` — статус торгов по инструменту (MOEX).

  * params: `engine` (default `stock`), `market` (default `shares`), `board` (default `TQBR`),
    `sec` (тикер/secId; default `SBER`), `correlationId` (optional).
  * Пример запроса:

    ```bash
    curl -sS -H "Authorization: Bearer $ACCESS_TOKEN" \
      "$BASE_URL/api/market/v1/status?sec=SBER&board=TQBR&correlationId=demo-6" | jq
    ```
  * Пример ответа:

    ```json
    {
      "correlationId": "demo-6",
      "status": {
        "exchange": "MOEX",
        "board": "TQBR",
        "secId": "SBER",
        "tradingStatus": "T",
        "time": "2026-01-11T11:35:00Z"
      }
    }
    ```

Интерфейс Swagger UI: `/swagger-ui.html`.

## Примечания

- `instruments.total` - количество элементов в текущем ответе (после фильтра), без общего размера по ISS.
- `orderbook` может возвращать ошибку, если MOEX требует подписку; в этом случае MOEX отвечает HTML, сервис вернёт 502 с сообщением об ограничении.
- `status.tradingStatus` соответствует полю MOEX `TRADINGSTATUS` (например: `T` - торги, `N` - закрыто).

## Конфигурация и env

- `JWT_SECRET` — общий секрет с gateway (не менее 32 байт).
- `JWT_ISSUER` — по умолчанию `lsp-api-gateway`.
- `MOEX_BASE_URL` — базовый URL ISS (`https://iss.moex.com/iss`).
- `MOEX_TIMEOUT` — таймаут HTTP (например `5s`).
- `MOEX_CACHE_TTL` — TTL кэша ответов ISS (например `30s`).

Порт по умолчанию: `8081`.

## Локальный запуск

```bash
mvn -pl services/market-data-service spring-boot:run
```

Проверка (нужен валидный токен доступа от gateway):

```bash
curl -H "Authorization: Bearer <ACCESS_TOKEN>" \
  "http://localhost:8081/api/market/v1/instruments?board=TQBR"
```
