-- Preserve existing page visibility without expanding existing API capabilities.
INSERT INTO identity_role_permission(role_id,permission)
SELECT DISTINCT role_id, 'page:tenants' FROM identity_role_permission WHERE permission='tenant:read';
INSERT INTO identity_role_permission(role_id,permission)
SELECT DISTINCT role_id, 'page:users' FROM identity_role_permission WHERE permission='iam:user:read';
INSERT INTO identity_role_permission(role_id,permission)
SELECT DISTINCT role_id, 'page:roles' FROM identity_role_permission WHERE permission='iam:role:read';
INSERT INTO identity_role_permission(role_id,permission)
SELECT DISTINCT role_id, 'page:menus' FROM identity_role_permission WHERE permission='iam:role:read';
INSERT INTO identity_role_permission(role_id,permission)
SELECT DISTINCT role_id, 'page:categories' FROM identity_role_permission WHERE permission='product:read';
INSERT INTO identity_role_permission(role_id,permission)
SELECT DISTINCT role_id, 'page:products' FROM identity_role_permission WHERE permission='product:read';
INSERT INTO identity_role_permission(role_id,permission)
SELECT DISTINCT role_id, 'page:devices' FROM identity_role_permission WHERE permission='device:read';
