# api-gateway-service

## Назначение

Этот сервис — "входные двери" всей системы. Он:

- хранит пользователей, роли и права (RBAC);
- выдаёт JWT access/refresh токены;
- связывает пользователя с внешними аккаунтами (Telegram и др.);
- даёт внутреннее API для command-center и адаптеров;
- хранит тумблеры команд (command switches) и обслуживает dev-консоль.

Источник истины по идентификации находится именно здесь.

Все остальные сервисы верят gateway, потому что только он
знает, кто вы и какие у вас права. Поэтому любой «вход» в систему начинается здесь, а дальше
все живут по токену, который выдал gateway.

---

## Как это работает (ключевые сценарии)

### 1) Регистрация и логин (public API)

1) Клиент вызывает `POST /api/auth/register` с `login/password`.
2) Gateway:
   - проверяет rate-limit (Redis),
   - хеширует пароль,
   - создаёт пользователя и назначает роль `USER`.
3) Клиент вызывает `POST /api/auth/login`.
4) Gateway:
   - проверяет пароль,
   - выдаёт:
     - `accessToken` (JWT, короткий TTL),
     - `refreshToken` (длинный TTL; хранится в БД в виде хеша).

### 2) Refresh и logout (public API)

- `POST /api/auth/refresh` реализует refresh rotation:
  - старый refresh считается использованным/отозванным,
  - выдаётся новая пара токенов.
- `POST /api/auth/logout` отзывает refresh-токен.

### 3) Токен доступа и права (JWT)

Access JWT содержит:
- `sub` — login (subject),
- `uid` — внутренний id пользователя,
- `roles` — список кодов ролей,
- `perms` — **effective permissions** (см. ниже).

#### raw vs effective permissions

`PermissionService` считает два набора:

- **raw permissions** = права от ролей + user overrides
  (override с `allowed=false` “снимает” право, `allowed=true` “добавляет”)
- **effective permissions** = raw + разворачивание `DEVGOD` во все права системы

В access JWT кладётся **effective**.
Но для чувствительных операций (hard delete) проверяется **raw** (чтобы `DEVGOD` в effective не скрывал отсутствие второго нужного права).

### 4) Внешние аккаунты (Telegram и др.)

- Таблица `external_accounts` связывает пользователя с внешним id: `(provider_code, external_id)`.
- Адаптеры/command-center работают с gateway через internal API:
  - resolve внешнего id в пользователя (`/internal/identity/resolve`),
  - login/register + link (`/internal/auth/*`),
  - unlink (`/internal/identity/unlink`).

### 5) Внутреннее API и защита

- Все `/internal/**` защищены заголовком `X-Internal-Token` (shared secret).
- Если `INTERNAL_API_TOKEN` не задан — gateway вернёт 500 на любой `/internal/**`.
- Если `X-Internal-Token` неправильный — gateway вернёт 401.

Важно: это защита уровня “шаг 1.x” (shared token), без mTLS/подписи запросов.

### 6) Dev console и command switches (шаг 1.5)

- Таблица `command_switches` хранит включённость команд (переживает рестарт).
- Internal API:
  - `GET /internal/commands/list` — список тумблеров (только internal token).
  - `POST /internal/commands/set-enabled` — включить/выключить (требует прав).
- Dev DB console:
  - `POST /internal/db/query` — выполнение SQL в gateway DB (возвращает таблицу/кол-во обновлённых строк).

---

## Как реализовано (карта кода)

- `auth/api/AuthController` — `/api/auth/*` (register/login/refresh/logout).
- `auth/service/*`:
  - `UserService` — регистрация, хеширование пароля,
  - `TokenService` — выпуск access JWT (claims: uid/roles/perms),
  - `RefreshTokenService` — refresh rotation (хеш + pepper),
  - `PermissionService` — raw/effective perms,
  - `ExternalAccountService` — привязка внешних аккаунтов.
- `auth/security/*`:
  - `SecurityConfig`, `JwtAuthConverter`, `InternalApiAuthFilter`.
- `internal/api/*Controller` — internal endpoints.
- `internal/service/*`:
  - `RbacAdminService`, `CommandSwitchService`, `UserHardDeleteService`,
  - `DbConsoleService`.
- Миграции: `src/main/resources/db/migration`.

---

## API (ручки)

### Публичные

- `GET /ping` — health check.
- `POST /api/auth/register`
- `POST /api/auth/login`
- `POST /api/auth/refresh`
- `POST /api/auth/logout`

### Внутренние (только `X-Internal-Token`)

- Идентификация:
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
- Command switches:
  - `GET /internal/commands/list`
  - `POST /internal/commands/set-enabled`
- Users:
  - `POST /internal/users/hard-delete`
- Dev DB console:
  - `POST /internal/db/query`

---

## Контракты и примеры (JSON + пояснения)

### Общие заголовки

#### Public API
- `Content-Type: application/json`
- (для публичных auth ручек `Authorization` не нужен)

#### Internal API
- `Content-Type: application/json`
- `X-Internal-Token: <INTERNAL_API_TOKEN>` — обязателен для `/internal/**`

---

## Ошибки (единый формат)

Большинство ошибок (валидация/403/404/409/429/500) возвращаются как:

```json
{
  "code": "FORBIDDEN",
  "message": "Missing permission COMMANDS_TOGGLE",
  "timestamp": "2026-01-11T21:05:12.123Z"
}
````

Исключение: защита `/internal/` через `X-Internal-Token` возвращает упрощённый JSON:

* 401: `{"error":"unauthorized"}`
* 500 (если токен не настроен): `{"error":"internal.auth.token is not configured"}`

---

# Public Auth API

## POST /api/auth/register

Создаёт пользователя (роль USER назначается автоматически).

Request:

```json
{
  "login": "nikita",       --- логин (3..64)
  "password": "passw0rd.." --- пароль (8..128)
}
```

Response `201 Created`:

```json
{
  "id": 10,        --- внутренний userId
  "login": "nikita"
}
```

## POST /api/auth/login

Request:

```json
{
  "login": "nikita",
  "password": "passw0rd.."
}
```

Response `200 OK`:

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...", --- JWT для вызова бизнес-сервисов
  "tokenType": "Bearer",
  "accessExpiresInSeconds": 900,                            --- TTL access токена (в секундах)
  "refreshToken": "rt_....",                                --- refresh токен (длинный)
  "refreshExpiresInSeconds": 2592000                        --- TTL refresh токена (в секундах)
}
```

## POST /api/auth/refresh

Request:

```json
{
  "refreshToken": "rt_...." --- текущий refresh токен
}
```

Response `200 OK`: (новая пара токенов; rotation)

```json
{
  "accessToken": "....",
  "tokenType": "Bearer",
  "accessExpiresInSeconds": 900,
  "refreshToken": "rt_new....",
  "refreshExpiresInSeconds": 2592000
}
```

## POST /api/auth/logout

Request:

```json
{
  "refreshToken": "rt_...." --- refresh, который нужно отозвать
}
```

Response: `204 No Content`

---

# Internal API (для command-center и адаптеров)

## POST /internal/identity/resolve

Проверяет, привязан ли внешний аккаунт (provider + externalUserId) к пользователю в gateway.
Используется command-center’ом, чтобы понять: linked ли пользователь, и какие у него роли/права.

Request:

```json
{
  "providerCode": "telegram", --- код провайдера (сейчас telegram)
  "externalUserId": "123456"  --- внешний id пользователя (например Telegram from.id)
}
```

Response (если НЕ привязан):

```json
{
  "linked": false,
  "userId": null,
  "login": null,
  "displayName": null,
  "roles": [],
  "perms": []
}
```

Response (если привязан):

```json
{
  "linked": true,
  "userId": 10,               --- внутренний userId
  "login": "nikita",          --- внутренний login
  "displayName": "nikita",    --- сейчас = login (можно расширять позже)
  "roles": ["USER", "ADMIN"],
  "perms": ["PERM_MARKETDATA_READ", "COMMANDS_TOGGLE", "DEVGOD"]
}
```

## POST /internal/identity/unlink

Отвязка внешнего аккаунта (logout-семантика для Telegram). Идемпотентно.

Request:

```json
{
  "providerCode": "telegram",
  "externalUserId": "123456"
}
```

Response:

```json
{
  "ok": true
}
```

---

## POST /internal/auth/register-and-link

Регистрирует пользователя, привязывает внешний аккаунт и выдаёт **только access token**
(без refresh), потому что в чат-бот сценариях refresh обычно не нужен.

Request:

```json
{
  "providerCode": "telegram",
  "externalUserId": "123456",
  "login": "nikita",
  "password": "passw0rd.."
}
```

Response `201 Created`:

```json
{
  "accessToken": "....",         --- access JWT
  "tokenType": "Bearer",
  "expiresInSeconds": 900,       --- TTL access
  "userId": 10,
  "login": "nikita",
  "roles": ["USER"],
  "perms": ["..."]
}
```

## POST /internal/auth/login-and-link

Логинит пользователя, привязывает внешний аккаунт и выдаёт access token.

Request:

```json
{
  "providerCode": "telegram",
  "externalUserId": "123456",
  "login": "nikita",
  "password": "passw0rd.."
}
```

Response `200 OK`: такой же, как `register-and-link`.

## POST /internal/auth/issue-access

Выдаёт access token по связке `(providerCode, externalUserId)`.
Если внешний аккаунт не привязан — вернёт “пустой” результат (accessToken=null).

Request:

```json
{
  "providerCode": "telegram",
  "externalUserId": "123456"
}
```

Response (не привязан):

```json
{
  "accessToken": null,
  "tokenType": "Bearer",
  "expiresInSeconds": 0,
  "userId": null,
  "login": null,
  "roles": [],
  "perms": []
}
```

Response (привязан): обычный `TokensResponse` (access + роли/права).

---

# Internal RBAC admin (консольные команды)

Важно: большинство RBAC ручек требует, чтобы `actorUserId` имел **effective** право:
`ADMIN_USERS_PERMS_REVOKE`.

## POST /internal/rbac/elevate-by-code

Dev-backdoor: назначает роль `ADMIN` пользователю, который уже привязан через external_accounts.
Включается только если `DEV_ADMIN_CODE_ENABLED=true` и задан `DEV_ADMIN_CODE`.

Request:

```json
{
  "providerCode": "telegram",
  "externalUserId": "123456",
  "code": "1234" --- секретный код (DEV_ADMIN_CODE)
}
```

Response:

```json
{
  "ok": true,
  "login": "nikita",
  "roles": ["ADMIN", "USER"],
  "perms": ["..."]
}
```

## POST /internal/rbac/users/get

Request:

```json
{
  "actorUserId": 10,  --- кто выполняет команду (проверяются его права)
  "login": "nikita"   --- кого смотрим
}
```

Response:

```json
{
  "userId": 10,
  "login": "nikita",
  "roles": ["USER"],
  "perms": ["PERM_MARKETDATA_READ"],
  "overrides": [
    {
      "permCode": "PERM_MARKETDATA_READ",
      "allowed": true,
      "expiresAt": null,
      "reason": "demo"
    }
  ]
}
```

## POST /internal/rbac/users/list

Request:

```json
{
  "actorUserId": 10
}
```

Response:

```json
{
  "users": [
    { "userId": 1, "login": "admin" },
    { "userId": 10, "login": "nikita" }
  ]
}
```

## POST /internal/rbac/roles/list

Request:

```json
{
  "actorUserId": 10
}
```

Response:

```json
{
  "roles": [
    { "code": "USER", "name": "User" },
    { "code": "ADMIN", "name": "Admin" }
  ]
}
```

## POST /internal/rbac/perms/list

Request:

```json
{
  "actorUserId": 10
}
```

Response:

```json
{
  "perms": [
    { "code": "PERM_MARKETDATA_READ", "name": "Market data read" },
    { "code": "COMMANDS_TOGGLE", "name": "Toggle commands" }
  ]
}
```

## POST /internal/rbac/roles/grant | /roles/revoke

Request:

```json
{
  "actorUserId": 10,
  "targetLogin": "nikita",
  "roleCode": "ADMIN"
}
```

Response: `UserInfoResponse` (как в users/get).

## POST /internal/rbac/perms/grant | /perms/deny

Request:

```json
{
  "actorUserId": 10,
  "targetLogin": "nikita",
  "permCode": "PERM_MARKETDATA_READ",
  "reason": "need for demo",                 --- опционально
  "expiresAt": "2026-02-01T00:00:00Z"        --- опционально (ISO-8601 Instant)
}
```

Response: `UserInfoResponse`.

## POST /internal/rbac/perms/revoke

Request:

```json
{
  "actorUserId": 10,
  "targetLogin": "nikita",
  "permCode": "PERM_MARKETDATA_READ"
}
```

Response: `UserInfoResponse`.

---

# Command switches

## GET /internal/commands/list

Response:

```json
{
  "switches": [
    {
      "commandCode": "/market",
      "enabled": true,
      "updatedAt": "2026-01-11T20:00:00Z",
      "updatedByUserId": 10,
      "note": "demo"
    }
  ]
}
```

## POST /internal/commands/set-enabled

Требует:

* либо `DEVGOD`,
* либо `COMMANDS_TOGGLE`.

Request:

```json
{
  "actorUserId": 10,
  "commandCode": "/market",
  "enabled": false,
  "note": "temporarily disabled"
}
```

Response:

```json
{
  "value": {
    "commandCode": "/market",
    "enabled": false,
    "updatedAt": "2026-01-11T20:10:00Z",
    "updatedByUserId": 10,
    "note": "temporarily disabled"
  }
}
```

---

# Hard delete

## POST /internal/users/hard-delete

Требует **raw** права одновременно:

* `DEVGOD`
* `USERS_HARD_DELETE`

И запрещено удалять самого себя.

Request (по userId):

```json
{
  "actorUserId": 10,
  "targetUserId": 11,
  "targetLogin": null
}
```

Request (по login):

```json
{
  "actorUserId": 10,
  "targetUserId": null,
  "targetLogin": "someone"
}
```

Response:

```json
{
  "ok": true,
  "deletedUserId": 11,
  "deletedLogin": "someone"
}
```

---

# Dev DB console

## POST /internal/db/query

Выполняет SQL в БД gateway.

Request:

```json
{
  "sql": "select id, login from users order by id",
  "maxRows": 50 --- опционально; default=50; max cap=1000
}
```

Response (QUERY):

```json
{
  "ok": true,
  "type": "QUERY",          --- QUERY | UPDATE | ERROR
  "columns": ["id", "login"],
  "rows": [["1", "admin"], ["10", "nikita"]],
  "updated": null,
  "truncated": false,
  "error": null
}
```

Response (UPDATE):

```json
{
  "ok": true,
  "type": "UPDATE",
  "columns": [],
  "rows": [],
  "updated": 1,
  "truncated": false,
  "error": null
}
```

Response (ERROR):

```json
{
  "ok": false,
  "type": "ERROR",
  "columns": [],
  "rows": [],
  "updated": null,
  "truncated": false,
  "error": "syntax error at or near ..."
}
```

---

## Rate-limit (Redis): что именно лимитируем и какие ключи

Rate-limit сейчас включён только на **public**:

* `POST /api/auth/register`
* `POST /api/auth/login`

Формат ключей в Redis:

* login: `rl:login:<login_lower>:ip:<ip_lower>`
* register: `rl:register:<login_lower>:ip:<ip_lower>`

Окно и лимит задаются env:

* `LOGIN_RL_WINDOW_SECONDS` (default 900)
* `LOGIN_RL_MAX_ATTEMPTS` (default 10)
* `REGISTER_RL_WINDOW_SECONDS` (default 3600)
* `REGISTER_RL_MAX_ATTEMPTS` (default 10)

---

## База данных (основные таблицы)

* `users`, `roles`, `user_roles`
* `permissions`, `role_permissions`, `user_permission_overrides`
* `external_accounts`, `auth_providers`
* `refresh_tokens`, `auth_events`
* `command_switches`

---

## Конфигурация и env

Ключевые переменные:

* `JWT_SECRET` — секрет HMAC для access JWT (не менее 32 байт).

* `JWT_ISSUER` — issuer для JWT (default `lsp-api-gateway`).

* `JWT_ACCESS_TTL` — TTL access (например `PT15M`).

* `JWT_REFRESH_TTL` — TTL refresh (например `30d`).

* `JWT_REFRESH_PEPPER` — pepper для хеширования refresh.

* `INTERNAL_API_TOKEN` — общий токен для `/internal/**` (заголовок `X-Internal-Token`).

* `DEV_ADMIN_CODE_ENABLED` / `DEV_ADMIN_CODE` — dev backdoor для `/internal/rbac/elevate-by-code`.

* `REDIS_HOST`, `REDIS_PORT` — rate-limit.

* `LOGIN_RL_WINDOW_SECONDS`, `LOGIN_RL_MAX_ATTEMPTS`

* `REGISTER_RL_WINDOW_SECONDS`, `REGISTER_RL_MAX_ATTEMPTS`

* `SPRING_DATASOURCE_*` — Postgres.

Порт по умолчанию: `8086`.

Swagger UI: `/swagger-ui.html`

---

## Локальный запуск

1. Поднять Postgres/Redis (см. `infra/`).
2. Задать `.env` / переменные окружения.
3. Запустить:

```bash
mvn -pl services/api-gateway-service spring-boot:run
```

---

## Ограничения и заметки

* `/internal/**` защищено shared token (без mTLS).
* Права пока демонстрационные (минимальный набор).
* `DEVGOD` разворачивается только в effective permissions; raw используется для критичных операций (hard delete).
