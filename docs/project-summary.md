# Дневник проекта (Logic Signal Protector)

## Хронология шагов (краткая выжимка)
- Шаг 0 — bootstrap: корневой Maven-агрегатор, три сервисных модуля, простейший `/ping` в gateway.
- Шаг 1.1 — подсистема аутентификации в `api-gateway-service`: БД `lsp_gateway`, таблицы `users`/`auth_provider`/`external_accounts`, роль `lsp_gateway_app`, REST `POST /api/auth/register`.
- Далее (план) — бизнес-логика core-сервисов, интеграции (Telegram/уведомления), авторизация запросов.

---

## Сервисы

### 1) `api-gateway-service`

**Глобальная задача / функция / нагрузка**
- Входная точка HTTP/REST для внешних клиентов.
- Регистрация и (в перспективе) аутентификация пользователей.
- Проксирование/оркестрация запросов к остальным сервисам.
- Нагрузка: входящий веб-трафик; в будущем — проверка прав, rate limiting, защита от брутфорса, агрегирование ответов.

**Текущее состояние (после шага 1.1)**
- Стек: Java 21; Spring Boot 3.2.x; зависимости: `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `org.postgresql:postgresql` (runtime), `spring-security-crypto` (BCrypt).
- Конфиг (`services/api-gateway-service/src/main/resources/application.yml`):
  - `spring.datasource.url=jdbc:postgresql://localhost:5432/lsp_gateway`
  - `spring.datasource.username=lsp_gateway_app`
  - `spring.datasource.password=${SPRING_DATASOURCE_PASSWORD}`
  - `spring.jpa.hibernate.ddl-auto=validate`, `show-sql=true`, формат SQL включён
  - `server.port=8080`
- REST:
  - `GET /ping` — health.
  - `POST /api/auth/register` — регистрация, 201 -> `{id, login}`, 400 при дубликате логина (сообщение: «Пользователь с таким логином уже существует»).
- Тестов нет; сборка проходит с `-DskipTests`.

**Классы и связь с бизнес-логикой**
- `UserEntity` → `users`: логин (уникальный), BCrypt-хэш пароля, created_at, активность. Ядро идентичности, на `users.id` опираются привязки и дальнейшие сервисы.
- `AuthProviderEntity` → `auth_provider`: справочник провайдеров (WEB/TELEGRAM и др.) для привязки внешних аккаунтов.
- `ExternalAccountEntity` → `external_accounts`: связь внутреннего пользователя с внешним идентификатором; гарантирует уникальность `(provider_code, external_id)` и `(user_id, provider_code)`.
- `UserService`: бизнес-слой регистрации (проверка дубликата, BCrypt, save в транзакции).
- `AuthController`: HTTP-слой регистрации (JSON → сервис → HTTP 201/400).
- `AuthConfig`: бин `PasswordEncoder` (BCrypt).
- `PingController`: health-check `/ping`.
- Репозитории: `UserRepository`, `AuthProviderRepository`, `ExternalAccountRepository` — поиск/CRUD для домена аутентификации.
- equals/hashCode: `UserEntity` и `ExternalAccountEntity` — по `id` с `Hibernate.getClass`; `AuthProviderEntity` — по `code` (стабильный бизнес-ключ). Это нужно для корректной работы JPA/кэшей/коллекций.

**База данных (PostgreSQL, `lsp_gateway`)** `(см шаг 1.1)`
- Идея: отдельное хранилище только под аутентификацию и привязку внешних каналов.
- Таблицы (по смыслу):
  - `users`: внутренние пользователи. PK `id BIGSERIAL`; уникальный `login`; `password_hash` (BCrypt); `created_at`; `is_active`. Ядро идентичности.
  - `auth_provider`: справочник провайдеров. PK `code`; `name`. Единый список поддерживаемых каналов.
  - `external_accounts`: привязка внутренних пользователей к внешним аккаунтам. PK `id`; FK `user_id` -> `users`; FK `provider_code` -> `auth_provider`; `external_id`; `created_at`; `UNIQUE(provider_code, external_id)` — внешний аккаунт не дублируется; `UNIQUE(user_id, provider_code)` — один аккаунт на провайдера на пользователя.
- Роль `lsp_gateway_app`:
  - CONNECT к БД, USAGE на schema `public`, CRUD на таблицы, USAGE/SELECT на sequences (включая default privileges) — нужно для `BIGSERIAL`.

**Открытые задачи для gateway**
- Политика сложности паролей, rate limiting на регистрацию/логин.
- Единый JSON-формат ошибок; обработка ошибок парсинга тела.
- Подключение Spring Security/JWT, когда появится полноценная авторизация.
- Unit/integration-тесты.

### 2) Core-service (условно, заглушка)

**Глобальная задача / функция / нагрузка**
- Предполагаемое доменное ядро: правила/сценарии/сигналы, расчёт «логических сигналов», управление бизнес-данными. (TBD, будет уточняться.)

**Текущее состояние**
- Каркас Spring Boot (pom, `@SpringBootApplication`, базовый `application.yml`). Реальной логики/БД нет.

**Классы**
- Пока только стартовый класс. Планируемые сущности/сервисы — TBD; по мере появления будут описаны с привязкой к бизнес-задачам.

**База данных**
- Не подключена. План: отдельная БД/схема под доменную модель или общая БД с выделенной схемой (решить позже). Таблицы будут описаны по смыслу после проектирования.

**Открытые задачи**
- Определить доменные сущности (правила, сценарии, источники, сигналы), API, требования к хранению и индексации.

### 3) Integration/notifications-service (условно, заглушка)

**Глобальная задача / функция / нагрузка**
- Интеграции и уведомления: Telegram-бот, e-mail и др.; получение событий из core/gateway и доставка сообщений. (TBD.)

**Текущее состояние**
- Каркас Spring Boot (pom, `@SpringBootApplication`, базовый `application.yml`). Реальной логики/БД нет.

**Классы**
- Пока только стартовый класс. Планируемые адаптеры (Telegram, e-mail), сервисы отправки, DTO/мапперы — TBD.

**База данных**
- Не подключена. Возможное будущее хранение: состояние доставки, настройки уведомлений пользователей. Описать по мере проектирования.

**Открытые задачи**
- Зафиксировать каналы и формат обмена с core/gateway; требования к надёжности/ретраям/журналированию.

---

## Хронология ключевых изменений (детальнее)
- Шаг 0:
  - Создан корневой Maven-агрегатор `logic-signal-protector`.
  - Подняты три сервисных модуля (gateway + 2 каркаса), в gateway — `/ping`.
  - Документация: `step-0-bootstrap.md`.
- Шаг 1.1:
  - Спроектирована БД `lsp_gateway` (`users`, `auth_provider`, `external_accounts`); создана роль `lsp_gateway_app`, выданы права на таблицы и sequences.
  - Добавлены JPA-сущности и репозитории в gateway.
  - Реализованы `UserService.register` (BCrypt) и REST `POST /api/auth/register`; health `/ping`.
  - Приведён в порядок `application.yml` (datasource/jpa под `spring`, пароль через env).
  - equals/hashCode для User/ExternalAccount переписаны на id + Hibernate class; сообщение о дубликате логина на русском; убран BOM из UserService.java.
  - Документация: `step-1.1-auth.md`; обновлён дневник.

---

## Проверка и запуск (текущее)
- Сборка: `mvn clean compile -pl services/api-gateway-service`
- Запуск: `SPRING_DATASOURCE_PASSWORD=secret mvn spring-boot:run -pl services/api-gateway-service`
  - Требуется живой Postgres с БД `lsp_gateway`, таблицами и ролью `lsp_gateway_app`.
- Ручной тест: `GET /ping`; `POST /api/auth/register` с корректным JSON:
  - новый логин → 201 + `{id, login}` и запись в `users`;
  - повторный логин → 400 + сообщение о дубликате.

---

## Открытые пункты (общие)
- Заполнить назначение/модель/БД для сервисов 2 и 3.
- Добавить тесты.
- Политика паролей, защита от частых попыток.
- Единый JSON-формат ошибок.
- Позже — Spring Security/JWT для защиты остальных эндпоинтов. 
