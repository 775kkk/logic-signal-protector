# Logic Signal Protector

Проект: набор микросервисов, покачто оркестратор-адаптер чат-бот-консоль формат.

## Карта сервисов

- `services/api-gateway-service` — источник истины по идентификации: пользователи, привязки внешних аккаунтов, JWT (токен доступа/токен обновления), RBAC (роли/права), внутреннее API.
- `services/api-telegram-service` — адаптер Telegram Bot API: принимает webhook/polling updates и передаёт «сырое сообщение» в command-center.
- `services/logic-commands-center-service` — командный центр: парсит команды, проверяет права, оркеструет вызовы сервисов, формирует ответ.
- `services/market-data-service` — доменный сервис рыночных данных (MOEX ISS) с REST API `/api/market/v1/**`.
- `services/alerts-service` — заглушка сервиса алертов/уведомлений.
- `services/virtual-broker-service` — заглушка «виртуального брокера».

Примечание: для реального webhook нужен публичный URL. Для локальной проверки можно использовать dev-ручки в `api-telegram-service` (см. README сервиса).

## Документация

- `docs/step-*.md` — дневник проекта по шагам (что было сделано и почему).
- `docs/tz-step-*.md` — технические задания по шагам.
- `services/*/README.md` — подробная документация по каждому сервису.
- `internal_docs/assistant_notes/*` — рабочие заметки (хронология + карта реализации).
- `для себя/*` — внутренние заметки реализации (шаг 1.5).


### Поток сообщения (Telegram)
1) Telegram → `api-telegram-service` (`POST /telegram/webhook` или polling)
2) `api-telegram-service` собирает `ChatMessageEnvelope` и вызывает:
   - v1: `POST http://logic/internal/chat/message`
   - v2: `POST http://logic/internal/chat/message/v2`
3) `logic-commands-center-service`:
   - парсит команду,
   - делает `gateway.resolve(providerCode, externalUserId)` через `/internal/**` (только с `X-Internal-Token`),
   - проверяет права, тумблеры команд, состояние сценария,
   - вызывает downstream-сервисы,
   - возвращает `ChatResponse` (v1) или `ChatResponseV2` (v2).
4) `api-telegram-service` рендерит ответ: send/edit/delete + inline-кнопки.

---

## Карта сервисов

Сервисы находятся в `services/*`.

- `api-gateway-service` (порт **8086**)  
  Пользователи/привязки внешних аккаунтов, RBAC, JWT access/refresh, internal API, command switches, dev-консоль.  
  Swagger: `http://localhost:8086/swagger-ui.html`

- `logic-commands-center-service` (порт **8085**)  
  Команды, сценарии, права, тумблеры, сборка ответов v1/v2.  
  Swagger: `http://localhost:8085/swagger-ui.html`

- `api-telegram-service` (порт **8084**)  
  Telegram webhook/polling, преобразование Telegram update → envelope → вызов logic, доставка ответов, Telegram renderer v2.  
  Swagger: `http://localhost:8084/swagger-ui.html`

- `market-data-service` (порт **8081**)  
  MOEX ISS → унифицированный REST API `/api/market/v1/**` (resource server, проверяет JWT и `PERM_MARKETDATA_READ`).  
  Swagger: `http://localhost:8081/swagger-ui.html`

- `alerts-service` (порт **8082**)  
  Заглушка сервиса алертов.

- `virtual-broker-service` (порт **8083**)  
  Заглушка “виртуального брокера”.

---

## Контракты сообщений

### v1 (текст + uiHints)
Logic возвращает “почти готовый” текст и подсказки адаптеру: edit/delete/inlineKeyboard.

### v2 (блочные ответы)
Logic возвращает **структуру**: `TEXT/LIST/TABLE/SECTIONS/ERROR/ACTIONS`, а адаптер решает как рендерить:
- Telegram: `<pre>` для таблиц, inline-кнопки из `ACTIONS`, редактирование меню-экранов.
- Web/mini-app (в будущем): нормальные таблицы/страницы без переписывания бизнес-логики.

Подробно: `services/logic-commands-center-service/README.md`.

---

## Старт локально

### Требования
- Java **21**
- Maven
- Docker + Docker Compose

### 1) Env
Скопируй `.env.example` → `.env` и заполни минимум:
- `JWT_SECRET` (>= 32 байт)
- `INTERNAL_API_TOKEN` (одинаковый для gateway и command-center)
- `TELEGRAM_BOT_TOKEN` (если нужен реальный Telegram)

Файл-пример: `.env.example`.

### 2) Поднять инфраструктуру (Postgres + Redis)
В репозитории есть `infra/docker-compose.yml`:

```bash
docker compose -f infra/docker-compose.yml up -d
````

Порты по умолчанию:

* Postgres: `localhost:15432`
* Redis: `localhost:6379`

### 3) Запуск

#### Ручной запуск Maven)

Отдельные терминалы:

```bash
mvn -pl services/api-gateway-service spring-boot:run
mvn -pl services/market-data-service spring-boot:run
mvn -pl services/alerts-service spring-boot:run
mvn -pl services/virtual-broker-service spring-boot:run
mvn -pl services/logic-commands-center-service spring-boot:run
mvn -pl services/api-telegram-service spring-boot:run
```

---

## Как быстро проверить, что всё живо

1. Swagger UI:

* gateway: `http://localhost:8086/swagger-ui.html`
* logic: `http://localhost:8085/swagger-ui.html`
* telegram-adapter: `http://localhost:8084/swagger-ui.html`
* market-data: `http://localhost:8081/swagger-ui.html`

2. Smoke-тест command-center без Telegram:

```bash
curl -X POST http://localhost:8085/internal/chat/message \
  -H "Content-Type: application/json" \
  -d '{"channel":"telegram","externalUserId":"1","chatId":"1","text":"/help"}'
```

---

## Документация в репозитории

* `docs/project-summary.md` — сводка проекта (что есть сейчас).
* `docs/step-*.md` — дневник шагов (что было сделано и почему).
* `docs/tz-step-*.md`, `docs/tz_step_1_7.md` — технические задания.
* `services/*/README.md` — документация по каждому сервису.
* `patch.md` — изменения/заметки по версии.

---

## Важные ограничения (шаг 1.x)

* `logic-commands-center-service` хранит состояние сценариев **в памяти** (TTL). Для горизонтального масштаба нужен Redis/DB.
* Защита `/internal/**` в gateway — это shared secret `X-Internal-Token` (уровень “dev/step 1.x”, не prod).
* Telegram имеет ограничения по длине сообщений — длинные таблицы/секции требуют пагинации/сжатия.
