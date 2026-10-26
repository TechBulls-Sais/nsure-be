-- Dev password for every seeded account below is: password
-- Hash matches the seeded admin user.

INSERT INTO app_user (username, password, enabled, office_id, customer_ref)
SELECT 'onboarding', '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG', TRUE,
       (SELECT id FROM app_office WHERE name = 'Head Office'), NULL
WHERE NOT EXISTS (SELECT 1 FROM app_user WHERE username = 'onboarding');

INSERT INTO app_user (username, password, enabled, office_id, customer_ref)
SELECT 'configurator', '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG', TRUE,
       (SELECT id FROM app_office WHERE name = 'Head Office'), NULL
WHERE NOT EXISTS (SELECT 1 FROM app_user WHERE username = 'configurator');

INSERT INTO app_user (username, password, enabled, office_id, customer_ref)
SELECT 'actuary', '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG', TRUE,
       (SELECT id FROM app_office WHERE name = 'Head Office'), NULL
WHERE NOT EXISTS (SELECT 1 FROM app_user WHERE username = 'actuary');

INSERT INTO app_user (username, password, enabled, office_id, customer_ref)
SELECT 'claims', '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG', TRUE,
       (SELECT id FROM app_office WHERE name = 'Head Office'), NULL
WHERE NOT EXISTS (SELECT 1 FROM app_user WHERE username = 'claims');

INSERT INTO app_user (username, password, enabled, office_id, customer_ref)
SELECT 'support', '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG', TRUE,
       (SELECT id FROM app_office WHERE name = 'Head Office'), NULL
WHERE NOT EXISTS (SELECT 1 FROM app_user WHERE username = 'support');

INSERT INTO app_user (username, password, enabled, office_id, customer_ref)
SELECT 'compliance', '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG', TRUE,
       (SELECT id FROM app_office WHERE name = 'Head Office'), NULL
WHERE NOT EXISTS (SELECT 1 FROM app_user WHERE username = 'compliance');

INSERT INTO app_user (username, password, enabled, office_id, customer_ref)
SELECT 'amara', '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG', TRUE,
       (SELECT id FROM app_office WHERE name = 'Head Office'), 'CUS-1001'
WHERE NOT EXISTS (SELECT 1 FROM app_user WHERE username = 'amara');

INSERT INTO app_user (username, password, enabled, office_id, customer_ref)
SELECT 'thabo', '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG', TRUE,
       (SELECT id FROM app_office WHERE name = 'Head Office'), 'CUS-1002'
WHERE NOT EXISTS (SELECT 1 FROM app_user WHERE username = 'thabo');

INSERT INTO app_user (username, password, enabled, office_id, customer_ref)
SELECT 'naledi', '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG', TRUE,
       (SELECT id FROM app_office WHERE name = 'Head Office'), 'CUS-1003'
WHERE NOT EXISTS (SELECT 1 FROM app_user WHERE username = 'naledi');

INSERT INTO app_user_role (user_id, role_id)
SELECT u.id, r.id FROM app_user u
JOIN app_role r ON r.name = 'Onboarding Specialist'
WHERE u.username = 'onboarding'
  AND NOT EXISTS (SELECT 1 FROM app_user_role ur WHERE ur.user_id = u.id AND ur.role_id = r.id);

INSERT INTO app_user_role (user_id, role_id)
SELECT u.id, r.id FROM app_user u
JOIN app_role r ON r.name = 'Product Configurator'
WHERE u.username = 'configurator'
  AND NOT EXISTS (SELECT 1 FROM app_user_role ur WHERE ur.user_id = u.id AND ur.role_id = r.id);

INSERT INTO app_user_role (user_id, role_id)
SELECT u.id, r.id FROM app_user u
JOIN app_role r ON r.name = 'Actuary'
WHERE u.username = 'actuary'
  AND NOT EXISTS (SELECT 1 FROM app_user_role ur WHERE ur.user_id = u.id AND ur.role_id = r.id);

INSERT INTO app_user_role (user_id, role_id)
SELECT u.id, r.id FROM app_user u
JOIN app_role r ON r.name = 'Claims Assessor'
WHERE u.username = 'claims'
  AND NOT EXISTS (SELECT 1 FROM app_user_role ur WHERE ur.user_id = u.id AND ur.role_id = r.id);

INSERT INTO app_user_role (user_id, role_id)
SELECT u.id, r.id FROM app_user u
JOIN app_role r ON r.name = 'Customer Support Representative'
WHERE u.username = 'support'
  AND NOT EXISTS (SELECT 1 FROM app_user_role ur WHERE ur.user_id = u.id AND ur.role_id = r.id);

INSERT INTO app_user_role (user_id, role_id)
SELECT u.id, r.id FROM app_user u
JOIN app_role r ON r.name = 'Compliance & Auditor'
WHERE u.username = 'compliance'
  AND NOT EXISTS (SELECT 1 FROM app_user_role ur WHERE ur.user_id = u.id AND ur.role_id = r.id);

INSERT INTO app_user_role (user_id, role_id)
SELECT u.id, r.id FROM app_user u
JOIN app_role r ON r.name = 'Policyholder / End User'
WHERE u.username IN ('amara', 'thabo', 'naledi')
  AND NOT EXISTS (SELECT 1 FROM app_user_role ur WHERE ur.user_id = u.id AND ur.role_id = r.id);
