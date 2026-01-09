# Logic Signal Protector

Проект: набор микросервисов, где **командный центр** (`logic-commands-center-service`) принимает сообщения из внешних каналов (сейчас Telegram),
проверяет identity/права в gateway (`api-gateway-service`), вызывает бизнес-сервисы и возвращает ответ для пользователя.

> Статус репозитория: активная разработка (шаги `docs/step-*.md`).

## 1) Карта сервисов

- `services/api-gateway-service` — источник истины по identity: пользователи, привязки внешних аккаунтов, JWT (access+refresh), RBAC (roles/permissions), internal API.
- `services/api-telegram-service` — адаптер Telegram Bot API: принимает webhook/polling updates и передаёт «сырое сообщение» в command-center.
- `services/logic-commands-center-service` — командный центр: парсит команды, проверяет права, оркеструет вызовы сервисов, формирует ответ.
- `services/market-data-service` — доменный сервис рыночных данных (MOEX ISS) с REST API `/api/market/v1/**`.
- `services/alerts-service` — заглушка сервиса алертов/уведомлений.
- `services/virtual-broker-service` — заглушка «виртуального брокера».

## 2) Типовой поток Telegram-команды (шаг 1.3+)

1. Telegram → `api-telegram-service` (`POST /telegram/webhook`)
2. `api-telegram-service` извлекает envelope: `{provider=telegram, externalUserId, chatId, messageId, text}`
3. `api-telegram-service` → `logic-commands-center-service` (HTTP)
4. `logic-commands-center-service`:
   - парсит команду (поддержка `/cmd` и `cmd`, RU-алиасы)
   - запрашивает identity/права в `api-gateway-service` через `/internal/**`
   - вызывает бизнес-сервисы
   - формирует `ChatResponse`
5. `api-telegram-service` отправляет пользователю сообщения (поддерживает `editMessageText`, inline-кнопки, `deleteMessage`).

## 3) Быстрый старт локально

Минимально работоспособный сценарий: поднять Postgres/Redis, запустить gateway+logic+telegram.

1) Заполнить `.env` (см. `.env.example`).
   - `JWT_SECRET`
   - `INTERNAL_API_TOKEN` (одинаковый для gateway и logic)
   - `TELEGRAM_BOT_TOKEN` (для telegram-service)
   - `DEV_CONSOLE_ENABLED` / `DEV_CONSOLE_USER_IDS` (dev console, шаг 1.5)
2) Поднять инфраструктуру из `infra/` (Postgres/Redis).
3) Запустить сервисы:
   - gateway: `services/api-gateway-service` (порт по умолчанию 8086)
   - logic: `services/logic-commands-center-service` (8085)
   - telegram: `services/api-telegram-service` (8084)

Примечание: для реального webhook нужен публичный URL. Для локальной проверки можно использовать dev-ручки в `api-telegram-service` (см. README сервиса).

## 4) Документация

- `docs/step-*.md` — дневник проекта по шагам (что было сделано и почему).
- `docs/tz-step-*.md` — технические задания по шагам.
- `services/*/README.md` — подробная документация по каждому сервису.
- `internal_docs/assistant_notes/*` — рабочие заметки (хронология + карта реализации).
- `для себя/*` — внутренние заметки реализации (Step 1.5).

## 5) Лицензия

Лицензия **не задана** (файла `LICENSE` нет) — используем «дефолтный» режим: код предназначен для работы в рамках этого репозитория/проекта.
