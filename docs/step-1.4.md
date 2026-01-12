# Шаг 1.4 - Telegram как "консоль" + админ-инструменты RBAC

Цель шага 1.4:

1) Сделать ввод команд в Telegram удобным: **/login = login = логин** (и аналогично для остальных команд).
2) Добавить **logout** (отвязка Telegram-аккаунта) с подтверждением.
3) Ограничить “сырые” ответы/ошибки (как HTTP/JSON) спец-правом **`ADMIN_ANSWERS_LOG`**.
4) Добавить dev-команду **`adminlogin <code>`** для выдачи роли ADMIN по коду.
5) Добавить админ-команды управления ролями и permission-overrides через Telegram (требует **`ADMIN_USERS_PERMS_REVOKE`**).

---

## 1) Что меняется по сервисам

### 1.1 `logic-commands-center-service`

* Парсинг команд:
  * команды работают в формате `/cmd` и `cmd`
  * добавлены RU-алиасы (пример: `логин → login`, `логаут/выход → logout`, `помощь → help`)
* Добавлено состояние подтверждения для logout:
  * `ChatState.AWAIT_LOGOUT_CONFIRM` с TTL (по умолчанию 60 секунд)
* Добавлен in-memory rate-limit для `adminlogin`:
  * по умолчанию 5 попыток за 10 минут на `provider|externalUserId`

### 1.2 `api-gateway-service`

* Добавлены permissions и выдача их роли `ADMIN` через Flyway:
  * `ADMIN_ANSWERS_LOG`
  * `ADMIN_USERS_PERMS_REVOKE`
* Добавлена internal ручка для logout:
  * `POST /internal/identity/unlink`
* Добавлены internal ручки для админских RBAC-операций:
  * elevate-by-code (dev)
  * просмотр пользователей
  * выдача/снятие ролей
  * grant/deny/revoke permission overrides

---

## 2) Команды Telegram (пользовательский уровень)

Команды можно писать **в трёх вариантах**:

* `/login` / `login` / `логин`
* `/logout` / `logout` / `логаут` (также `выход`)

Список команд см. прямо в Telegram: **`/help`** (или `help`, или `помощь`).

### 2.1 Login / Register

Вариант A (в одну строку):

* `/login <login> <password>`
* `/register <login> <password>`

Вариант B (в 2 шага):

1) `/login` (или `логин`)
2) отдельным сообщением: `<login> <password>`

Аналогично для `/register`.

### 2.2 Logout (с подтверждением)

Семантика **logout = отвязать Telegram-аккаунт** (удалить запись в `external_accounts` для `provider=TELEGRAM`).

Сценарий:

1) `/logout` (или `логаут`, или `выход`)
2) подтвердить в течение TTL:
   * `logout yes` **или** `logout да`
   * отмена: `cancel` / `отмена`

---

## 3) Админ-команды (RBAC консоль)

### 3.1 `adminlogin <code>` (dev backdoor)

Команда:

* `/adminlogin <code>` (или `adminlogin <code>`, или `админлогин <code>`)

Результат:

* текущему **привязанному** пользователю добавляется роль `ADMIN`

Ограничения безопасности:

* работает только при включенном флаге (см. конфиг ниже)
* лимит попыток (in-memory)

### 3.2 Управление пользователями

Требование: у пользователя должен быть permission **`ADMIN_USERS_PERMS_REVOKE`**.

Команды:

* `/users` / `users` / `пользователи` — список
* `/user <login>` / `user <login>` / `пользователь <login>` — карточка пользователя
* `/roles` / `roles` / `роли` — справочник ролей
* `/perms` / `perms` / `права` — справочник permissions

Мутации:

* `/grantrole <login> <roleCode>` (или `выдатьроль ...`)
* `/revokerole <login> <roleCode>` (или `отозватьроль ...`)
* `/grantperm <login> <permCode> [reason]` (или `разрешитьправо ...`)
* `/denyperm <login> <permCode> [reason]` (или `запретитьправо ...`)
* `/revokeperm <login> <permCode>` (или `снятьправо ...`)

---

## 4) Политика “сырых” ответов (debug outputs)

Permission: **`ADMIN_ANSWERS_LOG`**.

* Если permission **есть**:
  * ошибки показываются как `friendly + RAW: <http body>`
  * ответы downstream-команд (`market/alerts/broker/trade`) печатаются целиком

* Если permission **нет**:
  * ошибки — короткие (без JSON)
  * ответы downstream — только “Ок. Команда выполнена.”

---

## 5) Конфигурация

### 5.1 `api-gateway-service`

Dev admin-code:

* `DEV_ADMIN_CODE_ENABLED=true|false`
* `DEV_ADMIN_CODE=<secret>`

### 5.2 `logic-commands-center-service`

TTL подтверждения logout:

* `chat.logout.confirm-ttl` (по умолчанию `PT60S`)

Rate limit для adminlogin:

* `DEV_ADMINLOGIN_RATE_WINDOW` (по умолчанию `PT10M`)
* `DEV_ADMINLOGIN_RATE_MAX_ATTEMPTS` (по умолчанию `5`)

---

## 6) Быстрый чек-лист ручной проверки

1) `/help` показывает все команды и алиасы.
2) `логин` запускает сценарий входа; затем `<login> <password>` привязывает аккаунт.
3) `/logout` требует подтверждение; `logout да` отвязывает аккаунт.
4) без `ADMIN_ANSWERS_LOG` команды `market/alerts/...` не печатают RAW.
5) `adminlogin <code>` (при включенном флаге) выдаёт роль ADMIN.
6) админ-команды `/users`, `/user ...`, `/grantrole ...` работают только при наличии `ADMIN_USERS_PERMS_REVOKE`.
