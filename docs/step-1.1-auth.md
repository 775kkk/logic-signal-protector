# Шаг 1.1. Подсистема аутентификации в `api-gateway-service`

## 1. Цель шага

Цель данного шага — заложить фундамент аутентификации в проекте **Logic Signal Protector**:

- выделить отдельную базу данных `lsp_gateway` под данные о пользователях;
- описать схему БД для пользователей и их внешних аккаунтов (Telegram и др.);
- настроить отдельную роль PostgreSQL для сервиса `api-gateway-service`;
- подключить Spring Boot-приложение `api-gateway-service` к этой БД через JPA;
- реализовать первый REST-эндпоинт: регистрацию пользователя через `POST /api/auth/register`
  с безопасным хранением пароля (BCrypt-хэш в БД).

Дальнейшая логика (привязка Telegram, авторизация запросов и т.п.) будет строиться на основе этого фундамента.

---

## 2. Создание базы данных и схемы вручную

### 2.1. Создание базы данных `lsp_gateway`

В PostgreSQL создаётся отдельная база, предназначенная только для подсистемы аутентификации:

```sql
CREATE DATABASE lsp_gateway;
````

Дальнейшие команды выполняются уже **внутри** этой базы:

```sql
\c lsp_gateway
```

### 2.2. Таблица `users` — внутренние пользователи системы

Таблица `users` хранит **ядро идентичности** пользователя.
Все остальные сервисы работают с пользователем через его числовой идентификатор `id`.

```sql
-- 1. Таблица пользователей
CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    login           VARCHAR(64) NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE
);
```

Пояснение по полям:

* `id BIGSERIAL PRIMARY KEY`
  Внутренний идентификатор пользователя. Используется во всех внешних связях (`external_accounts`, в будущем — в других сервисах).

* `login VARCHAR(64) NOT NULL UNIQUE`
  Строковый логин пользователя. Наложен уникальный индекс: один логин — не более одного пользователя.

* `password_hash VARCHAR(255) NOT NULL`
  Здесь хранится **BCrypt-хэш** пароля. Сам пароль в БД не хранится и не восстанавливается.
  При регистрации пароль хэшируется, при логине — проверяется через `BCryptPasswordEncoder.matches(...)`.

* `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
  Дата и время создания пользователя.

* `is_active BOOLEAN NOT NULL DEFAULT TRUE`
  Флаг активности. Позволяет деактивировать пользователя без физического удаления строки.

---

### 2.3. Таблица `auth_provider` — справочник провайдеров/каналов

Таблица `auth_provider` хранит типы внешних каналов, через которые пользователь может
взаимодействовать с системой (Telegram, web-интерфейс и т.п.).

```sql
-- 2. Справочник провайдеров аутентификации / каналов
CREATE TABLE auth_provider (
    code VARCHAR(32) PRIMARY KEY,   -- 'TELEGRAM', 'WEB', ...
    name VARCHAR(64) NOT NULL       -- человекочитаемое название
);

-- Базовые провайдеры
INSERT INTO auth_provider (code, name) VALUES
    ('TELEGRAM', 'Telegram бот'),
    ('WEB',      'Веб-интерфейс');
```

Поля:

* `code` — машинно-читаемый код провайдера (PK), используется как внешний ключ в других таблицах.
* `name` — человекочитаемое название, используется для отображения/отчётов.

На момент шага 1.1 заведены два провайдера: Telegram-бот и веб-интерфейс.

---

### 2.4. Таблица `external_accounts` — привязка к внешним аккаунтам

Таблица `external_accounts` реализует принцип:

> “Не важно, откуда пишет пользователь — Telegram, web и т.д.
> Внутри системы у него один аккаунт (`users.id`), а к нему привязаны внешние идентификаторы.”

```sql
-- 3. Внешние аккаунты (Telegram и др.), привязанные к нашему пользователю
CREATE TABLE external_accounts (
    id             BIGSERIAL PRIMARY KEY,

    user_id        BIGINT NOT NULL,
    provider_code  VARCHAR(32) NOT NULL,
    external_id    VARCHAR(128) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Связь с users
    CONSTRAINT fk_external_user
        FOREIGN KEY (user_id)
        REFERENCES users(id)
        ON DELETE CASCADE,

    -- Связь с auth_provider
    CONSTRAINT fk_external_provider
        FOREIGN KEY (provider_code)
        REFERENCES auth_provider(code)
        ON DELETE RESTRICT,

    -- 1) один и тот же внешний аккаунт не может принадлежать разным users
    CONSTRAINT uq_external_provider_external
        UNIQUE (provider_code, external_id),

    -- 2) один user не может иметь больше одной учётки на один provider
    CONSTRAINT uq_external_user_provider
        UNIQUE (user_id, provider_code)
);
```

Поля и ограничения:

* `id BIGSERIAL PRIMARY KEY`
  Технический идентификатор привязки. Упрощает ссылки из других таблиц на конкретную учётку (при необходимости) и работу JPA.

* `user_id BIGINT NOT NULL`
  FK на `users.id`. Обозначает, какому внутреннему пользователю принадлежит данный внешний аккаунт.

* `provider_code VARCHAR(32) NOT NULL`
  FK на `auth_provider.code`. Указывает тип канала (Telegram, Web и т.п.).

* `external_id VARCHAR(128) NOT NULL`
  Идентификатор пользователя на стороне внешней платформы:

  * для Telegram — `chatId`/`userId`;
  * для web — может быть ID во внешней системе и т.п.

* `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
  Дата и время создания привязки.

* `UNIQUE (provider_code, external_id)`
  Один и тот же внешний аккаунт **не может быть привязан к разным пользователям**.

* `UNIQUE (user_id, provider_code)`
  Один пользователь **не может иметь две учётки на один и тот же провайдер**
  (например, два Telegram-аккаунта на одного `users.id`).

---

### 2.5. ER-диаграмма подсистемы

Логические связи:

* `users.id` ← `external_accounts.user_id`
* `auth_provider.code` ← `external_accounts.provider_code`

Графически (схематично):

```text
Users (users)
------------
id            PK, bigint
login         unique, varchar(64)
password_hash varchar(255)
created_at    timestamptz
is_active     boolean

AuthProvider (auth_provider)
---------------------------
code          PK, varchar(32)
name          varchar(64)

ExternalAccount (external_accounts)
-----------------------------------
id            PK, bigint
user_id       FK -> users.id
provider_code FK -> auth_provider.code
external_id   varchar(128)
created_at    timestamptz

Связи:
ExternalAccount.user_id       -> Users.id
ExternalAccount.provider_code -> AuthProvider.code
```

Эта схема потом была проверена и визуализирована через ERD-инструмент pgAdmin.

---

## 3. Настройка роли PostgreSQL для сервиса

Чтобы ограничить права доступа к БД со стороны приложения, создан отдельный логин PostgreSQL.

### 3.1. Создание роли

```sql
CREATE ROLE lsp_gateway_app
  LOGIN
  PASSWORD '***тут_пароль***';
```

Основные настройки роли:

* `Can login?` — включено (роль может подключаться к БД);
* `Superuser?`, `Create roles?`, `Create databases?` — отключены;
* роль не имеет административных привилегий и используется только приложением.

### 3.2. Выдача прав на базу и схему

```sql
-- Разрешаем подключаться к базе lsp_gateway
GRANT CONNECT ON DATABASE lsp_gateway TO lsp_gateway_app;

-- Переходим в базу
\c lsp_gateway

-- Разрешаем пользоваться схемой public
GRANT USAGE ON SCHEMA public TO lsp_gateway_app;

-- Права на уже существующие таблицы
GRANT SELECT, INSERT, UPDATE, DELETE
ON ALL TABLES IN SCHEMA public
TO lsp_gateway_app;

-- Права по умолчанию на будущие таблицы этой схемы
ALTER DEFAULT PRIVILEGES IN SCHEMA public
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES
TO lsp_gateway_app;

-- Права на все существующие sequence в схеме public
GRANT USAGE, SELECT
ON ALL SEQUENCES IN SCHEMA public
TO lsp_gateway_app;

-- Права по умолчанию на sequence, которые будут создаваться в будущем
ALTER DEFAULT PRIVILEGES IN SCHEMA public
GRANT USAGE, SELECT ON SEQUENCES
TO lsp_gateway_app;
```

В результате сервис `api-gateway-service` работает **под отдельным пользователем** БД
с ограниченным набором прав, что соответствует базовым требованиям безопасности.

---

## 4. Интеграция `api-gateway-service` с БД

### 4.1. Maven-зависимости

Модуль: `services/api-gateway-service`

В `pom.xml` подключены следующие зависимости:

```xml
<dependencies>
    <!-- REST API -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <!-- JPA / Hibernate -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>

    <!-- Драйвер PostgreSQL -->
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>

    <!-- Только крипта из Spring Security (для BCrypt), без веб-безопасности -->
    <dependency>
        <groupId>org.springframework.security</groupId>
        <artifactId>spring-security-crypto</artifactId>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-maven-plugin</artifactId>
        </plugin>
    </plugins>
</build>
```

`spring-boot-starter-security` **не** подключается на этом шаге: тестируется только регистрация, без авторизации HTTP-запросов.

---

### 4.2. Конфигурация `application.yml`

Файл: `services/api-gateway-service/src/main/resources/application.yml`

```yaml
spring:
  application:
    name: api-gateway-service

  datasource:
    url: jdbc:postgresql://localhost:5432/lsp_gateway
    username: lsp_gateway_app
    password: ${SPRING_DATASOURCE_PASSWORD}
    driver-class-name: org.postgresql.Driver

  jpa:
    hibernate:
      ddl-auto: validate          # схема создаётся вручную, Hibernate её только проверяет
    show-sql: true
    properties:
      hibernate.format_sql: true

server:
  port: 8080
```

Ключевые моменты:

* Пароль не храним в гите: задавайте `SPRING_DATASOURCE_PASSWORD` (и при необходимости `SPRING_DATASOURCE_URL/USERNAME`) через переменные окружения/профиль. Пример: `SPRING_DATASOURCE_PASSWORD=secret mvn spring-boot:run -pl services/api-gateway-service`.
* Профили: встроенной БД нет, нужен запущенный Postgres с созданной схемой/ролями. При использовании отдельных конфигов (`application-local.yml`) включайте профиль: `-Dspring-boot.run.profiles=local`.
* `ddl-auto: validate` - Hibernate **не создаёт** и не изменяет таблицы, а проверяет,
  что сущности соответствуют уже существующей схеме.
  Для создания/изменения схемы используйте миграции (Flyway/Liquibase) или ручной SQL.
* Логи SQL (`show-sql`) включены для удобной отладки.

---

## 5. Модель предметной области и JPA-сущности

Пакет домена:
`com.logicsignalprotector.apigateway.auth.domain`

### 5.1. `UserEntity` ↔ таблица `users`

Основные поля:

* `id` — `@Id`, `@GeneratedValue(strategy = GenerationType.IDENTITY)`
* `login` — `@Column(unique = true, nullable = false)`
* `passwordHash` — строка с BCrypt-хэшем
* `createdAt` — устанавливается в `@PrePersist` через `Instant.now()`
* `active` — флаг активности

Пример фрагмента:

```java
@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "login", nullable = false, unique = true, length = 64)
    private String login;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    // ...
}
```

### 5.2. `AuthProviderEntity` ↔ таблица `auth_provider`

Сущность для справочника провайдеров:

* `code` — `@Id` (PK, совпадает с `auth_provider.code`);
* `name` — человекочитаемое имя.

```java
@Entity
@Table(name = "auth_provider")
public class AuthProviderEntity {

    @Id
    @Column(name = "code", length = 32)
    private String code;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    // ...
}
```

### 5.3. `ExternalAccountEntity` ↔ таблица `external_accounts`

Отражает привязку пользователя к внешней учётке (например, Telegram):

* `id` — PK;
* `user` — `@ManyToOne` на `UserEntity` по колонке `user_id`;
* `providerCode` — строковый код (`TELEGRAM`, `WEB` и т.п.);
* `externalId` — идентификатор аккаунта/чата во внешней системе;
* `createdAt` — выставляется в `@PrePersist`.

```java
@Entity
@Table(name = "external_accounts")
public class ExternalAccountEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Column(name = "provider_code", nullable = false, length = 32)
    private String providerCode;

    @Column(name = "external_id", nullable = false, length = 128)
    private String externalId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    // ...
}
```

---

### 5.4. Стратегия `equals` / `hashCode` для сущностей

Для корректной работы JPA-сущностей в коллекциях и кэше используется **“Hibernate-friendly”** стратегия:

* Для `UserEntity` и `ExternalAccountEntity`:

  * сущности считаются равными, если:

    * у них **ненулевой** `id`,
    * `id` совпадают,
    * и совпадают фактические классы через `Hibernate.getClass(this)`.
  * Новые (ещё не сохранённые) сущности (`id == null`) считаются **не равными** друг другу,
    даже если их содержимое совпадает.

* Для `AuthProviderEntity`:

  * сущности считаются равными по полю `code` (так как это стабильный бизнес-ключ).

Такой подход:

* предотвращает проблемы при сравнении JPA-прокси;
* делает сущности пригодными для использования в `HashSet`/`HashMap`;
* не привязывает равенство к изменяемым полям (например, `login`).

---

## 6. Слой доступа к данным (репозитории)

Пакет:
`com.logicsignalprotector.apigateway.auth.repository`

### 6.1. `UserRepository`

```java
public interface UserRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByLogin(String login);

    boolean existsByLogin(String login);
}
```

Используется для:

* проверки занятости логина;
* загрузки пользователя по логину (в будущем — при логине).

### 6.2. `AuthProviderRepository`

```java
public interface AuthProviderRepository
        extends JpaRepository<AuthProviderEntity, String> {
}
```

Используется для чтения справочника провайдеров (при необходимости, например, для валидации `provider_code`).

### 6.3. `ExternalAccountRepository`

```java
public interface ExternalAccountRepository
        extends JpaRepository<ExternalAccountEntity, Long> {

    Optional<ExternalAccountEntity> findByProviderCodeAndExternalId(
            String providerCode, String externalId);

    List<ExternalAccountEntity> findByUserId(Long userId);
}
```

Планируется для:

* поиска пользователя по `(provider, external_id)` (в частности, по Telegram chatId);
* получения всех привязанных аккаунтов пользователя.

---

## 7. Конфигурация BCrypt

Пакет:
`com.logicsignalprotector.apigateway.auth.config`

Создан конфигурационный класс, который публикует бин `PasswordEncoder`:

```java
@Configuration
public class AuthConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        // BCrypt — стандартный и рекомендованный алгоритм для паролей
        return new BCryptPasswordEncoder();
    }
}
```

Этот бин используется сервисом регистрации для:

* хэширования пароля при создании пользователя;
* в будущем — для проверки пароля при логине (`matches`).

---

## 8. Сервис регистраций и REST-эндпоинт

### 8.1. `UserService` — бизнес-логика регистрации

Пакет:
`com.logicsignalprotector.apigateway.auth.service`

```java
@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserEntity register(String login, String rawPassword) {
        if (userRepository.existsByLogin(login)) {
            throw new IllegalArgumentException(
                    "Пользователь с таким логином уже существует");
        }

        String hash = passwordEncoder.encode(rawPassword);
        UserEntity user = new UserEntity(login, hash);
        return userRepository.save(user);
    }
}
```

Логика:

1. Проверка, что логин ещё не занят (`existsByLogin`).
2. Хэширование пароля через `BCryptPasswordEncoder`.
3. Создание и сохранение новой сущности пользователя.
4. Возвращение созданного пользователя.

Метод помечен `@Transactional` — регистрация выполняется как единая транзакция.

---

### 8.2. REST-контроллер `AuthController`

Пакет:
`com.logicsignalprotector.apigateway.auth.api`

```java
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    public record RegisterRequest(String login, String password) {}
    public record RegisterResponse(Long id, String login) {}

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest request) {
        try {
            UserEntity user =
                userService.register(request.login(), request.password());

            RegisterResponse response =
                new RegisterResponse(user.getId(), user.getLogin());

            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(e.getMessage());
        }
    }
}
```

#### Формат API

**URL:**
`POST /api/auth/register`

**Тело запроса:**

```json
{
  "login": "testuser",
  "password": "secret123"
}
```

**Ответ при успешной регистрации (`201 Created`):**

```json
{
  "id": 1,
  "login": "testuser"
}
```

**Ответ при ошибке (`400 Bad Request`):**

```json
"Пользователь с таким логином уже существует"
```

(В будущем формат ошибок можно унифицировать, например, оборачивать в JSON-объект.)

---

### 8.3. Ручная проверка работоспособности

1. Сборка модуля:

```bash
mvn clean compile -pl services/api-gateway-service
```

2. Запуск `api-gateway-service`:

```bash
mvn spring-boot:run -pl services/api-gateway-service
```

3. Запрос регистрации (через curl/Postman):

```bash
POST http://localhost:8080/api/auth/register
Content-Type: application/json

{
  "login": "testuser",
  "password": "secret123"
}
```

Ожидания:

* HTTP-код 201;
* JSON-ответ с `id` и `login`;
* в таблице `users` появляется запись с логином `testuser` и BCrypt-хэшем пароля (`$2a$...`).

Повторная регистрация с тем же `login` приводит к `400 Bad Request`.

---

## 9. Вопросы безопасности на данном этапе

На шаге 1.1 реализована базовая защита:

* пароли хранятся **только в виде BCrypt-хэшей**;
* отдельный пользователь БД `lsp_gateway_app` с ограниченными правами;
* Hibernate работает в режиме `ddl-auto: validate`, структура БД контролируется вручную (миграциями/SQL-скриптами).

Пока **не реализовано** (и это планируется на следующих шагах):

* политика сложности паролей (минимальная длина, набор символов);
* защита от частых попыток регистрации/подбора логина (rate limiting, captcha);
* полноценный Spring Security для HTTP (сессии/JWT, фильтры, авторизация по ролям);
* HTTPS/шифрование трафика на уровне внешнего веб-сервера/прокси.

---

## 10. Итог шага и дальнейшие планы

На шаге **1.1** выполнено:

1. Спроектирована и вручную создана БД `lsp_gateway` с тремя таблицами:

   * `users` — ядро идентичности пользователя;
   * `auth_provider` — справочник внешних провайдеров/каналов;
   * `external_accounts` — привязка внутренних пользователей к внешним аккаунтам (Telegram и др.) с нужными ограничениями.

2. Настроена отдельная роль PostgreSQL `lsp_gateway_app` с минимально необходимыми правами.

3. Модуль `api-gateway-service`:

   * подключён к БД через Spring Data JPA;
   * описаны JPA-сущности `UserEntity`, `AuthProviderEntity`, `ExternalAccountEntity`;
   * реализована “hibernate-friendly” стратегия `equals/hashCode` для сущностей;
   * создан сервис `UserService` с регистрацией пользователя и хэшированием пароля через BCrypt;
   * реализован REST-эндпоинт `POST /api/auth/register`, возвращающий ID и логин созданного пользователя.

4. Проводится ручное тестирование через HTTP-запросы; авто-тесты пока не написаны.

**Следующие шаги (план):**

* реализовать логин пользователя и выдачу токена/идентификатора сессии;
* реализовать привязку Telegram-аккаунта к существующему пользователю (`external_accounts`);
* добавить базовую валидацию входных данных (Bean Validation для DTO);
* постепенно вводить элементы полной безопасности (Spring Security, ограничение частоты запросов и т.п.).
