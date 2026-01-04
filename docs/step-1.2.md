# Отчёт 1.2

> **Основание для отчёта:** шаблон `t.md` + текущий архив проекта `logic-signal-protector.zip` (код, конфигурации и SQL-миграции) + контекст из переписки.

## 1) Что реализовано

- Поднятие локальной инфраструктуры для разработки через Docker Compose: PostgreSQL + Redis.
- Автоматическое создание схемы базы данных через Flyway при старте приложения.
- Сервис `api-gateway-service` запускается на отдельном порту и подключается к контейнерной базе данных.
- Реализованы HTTP эндпоинты аутентификации: регистрация, вход, обновление токена, выход.
- Подключены Swagger (OpenAPI) для ручного тестирования и Actuator для диагностики.
- Добавлен rate limiting (ограничение частоты попыток логина) на базе Redis.

## 2) Воспроизводимая инфраструктура

### 2.1 Что такое Docker
Docker — это способ запускать сервисы в контейнерах: изолированных процессах с предсказуемыми версиями зависимостей.
Контейнер удобен тем, что вы не «ставите PostgreSQL и Redis в систему», а запускаете их как готовые сервисы одной командой.

**Почему это важно для нас:** один и тот же проект должен одинаково запускаться на разных компьютерах.

### 2.2 Что такое Docker Desktop и WSL (Windows Subsystem for Linux)
На Windows чаще всего используется Docker Desktop. Он запускает Docker Engine внутри WSL 2 (подсистема Linux в Windows).
Из-за этого иногда появляются сообщения «WSL нужно обновить».
В вашем случае WSL уже установлен и работает (по выводу `wsl --version`).

### 2.3 Что такое Docker Compose
Docker Compose — это файл формата YAML, который описывает набор контейнеров (что поднять и как связать).
Одна команда `docker compose up -d` поднимает весь набор сервисов, нужных для разработки.

### 2.4 Как используется Docker Compose в проекте
Файл: `infra/docker-compose.yml`.

Поднимаются два контейнера:
- **PostgreSQL** (база данных):
  - контейнер: `lsp_gateway_postgres`
  - база данных: `lsp_gateway`
  - пользователь: `lsp_gateway_app`
  - пароль: `1111`
  - порт на Windows: **15432** (чтобы не конфликтовать с возможным локальным PostgreSQL)
  - данные сохраняются в Docker-томе (volume), чтобы не терять их после перезапуска контейнера
- **Redis** (быстрое in-memory хранилище):
  - контейнер: `lsp_gateway_redis`
  - порт на Windows: **6379**

### 2.5 Минимальные команды для работы с Docker Compose
Из папки `infra`:

```bat
docker compose up -d
docker compose ps
docker compose logs -f gateway-postgres
docker compose logs -f gateway-redis
```

Пояснение: `gateway-postgres` и `gateway-redis` — это имена сервисов внутри docker-compose.yml (не путать с “postgres”).

## 3) Миграции базы данных (Flyway)

### 3.1 Что такое Flyway
Flyway — инструмент версионирования схемы базы данных. Схема хранится как последовательность SQL-файлов.
При старте приложения Flyway проверяет, какие миграции применены, и применяет новые.

### 3.2 Зачем он нужен
- Можно поднять базу «с нуля» одной командой, без ручных SQL.
- Изменения схемы фиксируются в виде версий, а не “где-то в голове”.
- Легче откатывать и воспроизводить состояние базы для тестов.

### 3.3 Как реализовано в проекте
Миграции лежат в папке: `services/api-gateway-service/src/main/resources/db/migration/`.
На шаге 1.2 есть миграция `V1__init.sql`, которая создаёт основные таблицы.
Flyway ведёт служебную таблицу `flyway_schema_history`, где хранит историю применённых миграций.

## 4) Строгая проверка схемы базы данных (JPA + Hibernate, режим validate)

### 4.1 Что такое JPA и Hibernate
Java Persistence API (JPA) — стандарт работы с базой данных через Java-объекты.
Hibernate — реализация JPA, которая превращает операции над объектами в SQL-запросы.

### 4.2 Зачем включён режим validate
В `application.yml` включено `ddl-auto: validate`. Это значит: Hibernate не создаёт таблицы сам,
а проверяет, что таблицы и колонки в базе соответствуют вашим Java-сущностям (Entity).
Если схема базы и код разъехались — приложение падает сразу при запуске. Это помогает ловить ошибки раньше.

## 5) REST API и валидация входных данных

### 5.1 Что такое HTTP, REST и JSON (в двух словах)
- HyperText Transfer Protocol (HTTP) — протокол запрос/ответ между клиентом и сервером.
- Representational State Transfer (REST) — стиль, когда вы вызываете адреса (эндпоинты)
  и получаете ответы стандартными методами (GET, POST и так далее).
- JavaScript Object Notation (JSON) — текстовый формат для передачи данных.

### 5.2 Как это реализовано в проекте
В `api-gateway-service` есть контроллер `AuthController`, который принимает JSON и возвращает JSON.
Входные данные описаны в DTO (Data Transfer Object — объект передачи данных), например `RegisterRequest`.
Для проверки входных данных используется валидация через аннотации `@NotBlank`, `@Size` и `@Valid`.

### 5.3 Почему ошибки валидации дают HTTP 400
Если клиент прислал некорректные данные (например, слишком короткий пароль),
Spring выбрасывает исключение `MethodArgumentNotValidException`,
которое обрабатывается глобальным обработчиком ошибок и возвращает статус HTTP 400.

## 6) Spring Security и JSON Web Token

### 6.1 Что такое Spring Security
Spring Security — библиотека, которая добавляет “фильтры безопасности” перед контроллерами.
Она решает: какие запросы доступны без входа, какие требуют токен, как проверить токен и как определить текущего пользователя.

### 6.2 Что такое JSON Web Token
JSON Web Token (JWT) — это строка-токен, внутри которой находятся данные (claims) о пользователе и срок действия.
Токен подписывается секретом сервиса, поэтому его нельзя подделать без знания секрета.

### 6.3 Зачем два токена: access token и refresh token
- Access token: короткоживущий (обычно минуты). Нужен для доступа к защищённым ручкам.
- Refresh token: живёт дольше (дни/недели). Нужен, чтобы получать новый access token без повторного логина.
  Refresh token хранится на сервере в базе данных (в виде хэша) — это позволяет делать logout и “отзывать” сессию.

### 6.4 Как это реализовано в проекте
Файлы:
- `SecurityConfig.java` — правила, какие пути открыты и какая схема аутентификации используется.
- `AuthConfig.java` — создание компонентов кодирования/декодирования JWT и настройка секрета.
- `TokenService.java` — выпуск access token.
- `RefreshTokenService.java` — выпуск/ротация/отзыв refresh token.

## 7) Redis и rate limiting

### 7.1 Что такое Redis
Redis — быстрое in-memory хранилище ключ-значение. Оно подходит для счётчиков, кэша и временных данных.

### 7.2 Зачем он нужен здесь
На шаге 1.2 Redis используется для ограничения частоты попыток логина (rate limiting),
чтобы защититься от перебора пароля.

### 7.3 Как реализовано в проекте
Файл: `RedisRateLimitService.java`.
Логика: по ключу (например, login + IP) в Redis хранится счётчик попыток и время “окна”.
Если лимит превышен — возвращается ошибка HTTP 429 (Too Many Requests).

## 8) Swagger (OpenAPI)

### 8.1 Что это
OpenAPI — формат описания API (путей, методов, схем JSON).
Swagger UI — веб-интерфейс, который позволяет увидеть API и вызывать его из браузера.

### 8.2 Зачем это нужно
Это самый простой способ тестировать API без отдельного клиента: не нужно писать код, достаточно браузера.

### 8.3 Как реализовано в проекте
В `pom.xml` подключена зависимость `springdoc-openapi-starter-webmvc-ui`.
Swagger UI доступен по адресу: `http://localhost:8086/swagger-ui/index.html`.

## 9) Spring Boot Actuator

### 9.1 Что это
Actuator добавляет диагностические эндпоинты: здоровье сервиса, метрики, техническую информацию.

### 9.2 Как используется в проекте
В `application.yml` настроено раскрытие эндпоинтов здоровья и метрик.
Обычно проверяют: `http://localhost:8086/actuator/health`.

## 10) Lombok

Lombok уменьшает количество повторяющегося кода (конструкторы, геттеры/сеттеры и т.п.) и делает классы короче.

## 11) MapStruct

MapStruct генерирует код преобразования между объектами (например, Entity → DTO). Это уменьшает ручной код и ошибки копирования полей.

## 12) Resilience4j

Resilience4j — библиотека отказоустойчивости (circuit breaker, retry, rate limiter).
На этом шаге она подключена как база для будущих внешних вызовов (например, к market-data-service).

## 13) Testcontainers

Testcontainers — библиотека для интеграционных тестов, которые поднимают настоящие контейнеры (PostgreSQL, Redis) прямо во время тестов.
На шаге 1.2 зависимости подготовлены, чтобы в дальнейшем писать интеграционные тесты.

## 14) Схема работы сервиса `api-gateway-service` (пошагово)

### 14.1 Регистрация пользователя (`POST /api/auth/register`)
1) Клиент отправляет JSON с логином и паролем.
2) Валидация проверяет формат данных.
3) `UserService.register(...)` создаёт пользователя и записывает его в таблицу `users`.
4) Записывается событие в таблицу `auth_events`.
5) Ответ — HTTP 201 и JSON с данными пользователя.

### 14.2 Вход (`POST /api/auth/login`)
1) Клиент отправляет логин и пароль.
2) Rate limiting (Redis) проверяет частоту попыток.
3) `UserService.authenticate(...)` проверяет пароль (сравнение с хэшем).
4) Выпускается access token (JWT) и refresh token.
5) Ответ — HTTP 200 и JSON с токенами.

### 14.3 Обновление токена (`POST /api/auth/refresh`)
1) Клиент отправляет refresh token.
2) Сервис находит его хэш в таблице `refresh_tokens`, проверяет срок и отзыв.
3) Старый refresh token помечается как отозванный и создаётся новый (ротация).
4) Возвращается новая пара токенов.

### 14.4 Выход (`POST /api/auth/logout`)
1) Клиент отправляет refresh token.
2) Сервис помечает запись как отозванную (`revoked_at`).
3) После этого refresh token становится бесполезным.

## 15) Фактический пример работы (как тестировать)

### 15.1 Запуск
Самый простой вариант — выполнить `start.cmd` из корня проекта.
Он поднимет контейнеры и запустит сервис.

Либо вручную:
```bat
cd infra
docker compose up -d
cd ..
mvn -pl services/api-gateway-service spring-boot:run
```

### 15.2 Проверка через Swagger UI
Открыть: `http://localhost:8086/swagger-ui/index.html`
Дальше последовательно вызвать: register → login → refresh → logout.

### 15.3 Проверка через командную строку (Windows)
Регистрация:
```bat
curl.exe -i -X POST "http://localhost:8086/api/auth/register" ^
  -H "Content-Type: application/json" ^
  -d "{"login":"nikita","password":"password123"}"
```

Логин:
```bat
curl.exe -i -X POST "http://localhost:8086/api/auth/login" ^
  -H "Content-Type: application/json" ^
  -d "{"login":"nikita","password":"password123"}"
```

### 15.4 Как зайти в PostgreSQL внутри контейнера (для проверки данных)
Команда (важно указать реальное имя контейнера):
```bat
docker exec -it lsp_gateway_postgres psql -U lsp_gateway_app -d lsp_gateway
```

Дальше можно выполнить, например:
```sql
select * from flyway_schema_history order by installed_rank;
select id, login, created_at, is_active from users order by id desc;
```

## 16) План / ожидания на шаг 1.3

- Добавить настоящую модель прав (permissions) и проверки доступа к защищённым операциям.
- Подготовить межсервисную безопасность: остальные сервисы должны уметь проверять access token.
- Добавить интеграционные тесты (Testcontainers) для критичных сценариев регистрации/логина/refresh.
- Начать интеграцию внешнего аккаунта (например, Telegram) через таблицу `external_accounts`.

## 17) Индекс файлов проекта (что за что отвечает)

Ниже перечислены файлы проекта (исключены папки сборки и системные артефакты).
Описание даётся максимально “для новичка”.


### Папка: `/`

- `.env.example` — Пример файла настроек/окружения. Нужен как шаблон, чтобы создать собственный вариант.
- `.gitignore` — Список файлов/папок, которые не должны попадать в репозиторий Git.
- `desktop.ini` — Файл проекта (служебный или вспомогательный).
- `pom.xml` — Файл проекта (служебный или вспомогательный).
- `start.cmd` — Скрипт для Windows: запускает Docker Compose и поднимает api-gateway-service через Maven.

### Папка: `docs`

- `docs/mvn-cheatsheet.md` — Проектная документация (Markdown). Важно: часть документов могла устареть после изменения портов/настроек.
- `docs/project-summary.md` — Проектная документация (Markdown). Важно: часть документов могла устареть после изменения портов/настроек.
- `docs/step-0.0-bootstrap.md` — Проектная документация (Markdown). Важно: часть документов могла устареть после изменения портов/настроек.
- `docs/step-1.1-auth.md` — Проектная документация (Markdown). Важно: часть документов могла устареть после изменения портов/настроек.
- `docs/step-1.2-report.md` — Проектная документация (Markdown). Важно: часть документов могла устареть после изменения портов/настроек.

### Папка: `infra`

- `infra/docker-compose.yml` — Файл Docker Compose: описывает контейнеры PostgreSQL и Redis для локальной разработки (порты, логины, тома, сеть).

### Папка: `services/alerts-service`

- `services/alerts-service/pom.xml` — Файл конфигурации Maven для сервиса alerts-service.

### Папка: `services/alerts-service/src/main/java/com/logicsignalprotector/alerts`

- `services/alerts-service/src/main/java/com/logicsignalprotector/alerts/AlertsServiceApplication.java` — Java-код сервиса alerts-service (пока базовый каркас).

### Папка: `services/alerts-service/src/main/java/com/logicsignalprotector/alerts/api`

- `services/alerts-service/src/main/java/com/logicsignalprotector/alerts/api/PingController.java` — Java-код сервиса alerts-service (пока базовый каркас).

### Папка: `services/alerts-service/src/main/resources`

- `services/alerts-service/src/main/resources/application.yml` — Конфигурация Spring Boot (YAML): порты, подключение к базе данных, Redis, миграции Flyway, параметры токенов и т.п.

### Папка: `services/api-gateway-service`

- `services/api-gateway-service/pom.xml` — Файл конфигурации Maven для сервиса api-gateway-service: зависимости (Spring Boot, база данных, безопасность, миграции и т.д.) и плагины сборки.

### Папка: `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway`

- `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/ApiGatewayServiceApplication.java` — Java-исходник.

### Папка: `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/api`

- `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/api/PingController.java` — Java-исходник.

### Папка: `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/auth/api`

- `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/auth/api/AuthController.java` — Java-код подсистемы аутентификации: контроллеры, сервисы, сущности, репозитории, безопасность.

### Папка: `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/auth/api/dto`

- `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/auth/api/dto/LoginRequest.java` — Java-код подсистемы аутентификации: контроллеры, сервисы, сущности, репозитории, безопасность.
- `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/auth/api/dto/LogoutRequest.java` — Java-код подсистемы аутентификации: контроллеры, сервисы, сущности, репозитории, безопасность.
- `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/auth/api/dto/RefreshRequest.java` — Java-код подсистемы аутентификации: контроллеры, сервисы, сущности, репозитории, безопасность.
- `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/auth/api/dto/RegisterRequest.java` — Java-код подсистемы аутентификации: контроллеры, сервисы, сущности, репозитории, безопасность.
- `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/auth/api/dto/RegisterResponse.java` — Java-код подсистемы аутентификации: контроллеры, сервисы, сущности, репозитории, безопасность.
- `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/auth/api/dto/TokenResponse.java` — Java-код подсистемы аутентификации: контроллеры, сервисы, сущности, репозитории, безопасность.
- `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/auth/api/dto/TokensResponse.java` — Java-код подсистемы аутентификации: контроллеры, сервисы, сущности, репозитории, безопасность.

### Папка: `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/auth/api/mapper`

- `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/auth/api/mapper/AuthApiMapper.java` — Java-код подсистемы аутентификации: контроллеры, сервисы, сущности, репозитории, безопасность.

### Папка: `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/auth/config`

- `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/auth/config/AuthConfig.java` — Java-код подсистемы аутентификации: контроллеры, сервисы, сущности, репозитории, безопасность.

### Папка: `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/auth/domain`

- `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/auth/domain/AuthEventEntity.java` — Java-код подсистемы аутентификации: контроллеры, сервисы, сущности, репозитории, безопасность.
- `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/auth/domain/AuthProviderEntity.java` — Java-код подсистемы аутентификации: контроллеры, сервисы, сущности, репозитории, безопасность.
- `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/auth/domain/ExternalAccountEntity.java` — Java-код подсистемы аутентификации: контроллеры, сервисы, сущности, репозитории, безопасность.
- `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/auth/domain/RefreshTokenEntity.java` — Java-код подсистемы аутентификации: контроллеры, сервисы, сущности, репозитории, безопасность.
- `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/auth/domain/RoleEntity.java` — Java-код подсистемы аутентификации: контроллеры, сервисы, сущности, репозитории, безопасность.
- `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/auth/domain/UserEntity.java` — Java-код подсистемы аутентификации: контроллеры, сервисы, сущности, репозитории, безопасность.

### Папка: `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/auth/repository`

- `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/auth/repository/AuthEventRepository.java` — Java-код подсистемы аутентификации: контроллеры, сервисы, сущности, репозитории, безопасность.
- `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/auth/repository/AuthProviderRepository.java` — Java-код подсистемы аутентификации: контроллеры, сервисы, сущности, репозитории, безопасность.
- `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/auth/repository/ExternalAccountRepository.java` — Java-код подсистемы аутентификации: контроллеры, сервисы, сущности, репозитории, безопасность.
- `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/auth/repository/RefreshTokenRepository.java` — Java-код подсистемы аутентификации: контроллеры, сервисы, сущности, репозитории, безопасность.
- `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/auth/repository/RoleRepository.java` — Java-код подсистемы аутентификации: контроллеры, сервисы, сущности, репозитории, безопасность.
- `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/auth/repository/UserRepository.java` — Java-код подсистемы аутентификации: контроллеры, сервисы, сущности, репозитории, безопасность.

### Папка: `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/auth/security`

- `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/auth/security/SecurityConfig.java` — Java-код подсистемы аутентификации: контроллеры, сервисы, сущности, репозитории, безопасность.

### Папка: `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/auth/service`

- `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/auth/service/AuthAuditService.java` — Java-код подсистемы аутентификации: контроллеры, сервисы, сущности, репозитории, безопасность.
- `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/auth/service/AuthUnauthorizedException.java` — Java-код подсистемы аутентификации: контроллеры, сервисы, сущности, репозитории, безопасность.
- `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/auth/service/RefreshTokenService.java` — Java-код подсистемы аутентификации: контроллеры, сервисы, сущности, репозитории, безопасность.
- `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/auth/service/TokenService.java` — Java-код подсистемы аутентификации: контроллеры, сервисы, сущности, репозитории, безопасность.
- `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/auth/service/UserService.java` — Java-код подсистемы аутентификации: контроллеры, сервисы, сущности, репозитории, безопасность.

### Папка: `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/common/ratelimit`

- `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/common/ratelimit/RedisRateLimitService.java` — Общий Java-код сервиса: обработка ошибок, rate limiting, вспомогательные компоненты.
- `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/common/ratelimit/TooManyRequestsException.java` — Общий Java-код сервиса: обработка ошибок, rate limiting, вспомогательные компоненты.

### Папка: `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/common/web`

- `services/api-gateway-service/src/main/java/com/logicsignalprotector/apigateway/common/web/ApiExceptionHandler.java` — Общий Java-код сервиса: обработка ошибок, rate limiting, вспомогательные компоненты.

### Папка: `services/api-gateway-service/src/main/resources`

- `services/api-gateway-service/src/main/resources/application.yml` — Конфигурация Spring Boot (YAML): порты, подключение к базе данных, Redis, миграции Flyway, параметры токенов и т.п.
- `services/api-gateway-service/src/main/resources/logback-spring.xml` — Настройки логирования Logback для Spring Boot (в нашем случае — вывод логов в JSON формате).

### Папка: `services/api-gateway-service/src/main/resources/db/migration`

- `services/api-gateway-service/src/main/resources/db/migration/V1__init.sql` — SQL-миграция Flyway: создаёт и/или изменяет структуру базы данных при старте приложения.

### Папка: `services/market-data-service`

- `services/market-data-service/pom.xml` — Файл конфигурации Maven для сервиса market-data-service.

### Папка: `services/market-data-service/src/main/java/com/logicsignalprotector/marketdata`

- `services/market-data-service/src/main/java/com/logicsignalprotector/marketdata/MarketDataServiceApplication.java` — Java-код сервиса market-data-service (пока базовый каркас).

### Папка: `services/market-data-service/src/main/java/com/logicsignalprotector/marketdata/api`

- `services/market-data-service/src/main/java/com/logicsignalprotector/marketdata/api/PingController.java` — Java-код сервиса market-data-service (пока базовый каркас).

### Папка: `services/market-data-service/src/main/resources`

- `services/market-data-service/src/main/resources/application.yml` — Конфигурация Spring Boot (YAML): порты, подключение к базе данных, Redis, миграции Flyway, параметры токенов и т.п.

### Папка: `services/virtual-broker-service`

- `services/virtual-broker-service/pom.xml` — Файл конфигурации Maven для сервиса virtual-broker-service.

### Папка: `services/virtual-broker-service/src/main/java/com/logicsignalprotector/virtualbroker`

- `services/virtual-broker-service/src/main/java/com/logicsignalprotector/virtualbroker/VirtualBrokerServiceApplication.java` — Java-код сервиса virtual-broker-service (пока базовый каркас).

### Папка: `services/virtual-broker-service/src/main/java/com/logicsignalprotector/virtualbroker/api`

- `services/virtual-broker-service/src/main/java/com/logicsignalprotector/virtualbroker/api/PingController.java` — Java-код сервиса virtual-broker-service (пока базовый каркас).

### Папка: `services/virtual-broker-service/src/main/resources`

- `services/virtual-broker-service/src/main/resources/application.yml` — Конфигурация Spring Boot (YAML): порты, подключение к базе данных, Redis, миграции Flyway, параметры токенов и т.п.
