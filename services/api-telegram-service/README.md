# api-gateway-service

## Назначение

Gateway в составе микросервисного приложения. Отвечает за:

- регистрацию/логин по `login/password`
- выдачу JWT access token и refresh token
- RBAC (roles/permissions) и публикацию `perms` в access token
- хранение и привязку внешних аккаунтов (`external_accounts`) как часть identity
- внутренние (internal) ручки для сервисов-адаптеров и command-center

- JWT issuance + refresh rotation (шаг 1.2)
- Flyway migrations + единая воспроизводимая схема БД auth (шаг 1.2)
- RBAC модель (roles/permissions) и добавление `perms` в access token (шаг 1.3)
- Внутренние ручки для command-center и адаптеров (шаг 1.3)

## Что реализовано сейчас

### Аутентификация

- `POST /api/auth/register` — регистрация (шаг 1.2)
- `POST /api/auth/login` — логин, выдача access+refresh (шаг 1.2)
- `POST /api/auth/refresh` — refresh rotation (шаг 1.2)
- `POST /api/auth/logout` — отзыв refresh токена (шаг 1.2)

### RBAC (заглушки прав)

Схема таблиц:

- `users` / `roles` / `user_roles` (шаг 1.2)
- `permissions` / `role_permissions` / `user_permission_overrides` (шаг 1.3)

При выдаче access token gateway кладёт в JWT:

- `roles`: список ролей пользователя
- `perms`: эффективные permissions (роль + overrides; deny отменяет allow) (шаг 1.3)
- `uid`: внутренний id пользователя (шаг 1.3)

Сейчас заведено **6 заглушек** прав (минимальная демонстрация RBAC):

- `MARKETDATA_READ`
- `ALERTS_READ`
- `ALERTS_MANAGE`
- `BROKER_READ`
- `BROKER_TRADE`
- `ADMIN_PANEL`

Маппинг заглушек:

- USER: `MARKETDATA_READ`, `ALERTS_READ`, `BROKER_READ`
- ADMIN: все 6

### External accounts (Telegram и будущие каналы)

Начиная с (шаг 1.3) gateway **не занимается Telegram транспортом** (webhook/polling) и не парсит чат-команды.

- Telegram-адаптер вынесен в отдельный сервис: `api-telegram-service` (шаг 1.3)
- Парсинг команд и оркестрация вынесены в: `logic-commands-center-service` (шаг 1.3)

Gateway остаётся источником истины по identity:

- хранит связку `user <-> external_accounts(provider, external_id)`
- выдаёт access token с `uid`, `roles`, `perms`

#### Internal API для command-center/адаптеров (шаг 1.3)

Все `/internal/**` защищены общим заголовком `X-Internal-Token` (значение берётся из `INTERNAL_API_TOKEN`).

- `POST /internal/identity/resolve`
  - вход: `{providerCode, externalUserId}`
  - выход: `{linked, userId, login, roles, perms}`
- `POST /internal/auth/register-and-link`
  - вход: `{providerCode, externalUserId, login, password}`
  - эффект: создаёт пользователя, привязывает внешний аккаунт, выдаёт access token
- `POST /internal/auth/login-and-link`
  - вход: `{providerCode, externalUserId, login, password}`
  - эффект: логинит пользователя, привязывает внешний аккаунт, выдаёт access token
- `POST /internal/auth/issue-access`
  - вход: `{providerCode, externalUserId}`
  - эффект: выдаёт access token для уже привязанного внешнего аккаунта

## События и готовность к Kafka

`AuthAuditService` сохраняет события в БД (`auth_events`) и дополнительно вызывает `AuthEventPublisher`.
Сейчас стоит `NoopAuthEventPublisher` (ничего не публикует), но интерфейс оставлен как точка расширения для Kafka/Rabbit (шаг 1.3).

## Как запустить

1. Поднять инфраструктуру (Postgres/Redis) из `infra/` (шаг 1.2).
2. Задать `JWT_SECRET` через env/.env.
3. Задать `INTERNAL_API_TOKEN` (должен совпадать у gateway и command-center) (шаг 1.3).
4. Запустить сервис.

Примечание: `TELEGRAM_BOT_TOKEN` теперь нужен только в `api-telegram-service` (для Bot API) (шаг 1.3).

## Ограничения

- RBAC: permissions пока заглушки (6 прав), бизнес-операции downstream сервисов демонстрационные.
- Internal API защищено простым shared-token. Для production уровня понадобятся mTLS/mesh или хотя бы network policies.
