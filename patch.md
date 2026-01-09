# patch.md - Step 1.6

Источник правды: `docs/tz-step-1.6.md`

## Статус

### market-data-service
- [x] REST API `/api/market/v1/**`: instruments, quotes, candles, orderbook, trades.
- [x] `MoexClient` (WebClient + iss.only/columns) + Caffeine cache.
- [x] DTO + response wrappers.
- [x] Use-case слой (`MarketDataUseCase`).
- [x] RBAC `PERM_MARKETDATA_READ` (resource-server), `GET /ping` открыт.
- [x] Валидация параметров (interval/limit/depth) + маппинг ошибок.
- [x] Swagger UI `/swagger-ui.html`.
- [x] Защита от non-JSON ответов MOEX (orderbook subscription-only -> 502).

### logic-commands-center-service
- [x] `/market` переведен на подкоманды: `instruments`, `quote`, `candles`, `orderbook`, `trades`.
- [x] Поддержка параметров `key=value` + позиционный `SEC`.
- [x] Вызовы market-data через `DownstreamClients` (access token).
- [x] `/market help` с описанием и примерами.
- [x] Требуется право `MARKETDATA_READ`.

### Документация и конфигурация
- [x] README: root + market-data + logic.
- [x] `docs/tz-step-1.6.md` (про прямой доступ без proxy через gateway).
- [x] `internal_docs/assistant_notes/impl_notes.md`, `internal_docs/assistant_notes/dev_log.md`.
- [x] `.env.example` с `MOEX_*`.

## Принятые решения
- API market-data в шаге 1.6 не проксируется через gateway; клиенты ходят напрямую с access token.
- `MARKETDATA_ADMIN` не добавляем до появления админ-ручек.
- `orderbook` может быть subscription-only; при non-JSON ответе MOEX отдаем 502 с `MOEX_ISS_ERROR`.
