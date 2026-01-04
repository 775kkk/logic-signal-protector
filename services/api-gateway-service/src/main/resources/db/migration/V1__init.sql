-- V1__init.sql
-- Initial schema for api-gateway-service
-- DB: PostgreSQL
-- Purpose: users + external identities (providers) + RBAC (roles/permissions) + refresh tokens + auth audit

/* ============================================================
   1) users
   ============================================================ */
CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    login         VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,

    CONSTRAINT uq_users_login UNIQUE (login)
);

/* ============================================================
   2) external identities (providers + external accounts)
   ============================================================ */
CREATE TABLE auth_provider (
    code VARCHAR(32) PRIMARY KEY,
    name VARCHAR(64) NOT NULL
);

INSERT INTO auth_provider(code, name) VALUES
    ('WEB',      'Web'),
    ('TELEGRAM', 'Telegram bot')
ON CONFLICT (code) DO NOTHING;

CREATE TABLE external_accounts (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    provider_code VARCHAR(32)  NOT NULL,
    external_id   VARCHAR(128) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_external_accounts_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,

    CONSTRAINT fk_external_accounts_provider
        FOREIGN KEY (provider_code) REFERENCES auth_provider(code) ON DELETE RESTRICT,

    CONSTRAINT uq_external_accounts_provider_external
        UNIQUE (provider_code, external_id),

    CONSTRAINT uq_external_accounts_user_provider
        UNIQUE (user_id, provider_code)
);

CREATE INDEX ix_external_accounts_user_id ON external_accounts(user_id);

/* ============================================================
   3) RBAC: roles + permissions + mappings + user overrides
   Model:
     user_roles -> roles assigned to user
     role_permissions -> permissions assigned to role
     user_permission_overrides -> per-user allow/deny (DENY wins)
   ============================================================ */

-- 3.1) Roles
CREATE TABLE roles (
    id   BIGSERIAL PRIMARY KEY,
    code VARCHAR(32) NOT NULL UNIQUE,   -- e.g. 'USER', 'ADMIN'
    name VARCHAR(64) NOT NULL
);

-- 3.2) User -> Roles (many-to-many)
CREATE TABLE user_roles (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,

    CONSTRAINT fk_user_roles_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,

    CONSTRAINT fk_user_roles_role
        FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE RESTRICT,

    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, role_id)
);

CREATE INDEX ix_user_roles_user ON user_roles(user_id);
CREATE INDEX ix_user_roles_role ON user_roles(role_id);

-- Seed base roles
INSERT INTO roles(code, name) VALUES
    ('USER',  'User'),
    ('ADMIN', 'Administrator')
ON CONFLICT (code) DO NOTHING;

-- 3.3) Permissions dictionary (fill as features appear)
CREATE TABLE permissions (
    id   BIGSERIAL PRIMARY KEY,
    code VARCHAR(64)  NOT NULL UNIQUE,   -- e.g. 'PORTFOLIO_READ', 'MOEX_QUOTES_READ'
    name VARCHAR(128) NOT NULL
);

-- 3.4) Role -> Permissions mapping
CREATE TABLE role_permissions (
    role_id       BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    granted_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_role_permissions_role
        FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE,

    CONSTRAINT fk_role_permissions_permission
        FOREIGN KEY (permission_id) REFERENCES permissions(id) ON DELETE RESTRICT,

    CONSTRAINT pk_role_permissions PRIMARY KEY (role_id, permission_id)
);

CREATE INDEX ix_role_permissions_permission_id ON role_permissions(permission_id);

-- 3.5) User overrides: ALLOW / DENY
-- is_allowed = TRUE  -> ALLOW
-- is_allowed = FALSE -> DENY (DENY wins)
CREATE TABLE user_permission_overrides (
    user_id       BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    is_allowed    BOOLEAN NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at    TIMESTAMPTZ NULL,
    reason        VARCHAR(256) NULL,

    CONSTRAINT fk_user_perm_over_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,

    CONSTRAINT fk_user_perm_over_perm
        FOREIGN KEY (permission_id) REFERENCES permissions(id) ON DELETE RESTRICT,

    CONSTRAINT pk_user_perm_over PRIMARY KEY (user_id, permission_id)
);

CREATE INDEX ix_user_perm_over_perm ON user_permission_overrides(permission_id);
CREATE INDEX ix_user_perm_over_user_active ON user_permission_overrides(user_id, expires_at);

/* ============================================================
   4) refresh tokens (store hash, not raw token)
   ============================================================ */
CREATE TABLE refresh_tokens (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT NOT NULL,

    -- Store HASH of refresh token (never store raw token)
    token_hash VARCHAR(255) NOT NULL UNIQUE,

    issued_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ NULL,

    device_id  VARCHAR(128) NULL,
    ip         VARCHAR(64)  NULL,
    user_agent VARCHAR(256) NULL,

    CONSTRAINT fk_refresh_tokens_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,

    CONSTRAINT ck_refresh_tokens_exp
        CHECK (expires_at > issued_at)
);

CREATE INDEX ix_refresh_tokens_user ON refresh_tokens(user_id);
CREATE INDEX ix_refresh_tokens_expires ON refresh_tokens(expires_at);
CREATE INDEX ix_refresh_tokens_active ON refresh_tokens(user_id, revoked_at, expires_at);

/* ============================================================
   5) auth events (audit / security events)
   ============================================================ */
CREATE TABLE auth_events (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT NULL,
    type         VARCHAR(32) NOT NULL,   -- REGISTER, LOGIN_SUCCESS, LOGIN_FAIL, TOKEN_REFRESH, LOGOUT, TELEGRAM_LINK, ...
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    ip           VARCHAR(64)  NULL,
    user_agent   VARCHAR(256) NULL,
    details_json JSONB        NULL,

    CONSTRAINT fk_auth_events_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
);

CREATE INDEX ix_auth_events_user_time ON auth_events(user_id, created_at DESC);
CREATE INDEX ix_auth_events_type_time ON auth_events(type, created_at DESC);
