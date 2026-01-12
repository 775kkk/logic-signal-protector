# Дневник проекта (Logic Signal Protector)

Этот файл — **живой конспект проекта**: что за сервисы есть, как они связаны, какие шаги уже сделаны и какие риски/долги остаются.

Основание: код и документация из `logic-signal-protector-1.7.zip` + твой скрин с поведением `/help` и `/db`.

---

## Хронология шагов (краткая выжимка)

- **Шаг 0** — bootstrap: корневой Maven-агрегатор, каркас сервисов.
- **Шаг 1.1** — базовая регистрация/логин в `api-gateway-service`, таблицы `users/auth_provider/external_accounts`.
- **Шаг 1.2** — отладочные инструменты/логирование (исторически).
- **Шаг 1.3** — RBAC-скелет (роли/права/переопределения), JWT, внутренние `/internal/**`.
- **Шаг 1.4** — админ-инструменты (часть команд через chat), право `ADMIN_ANSWERS_LOG`, dev `/adminlogin <code>`.
- **Шаг 1.5** — dev-консоль и тумблеры команд (`command_switches`), автоприсвоение dev-роли по `DEV_CONSOLE_USER_IDS`.
- **Шаг 1.6** — `market-data-service` (MOEX ISS) + интеграция в команды (`/market_*`), кэширование.
- **Шаг 1.7** — «Console Core v2»: структурные ответы `ChatResponseV2`, V2-рендер в Telegram, `/help` и `/menu` с секциями, dev DB-консоль `/db*`, меню/пейджеры.

---

## Карта сервисов (актуально в 1.7)

### 1) `services/api-gateway-service`
**Роль:** источник истины по идентичности и правам.
- Auth: регистрация/логин, JWT (access/refresh), refresh_tokens.
- RBAC: роли/права, overrides, расчёт эффективных прав.
- Internal API (`/internal/**`) для сервисов (под `X-Internal-Token`).
- Dev: bootstrap dev-роли для выбранных пользователей (`DEV_CONSOLE_USER_IDS`), внутренний endpoint DB-консоли (`/internal/db/query`).

**Хранилище:** Postgres (`lsp_gateway`).

### 2) `services/logic-commands-center-service`
**Роль:** командный центр (оркестратор).
- Принимает “сырой чат” (текст/колбэки), парсит команды, решает права через gateway.
- Держит state для UI (пагинации/контекст меню) через `ChatStateStore`.
- Вызывает downstream сервисы (market-data, в перспективе alerts/broker и т.д.).

**Два режима ответов:**
- **V1**: `ChatResponse` (простой текст + клавиатура).
- **V2**: `ChatResponseV2` (набор блоков: TEXT/NOTICE/LIST/TABLE/SECTIONS/ERROR/ACTIONS).

**Хранилище/кэш:** Redis (для rate limit / state), Postgres не используется.

### 3) `services/api-telegram-service`
**Роль:** адаптер Telegram Bot API.
- Получает updates (webhook/polling), нормализует в envelope.
- Вызывает `logic-commands-center-service`.
- Рендерит ответ пользователю:
  - V1 — старый рендер.
  - V2 — `TelegramRendererV2` (таблицы в `<pre>`, пейджеры для секций, preferEdit для навигации).

### 4) `services/market-data-service`
**Роль:** доменный сервис рыночных данных (MOEX ISS).
- REST `/api/market/v1/**` (instruments/quote/candles/orderbook/trades + status).
- WebClient к MOEX, caching Caffeine, валидация параметров.

### 5) `services/alerts-service`
**Статус:** заглушка (каркас, без полноценной реализации бизнес-API).

### 6) `services/virtual-broker-service`
**Статус:** заглушка (каркас).

---

## Основные потоки

### V1 (текстовый ответ)
1) Telegram → `api-telegram-service`
2) `api-telegram-service` → `logic-commands-center-service`
3) `logic-commands-center-service` → `api-gateway-service` `/internal/**` (resolve/perms/прочее)
4) (опционально) `logic-commands-center-service` → доменные сервисы (market-data и др.)
5) `api-telegram-service` отправляет текст/кнопки.

### V2 (структурные блоки)
Поток тот же, отличие в контракте ответа:
- logic возвращает `ChatResponseV2 { blocks: [...] }`
- telegram-адаптер решает, как показать (таблица/список/навигация по секциям/редактирование сообщения).

---

## Dev DB-консоль (/db*)

### Команды
- `/db_menu` — меню БД-операций.
- `/db <SQL>` — выполнить SQL через gateway (внутренний вызов `/internal/db/query`).
- алиасы: `/db_tables`, `/db_history`, `/db_describe` и т.п.

### Доступ и наблюдаемое поведение
- **Нужно быть привязанным (linked)**: без `/login` gateway не сможет определить пользователя.
- Нужны **dev-права** (через роли/права в gateway).  
  На твоём скрине это видно: `/db` отвечает *«DB команда недоступна. Проверь dev-права.»* — это штатный `FORBIDDEN`.
- `adminlogin` **не заменяет** `/login`: он сам требует, чтобы аккаунт был уже привязан (внутри handler есть проверка “Сначала привяжи аккаунт: /login”).

> Важно: флаг `dev.console.enabled` сейчас влияет на видимость dev-команд в `/help`, но **не участвует** в `MenuBuilder.canDev(...)` (то есть не является “рубильником” доступа). Это текущая реализация 1.7.

---

## Проверка и запуск (текущее)

Основание: корневой `README.md` + README сервисов.

Минимально: Postgres + Redis + 3 сервиса
- `api-gateway-service` (порт по умолчанию 8086)
- `logic-commands-center-service` (8085)
- `api-telegram-service` (8087)

Конфиг — через `.env` / `.env.example`:
- `JWT_SECRET`, `INTERNAL_API_TOKEN`, `TELEGRAM_BOT_TOKEN`
- `DEV_CONSOLE_ENABLED` / `DEV_CONSOLE_USER_IDS`
- `DEV_ADMIN_CODE_ENABLED` / `DEV_ADMIN_CODE`
- `MOEX_*` (для market-data)

---

## Открытые пункты (общие)

Это не “задачи на 1.7”, а заметки для следующих итераций.

- Риск Telegram лимитов: `TABLE` может получиться слишком длинной → нужно резать/пагинировать по символам и/или строкам.
- `FriendlyMessageTemplates`: если ключи ошибок и коды не в одном регистре, шаблоны не матчятся (нужно унифицировать).
- `dev.console.enabled` сейчас не рубильник доступа к dev-меню/`/db*` (только видимость в help) — если нужен рубильник, его надо учесть в `canDev()`.
- Дублирование V2 DTO в двух сервисах (logic и telegram) — либо общий модуль, либо контракт-тесты/fixtures.
- Тестов почти нет; “работает” подтверждается руками (пока так и зафиксировано в docs/step-1.7.md).

---

## Заметки об обновлениях (2026-01-10)

(Основание: `docs/step-1.7.md` + `internal_docs/assistant_notes/impl_notes.md`.)

- Пагинация `/help` и `/menu` сделана в Telegram-рендере; ядро отдаёт полный `SectionsBlock`.
- Пагинация инструментов рынка использует `ChatStateStore` и callbacks `mi:<sessionId>:<offset>`.
- Telegram формирует стабильный `sessionId` как `fromId|chatId` и прокидывает callbacks в V2.
- `resolve` в gateway возвращает `displayName` (пока равен login).
- Навигация меню редактирует одно сообщение (UX без спама).
- `/menu_account` скрывает чувствительные поля для неадминов.
- Добавлен endpoint статуса MOEX: `/api/market/v1/status`.
- Dev DB-консоль: форматирование pretty/raw, листание столбцов, возврат в `/db_menu`.
