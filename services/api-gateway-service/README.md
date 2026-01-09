# api-gateway-service

## Назначение (простыми словами)

Этот сервис — "входные двери" всей системы. Он:

- хранит пользователей, роли и права (RBAC);
- выдаёт JWT access/refresh токены;
- связывает пользователя с внешними аккаунтами (Telegram и др.);
- даёт внутреннее API для command-center и адаптеров;
- хранит тумблеры команд (command switches) и обслуживает dev-консоль.

Источник истины по identity находится именно здесь.

Если говорить совсем по‑человечески: все остальные сервисы верят gateway, потому что только он
знает, кто вы и какие у вас права. Поэтому любой «вход» в систему начинается здесь, а дальше
все живут по токену, который выдал gateway.

## Как это работает (ключевые сценарии)

Типичная история выглядит так: пользователь регистрируется или логинится, получает JWT,
потом любой другой сервис принимает этот токен и по нему понимает, что можно делать.
Gateway остаётся «админом» по пользователям и правам.

### 1) Регистрация и логин

1. Клиент вызывает `POST /api/auth/register` с `login/password`.
2. Gateway:
   - проверяет rate-limit (Redis),
   - хеширует пароль,
   - создаёт пользователя и назначает роль `USER`.
3. Логин (`/api/auth/login`) проверяет пароль и выдаёт:
   - `access_token` (JWT, короткий TTL),
   - `refresh_token` (длинный TTL, хранится в БД в виде хеша).

### 2) Refresh и logout

- `POST /api/auth/refresh` реализует **rotation**: старый refresh отзывается, выдаётся новый.
- `POST /api/auth/logout` отзывает refresh-токен.

### 3) Access token и права

- Access token содержит:
  - `uid` (внутренний id),
  - `roles`,
  - `perms` (эффективные права).
- `PermissionService` считает два набора:
  - **raw permissions** = роли + overrides (deny отменяет allow),
  - **effective permissions** = raw + разворачивание `DEVGOD` во все права.
- В JWT кладётся **effective**, но для чувствительных операций (hard delete)
  проверяется **raw**.

### 4) Внешние аккаунты (Telegram и др.)

- Таблица `external_accounts` связывает пользователя и внешний id (provider + external_id).
- Адаптеры/command-center работают через internal API:
  - разрешение внешнего id в пользователя (`/internal/identity/resolve`),
  - логин/регистрация + привязка (`/internal/auth/*`).

### 5) Internal API и защита

- Все `/internal/**` защищены заголовком `X-Internal-Token`.
- `InternalApiAuthFilter` отклоняет запросы без корректного токена.
- Дальше безопасность делается на уровне сервиса:
  - `RbacAdminService` проверяет права `actorUserId`,
  - списки/модификации RBAC доступны только при нужных правах.

### 6) Dev console и command switches (шаг 1.5)

- На старте `DevConsoleBootstrapper` выдаёт роль `DEVONLYADMIN` пользователям из env.
- Таблица `command_switches` хранит включённость команд (переживает рестарт).
- Internal API:
  - `GET /internal/commands/list` — список тумблеров (только internal token).
  - `POST /internal/commands/set-enabled` — включить/выключить (требует прав).
  - `POST /internal/users/hard-delete` — hard delete (требует **raw** `DEVGOD+USERS_HARD_DELETE`, запрет на удаление себя).

## Как реализовано (карта кода)

Если вы первый раз в проекте, начните с `AuthController` и `PermissionService` — они дают
самое понятное представление о логике регистрации/токенов и расчёте прав.

- `auth/api/AuthController` — `/api/auth/*` (register/login/refresh/logout).
- `auth/service/*`:
  - `UserService` — регистрация, хеширование пароля,
  - `TokenService` — выпуск access JWT,
  - `RefreshTokenService` — refresh rotation (хеш + pepper),
  - `PermissionService` — raw/effective perms,
  - `ExternalAccountService` — привязка внешних аккаунтов.
- `auth/security/*`:
  - `SecurityConfig`, `JwtAuthConverter`, `InternalApiAuthFilter`.
- `internal/api/*Controller` — internal endpoints.
- `internal/service/*`:
  - `RbacAdminService`, `CommandSwitchService`, `UserHardDeleteService`,
  - `DevConsoleBootstrapper`.
- `auth/domain/*` + `auth/repository/*` — JPA сущности и репозитории.
- Миграции: `src/main/resources/db/migration` (V1–V4).

## API (ручки)

### Public

- `GET /ping` — health check.
- `POST /api/auth/register`
- `POST /api/auth/login`
- `POST /api/auth/refresh`
- `POST /api/auth/logout`

### Internal (только X-Internal-Token)

- Identity:
  - `POST /internal/identity/resolve`
  - `POST /internal/identity/unlink`
- Auth + link:
  - `POST /internal/auth/register-and-link`
  - `POST /internal/auth/login-and-link`
  - `POST /internal/auth/issue-access`
- RBAC admin:
  - `POST /internal/rbac/elevate-by-code`
  - `POST /internal/rbac/users/get`
  - `POST /internal/rbac/users/list`
  - `POST /internal/rbac/roles/list`
  - `POST /internal/rbac/perms/list`
  - `POST /internal/rbac/roles/grant`
  - `POST /internal/rbac/roles/revoke`
  - `POST /internal/rbac/perms/grant`
  - `POST /internal/rbac/perms/deny`
  - `POST /internal/rbac/perms/revoke`
- Dev console:
  - `GET /internal/commands/list`
  - `POST /internal/commands/set-enabled`
  - `POST /internal/users/hard-delete`

## База данных (основные таблицы)

- `users`, `roles`, `user_roles`
- `permissions`, `role_permissions`, `user_permission_overrides`
- `external_accounts`, `auth_providers`
- `refresh_tokens`, `auth_events`
- `command_switches`

## Конфигурация и env

Ключевые переменные:

- `JWT_SECRET` (не менее 32 байт)
- `JWT_ISSUER` (по умолчанию `lsp-api-gateway`)
- `INTERNAL_API_TOKEN` (shared token для /internal)
- `DEV_CONSOLE_ENABLED` / `DEV_CONSOLE_USER_IDS`
- `DEV_ADMIN_CODE_ENABLED` / `DEV_ADMIN_CODE` (dev backdoor для adminlogin)
- `REDIS_HOST`, `REDIS_PORT` (rate limit)
- `SPRING_DATASOURCE_*` (Postgres)

Порт по умолчанию: `8086`.

## Локальный запуск

1) Поднять Postgres/Redis (см. `infra/`).
2) Задать `.env` / переменные окружения.
3) Запустить:

```bash
mvn -pl services/api-gateway-service spring-boot:run
```

## Ограничения и заметки

- Internal API защищено shared-token (без mTLS).
- Права пока демонстрационные (минимальный набор).
- `DEVGOD` разворачивается только в effective permissions; raw используется для критичных операций.
