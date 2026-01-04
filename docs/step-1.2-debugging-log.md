## Журнал ошибок шага 1.2 и как мы их исправили

Этот раздел фиксирует проблемы, которые возникли при сборке/запуске `api-gateway-service`, и конкретные действия, которые привели к рабочему состоянию.

---


### A1) Ошибка сборки Maven: “missing version” для зависимостей

**Симптом**
Команда:

```bat
mvn help:effective-pom
```

падает с ошибками вида:

* `'dependencies.dependency.version' ... is missing` (Testcontainers, Resilience4j и т.д.)

**Причина**
В `pom.xml` были зависимости, для которых **не задана версия**, и при этом не был подключён BOM/управление версиями, которое эти версии “подставляет”.

**Как исправили**

* Добавили управление версиями через **BOM** (в частности для Resilience4j и Testcontainers) **или** проставили версии централизованно.
* После этого Maven смог собрать проект.

**Как проверить**

```bat
mvn -U test
```

(после фикса `mvn test -U` проходил по всем модулям)

---

### A2) Падение на старте из-за Swagger(Springdoc): `NoClassDefFoundError LiteWebJarsResourceResolver`

**Симптом**
`mvn spring-boot:run -pl services/api-gateway-service`
падал на инициализации Spring контекста с ошибкой про:

* `LiteWebJarsResourceResolver` (класс не найден)

**Причина**
Версии `springdoc-openapi` и Spring Boot/Spring Framework оказались **несовместимы**: springdoc ожидал класс, которого в текущей версии Spring уже нет/он переехал.

**Как исправили**
Было два подхода (прошли оба по очереди):

1. **Временный “костыль”**: добавили shim-класс `LiteWebJarsResourceResolver`, чтобы springdoc загрузился.
2. **Правильное решение**: понизили springdoc до версии, совместимой с Boot `3.2.5` (у нас это стало: `springdoc 2.6.0`). После этого shim по смыслу больше не нужен (или может остаться, но он уже не “ядро решения”).

**Как проверить**

* Сервис стартует и Swagger UI открывается:

  * `http://localhost:8086/swagger-ui/index.html`
* `/v3/api-docs` отдаёт OpenAPI.

---

### A3) Ошибка подключения к PostgreSQL: `SQL State: 28P01` (authentication failed)

**Симптом**
При старте сервиса Flyway не может подключиться к БД:

* `SQL State: 28P01` (ошибка аутентификации)

**Причина**
Сервис пытался подключиться к PostgreSQL по настройкам из `application.yml`, но:

* база ещё не поднята,
* или порт/логин/пароль/имя базы не совпадают с тем, что реально поднято в Docker.

**Как исправили (у нас сейчас рабочая схема)**

1. Поднимаем инфраструктуру из папки `infra`:

```bat
cd infra
docker compose up -d
docker compose ps
```

2. Привели настройки подключения в `application.yml` к реальным контейнерным параметрам:

* PostgreSQL порт на хосте: **15432** (а не 5432)
* база: `lsp_gateway`
* пользователь: `lsp_gateway_app`
* пароль: `1111`

**Как проверить**

* Контейнеры в статусе `Up`:

```bat
docker compose ps
```

* Flyway применил миграцию:
  внутри БД есть `flyway_schema_history` и запись про `V1__init.sql`.

---

### A5) Неправильный `docker exec`: `docker exec -it <container_name> ...` (и “The system cannot find the file specified”)

**Симптом**
Команда вида:

```bat
docker exec -it <container_name> psql -U ...
```

не работает (на Windows может писать “не найден файл”).

**Причина**
`<container_name>` — это placeholder. Его нужно заменить на реальное имя контейнера из `docker compose ps`, например `lsp_gateway_postgres`.

**Как правильно**

```bat
docker exec -it lsp_gateway_postgres psql -U lsp_gateway_app -d lsp_gateway
```

---

### A6) `POST /api/auth/register` даёт 500 из-за `jsonb` (SQLState 42804)

**Симптом**
Регистрация падала 500, а в базе/логах всплывало, что в `auth_events.details_json (jsonb)` пытаются записать не-JSON.

**Причина**
Поле `details_json` в PostgreSQL имеет тип `jsonb`, а Hibernate по умолчанию мог пытаться писать туда строку/неправильный тип.

**Как исправили**
В Entity для события аудита добавили корректный JSON биндинг:

* `@JdbcTypeCode(SqlTypes.JSON)` для поля `detailsJson` в `AuthEventEntity.java`

Теперь Hibernate понимает, что это JSON, и корректно сериализует/пишет в `jsonb`.

**Как проверить**

1. `POST /api/auth/register` возвращает **201 Created**
2. В таблице `auth_events` появляется запись без ошибки по jsonb.

---

### A7) `POST /api/auth/login` даёт 500: `JwtEncodingException` / “Failed to select a JWK signing key”

**Симптом**
Логин падал с 500, причина в логах:

* `JwtEncodingException: Failed to select a JWK signing key`

**Причина**
JWT подписывался HMAC-ключом, но при выпуске токена не был явно задан алгоритм/заголовок, и `JwtEncoder` не мог выбрать корректный ключ.

**Как исправили**
В `TokenService.java` при выпуске токена добавили явный заголовок подписи:

* `JwsHeader.with(MacAlgorithm.HS256).build()`
* и вызов `jwtEncoder.encode(...)` теперь идёт с header + claims.

**Как проверить**

* `POST /api/auth/login` возвращает **200 OK**
* в ответе есть токены.

---

### A8) “400 потом 500”

**Наблюдение**
- 400 — при занятом логине или невалидном теле.
- 500 — валились из-за jsonb (A6) и `JwtEncodingException` (A8), хотя клиентского косяка не было.

**Это значит**
- **400** — корректный отказ на плохой ввод (`@Valid`, проверка существующего логина).
- **500** — реальная ошибка сервера (jsonb/JWT), которую надо устранять.

**Что мы сделали полезного для диагностики/фикса**
- `AuthEventEntity.details_json` пометили как JSON → 500 на /register исчез.
- В `TokenService` явно задали HS256 в заголовке JWT → 500 на /login исчез.
- В `ApiExceptionHandler` включили лог стека для любых 500 — теперь причина сразу видна в консоли.

---

### A9) Порты

**Что было сделано**

* Перенесли сервис на порт **8086** (в `application.yml`)
* PostgreSQL в docker подняли на **15432**
* Добавили возможность задавать порт через `SERVER_PORT` в `start.cmd`, чтобы можно было быстро обойти занятость порта.

**Как пользоваться**

```bat
set SERVER_PORT=8087
start.cmd
```

---

## Итог по устойчивости шага 1.2

После всех фиксов:

* `api-gateway-service` **стартует**
* Docker инфраструктура **поднимается одной командой**
* Flyway **применяет миграции**
* Swagger UI **работает**
* `POST /api/auth/register` → **201**
* `POST /api/auth/login` → **200** и токены
* ошибки `jsonb` и `JwtEncodingException` **устранены**
* диагностика ошибок стала лучше (логирование стека на 500)
