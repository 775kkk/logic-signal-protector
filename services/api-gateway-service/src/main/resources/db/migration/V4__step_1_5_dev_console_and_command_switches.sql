-- V4__step_1_5_dev_console_and_command_switches.sql
-- Step 1.5: dev console role/perms + command switches.

/* ============================================================
   1) DEVONLYADMIN role + perms
   ============================================================ */
INSERT INTO roles(code, name) VALUES
    ('DEVONLYADMIN', 'Dev-only admin (bootstrap via env)')
ON CONFLICT (code) DO NOTHING;

INSERT INTO permissions(code, name) VALUES
    ('DEVGOD', 'Dev super-permission: allows everything'),
    ('COMMANDS_TOGGLE', 'Enable/disable commands via switches'),
    ('USERS_HARD_DELETE', 'Hard delete users (dev-only)')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions(role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('DEVGOD', 'COMMANDS_TOGGLE', 'USERS_HARD_DELETE')
WHERE r.code = 'DEVONLYADMIN'
ON CONFLICT DO NOTHING;

/* ============================================================
   2) Command switches
   ============================================================ */
CREATE TABLE IF NOT EXISTS command_switches (
    command_code VARCHAR(64) PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by_user_id BIGINT NULL,
    note VARCHAR(256) NULL,

    CONSTRAINT fk_command_switches_updated_by
        FOREIGN KEY (updated_by_user_id) REFERENCES users(id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS ix_command_switches_enabled ON command_switches(enabled);
