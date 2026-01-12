-- V3__step_1_4_rbac_admin_tools.sql
-- Step 1.4: admin tools permissions used by Telegram "console".

/* ============================================================
   1) New permissions
   ============================================================ */
INSERT INTO permissions(code, name) VALUES
    ('ADMIN_ANSWERS_LOG', 'Receive raw HTTP/debug outputs in chat'),
    ('ADMIN_USERS_PERMS_REVOKE', 'Manage users roles & permission overrides')
ON CONFLICT (code) DO NOTHING;

/* ============================================================
   2) Role -> Permissions mappings
      ADMIN -> both new permissions
   ============================================================ */
INSERT INTO role_permissions(role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('ADMIN_ANSWERS_LOG', 'ADMIN_USERS_PERMS_REVOKE')
WHERE r.code = 'ADMIN'
ON CONFLICT DO NOTHING;
