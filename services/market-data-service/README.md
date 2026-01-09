# market-data-service

## Назначение (простыми словами)

Сервис рыночных данных, который обращается к MOEX ISS и отдаёт
универсальный REST API для клиентов (бот, web, mini-app).

## Важно про gateway

В шаге 1.6 API **не проксируется** через `api-gateway-service`. Сервис
сам валидирует JWT как resource-server и проверяет право
`PERM_MARKETDATA_READ`. Клиенты обращаются напрямую к
`market-data-service` с access token.

## Архитектура (карта кода)

- `client/MoexClient` — интеграция с MOEX ISS (WebClient + кэш).
- `usecase/MarketDataUseCase` — бизнес-операции сервиса.
- `api/*Controller` — REST-адаптер `/api/market/v1/**`.
- `security/SecurityConfig` — resource-server конфигурация и RBAC.
- `api/PingController` — открытый `GET /ping`.

## API Reference (v1)

Все ручки требуют `PERM_MARKETDATA_READ`.

- `GET /api/market/v1/instruments` — список инструментов.
  - params: `engine` (default `stock`), `market` (default `shares`), `board` (default `TQBR`),
    `filter`, `limit` (1..5000, default 100), `offset` (default 0).
- `GET /api/market/v1/quotes` — котировка по тикеру.
  - params: `sec` (ticker), `board` (default `TQBR`).
- `GET /api/market/v1/candles` — свечи.
  - params: `sec`, `board`, `interval` (1, 10, 60, 1440), `from`, `till`.
  - `from` трактуется как `00:00:00`, `till` — как `23:59:59`.
- `GET /api/market/v1/orderbook` — стакан.
  - params: `sec`, `board`, `depth` (1..50, default 10; обрезка выполняется локально).
- `GET /api/market/v1/trades` — сделки.
  - params: `sec`, `board`, `from` (номер сделки или дата), `limit` (1, 10, 100, 1000, 5000).

Swagger UI: `/swagger-ui.html`.

## Примечания

- `instruments.total` — количество элементов в текущем ответе (после фильтра), без общего размера по ISS.
- `orderbook` может возвращать ошибку, если MOEX требует подписку; в этом случае MOEX отвечает HTML, сервис вернёт 502 с сообщением об ограничении.

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

Проверка (нужен валидный access token от gateway):

```bash
curl -H "Authorization: Bearer <ACCESS_TOKEN>" \
  "http://localhost:8081/api/market/v1/instruments?board=TQBR"
```
