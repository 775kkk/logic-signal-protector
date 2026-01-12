# Шаг 0. Бутстрап проекта `Logic Signal Protector`

> Источники: фактические файлы `project-summary.md`, `step-1.1-auth.md`, структура репо; допущение про минимум три сервиса взято из переписки/плана — точные имена модулей смотрите в своём `pom.xml`.

## 0. Идея шага

На шаге 0 не трогаем бизнес-логику и БД. Задача — подготовить **скелет проекта**, на который потом можно навешивать сервисы:

- выбрать версию Java и Spring Boot;
- завести корневой Maven-проект;
- разнести код по нескольким сервисам (микросервисный каркас);
- убедиться, что каждый сервис собирается и стартует как отдельное Spring Boot-приложение.

Результат: репозиторий `logic-signal-protector` с корневым `pom.xml` и несколькими подпроектами-сервисами, один из которых — `services/api-gateway-service`.

---

## 1. Структура репозитория

### 1.1. Корень проекта

Maven-агрегатор:

- каталог: `logic-signal-protector/`
- файл: `pom.xml`
- packaging: `pom`
- общая группа/артефакт, например:

  ```xml
  <groupId>com.logicsignalprotector</groupId>
  <artifactId>logic-signal-protector</artifactId>
  <version>0.0.1-SNAPSHOT</version>
  <packaging>pom</packaging>
  ```

В `pom.xml` задаются:

* версия Java (21) и Spring Boot 3.x;
* общий `dependencyManagement` для стартеров Spring;
* список модулей `<modules>` — включён как минимум `services/api-gateway-service` (ещё 2 модуля-заготовки — сверить по фактическому `pom.xml`).

Итог: единое место версий зависимостей и одна точка сборки `mvn clean install` на корне.

### 1.2. Каталог `services/`

Структура:

```text
services/
  api-gateway-service/
  ... ещё 2 сервиса (заготовки под будущие микросервисы)
```

На шаге 0 это **пустые Spring Boot-приложения** с минимальным кодом и своим `pom.xml`.

---

## 2. Общие настройки Maven / Spring Boot

### 2.1. Версия Java и Spring Boot

В корневом `pom.xml`:

```xml
<properties>
    <java.version>21</java.version>
    <spring.boot.version>3.2.x</spring.boot.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-dependencies</artifactId>
            <version>${spring.boot.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<build>
    <pluginManagement>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </pluginManagement>
</build>
```

У всех модулей: единая версия Spring Boot, Java 21, сборка/запуск через `mvn spring-boot:run -pl <module>`.

---

## 3. Сервис 1: `api-gateway-service`

> На шаге 1.1 сюда добавится аутентификация. Пока — только каркас.

### 3.1. Назначение

Входная точка для HTTP-клиентов:

* поднимает REST-API;
* дальше — регистрация/логин, проксирование к другим сервисам;
* позже знает про БД аутентификации `lsp_gateway` и отдаёт `userId`.

### 3.2. Структура модуля

```text
services/api-gateway-service/
  pom.xml
  src/
    main/
      java/
        com/logicsignalprotector/apigateway/ApiGatewayServiceApplication.java
      resources/
        application.yml (минимальный)
```

### 3.3. `pom.xml` модуля (минимум)

```xml
<dependencies>
    <!-- REST API -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
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

JPA/БД/безопасности пока нет — только запуск веб-приложения.

### 3.4. Точка входа

```java
@SpringBootApplication
public class ApiGatewayServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayServiceApplication.class, args);
    }
}
```

### 3.5. Минимальная проверка

Простой контроллер-пинг:

```java
@RestController
public class PingController {
    @GetMapping("/ping")
    public String ping() {
        return "OK";
    }
}
```

Проверка:

```bash
mvn clean compile -pl services/api-gateway-service
mvn spring-boot:run -pl services/api-gateway-service
# GET http://localhost:8080/ping -> "OK"
```

---

## 4. Сервисы 2 и 3 (заготовки)

> Имена зависят от реального `pom.xml`. Нужны как пустые каркасы для микросервисной структуры.

Концепция:

* **Сервис 2** — будущая бизнес-логика (мониторинг/правила/ядро).
* **Сервис 3** — интеграции/уведомления (например, Telegram-бот, e-mail).

Для каждого:

1. `<module>services/<имя-сервиса></module>` в корневом `pom.xml`.
2. Свой `pom.xml`, наследующий родителя, с зависимостями минимум:

   ```xml
   <dependency>
       <groupId>org.springframework.boot</groupId>
       <artifactId>spring-boot-starter</artifactId>
   </dependency>
   ```

   (добавить `spring-boot-starter-web`, если нужен HTTP).
3. Класс `@SpringBootApplication` в своём пакете.
4. Минимальный `application.yml` (имя, порт).

На шаге 0: без сущностей/JPA и без БД; задача — чтобы каждый сервис собирался и стартовал.

---

## 5. Архитектурные договорённости (шаг 0)

* Проект развивается как набор сервисов под одним Maven-агрегатором; каждый можно будет вынести отдельно.
* Взаимодействие между сервисами — HTTP/REST (позже возможно брокер сообщений).
* UML на уровне логической схемы: вход `api-gateway`, сервис(ы) ядра, сервис(ы) интеграций.

---

## 6. Итог шага 0

1. Создан корневой Maven-агрегатор `logic-signal-protector` (packaging `pom`), Java 21, Spring Boot 3.x.
2. Выделен `services/`, заведены минимум три модуля, один — `api-gateway-service`.
3. У каждого сервиса: свой `pom.xml`, класс `@SpringBootApplication`, сборка/запуск через `mvn spring-boot:run -pl ...`.
4. В `api-gateway-service` есть минимальный REST `/ping` для проверки.
5. Дальше: в `api-gateway-service` — аутентификация и внешний REST-API; в остальных — бизнес-логика и интеграции.

Этот шаг дал «скелет космолёта»; на шаге 1.1 добавлены БД аутентификации, `UserEntity`/`ExternalAccountEntity` и REST-регистрация пользователей.
