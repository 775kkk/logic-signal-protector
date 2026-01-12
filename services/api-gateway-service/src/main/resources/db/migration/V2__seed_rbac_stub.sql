-- V2__seed_rbac_stub.sql
-- Step 1.3: RBAC stub permissions and role-permission mappings.
-- Note: permissions are stubs (placeholders) until business APIs in downstream services are implemented.

/* ============================================================
   1) Permissions dictionary (6 stubs)
   ============================================================ */
INSERT INTO permissions(code, name) VALUES
    ('MARKETDATA_READ', 'Read market data (stub)'),
    ('ALERTS_READ',     'Read alerts (stub)'),
    ('BROKER_READ',     'Read broker state (stub)'),
    ('BROKER_TRADE',    'Execute trade operations (stub)'),
    ('AUDIT_READ',      'Read audit events (stub)'),
    ('RBAC_MANAGE',     'Manage roles/permissions (stub)')
ON CONFLICT (code) DO NOTHING;

/* ============================================================
   2) Role -> Permissions mappings
      USER  -> 3 permissions
      ADMIN -> all 6 permissions
   ============================================================ */

-- USER: MARKETDATA_READ, ALERTS_READ, BROKER_READ
INSERT INTO role_permissions(role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('MARKETDATA_READ', 'ALERTS_READ', 'BROKER_READ')
WHERE r.code = 'USER'
ON CONFLICT DO NOTHING;

-- ADMIN: all 6
INSERT INTO role_permissions(role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'MARKETDATA_READ',
    'ALERTS_READ',
    'BROKER_READ',
    'BROKER_TRADE',
    'AUDIT_READ',
    'RBAC_MANAGE'
)
WHERE r.code = 'ADMIN'
ON CONFLICT DO NOTHING;
