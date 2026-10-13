INSERT INTO app_role (name, description, disabled) VALUES
('Policyholder / End User', 'Portal access is by ownership only. This role must never receive a capability.', FALSE),
('Product Configurator', 'Sets up catalogue parameters, cover tiers and rate drafts.', FALSE),
('Actuary', 'Defines rate tables and is the only role that can publish them.', FALSE),
('Onboarding Specialist', 'Reviews customer documents and KYC decisions.', FALSE),
('Claims Assessor', 'Assesses claims and authorises payouts within the approval rules.', FALSE),
('Customer Support Representative', 'Services policies, payments and claim intake.', FALSE),
('Compliance & Auditor', 'Reads audit history and exports the finance file.', FALSE);
INSERT INTO mipas_capability (code, capability_group, meaning) VALUES
('dashboard.read', 'Dashboard', 'View the admin dashboard, KPIs and recent activity'),
('claim.read', 'Claims', 'List and read claims'),
('claim.create', 'Claims', 'Lodge a claim on behalf of a claimant'),
('claim.assess', 'Claims', 'Assess a claim and record a recommendation'),
('claim.approve', 'Claims', 'Authorise a claim payout within an approval limit'),
('claim.reject', 'Claims', 'Reject a claim'),
('policy.read', 'Policies', 'List and read policies'),
('policy.create', 'Policies', 'Create a policy on a customer behalf'),
('policy.write', 'Policies', 'Edit an existing policy'),
('payment.read', 'Payments', 'List and read premium payments'),
('customer.read', 'Customers', 'List and read customer records'),
('customer.write', 'Customers', 'Edit customer records and verification status'),
('product.read', 'Product Catalogue', 'Read the product catalogue'),
('product.write', 'Product Catalogue', 'Create and edit catalogue entities'),
('product.activate', 'Product Catalogue', 'Activate or deactivate catalogue entities'),
('product.delete', 'Product Catalogue', 'Delete catalogue entities'),
('tier.write', 'Product Catalogue', 'Add, edit and remove cover tiers'),
('audit.read', 'Audit', 'Read the activation audit log'),
('finance.export', 'Finance', 'Trigger the finance export'),
('rate.read', 'Rating', 'View rate tables and versions'),
('rate.write', 'Rating', 'Create, edit and submit rate tables'),
('rate.publish', 'Rating', 'Approve and publish a rate version'),
('controls.read', 'Controls', 'View operational control state and history'),
('controls.write', 'Controls', 'Enable, disable or re-value an operational control'),
('claim.approve.override', 'Claims', 'Bypass claim four-eyes with a written reason'),
('rate.publish.self', 'Rating', 'Publish a rate version you authored');
INSERT INTO mipas_capability_grant (role_id, capability_id)
SELECT r.id, c.id FROM app_role r JOIN mipas_capability c ON c.code IN ('product.read','product.write','product.activate','product.delete','tier.write','rate.read','rate.write')
WHERE r.name = 'Product Configurator';
INSERT INTO mipas_capability_grant (role_id, capability_id)
SELECT r.id, c.id FROM app_role r JOIN mipas_capability c ON c.code IN ('dashboard.read','claim.read','policy.read','payment.read','product.read','product.write','rate.read','rate.write','rate.publish','controls.read')
WHERE r.name = 'Actuary';
INSERT INTO mipas_capability_grant (role_id, capability_id)
SELECT r.id, c.id FROM app_role r JOIN mipas_capability c ON c.code IN ('customer.read','customer.write')
WHERE r.name = 'Onboarding Specialist';
INSERT INTO mipas_capability_grant (role_id, capability_id)
SELECT r.id, c.id FROM app_role r JOIN mipas_capability c ON c.code IN ('dashboard.read','claim.read','claim.create','claim.assess','claim.approve','claim.reject','policy.read','payment.read','controls.read')
WHERE r.name = 'Claims Assessor';
INSERT INTO mipas_capability_grant (role_id, capability_id)
SELECT r.id, c.id FROM app_role r JOIN mipas_capability c ON c.code IN ('dashboard.read','claim.read','claim.create','policy.read','policy.create','policy.write','payment.read','customer.read')
WHERE r.name = 'Customer Support Representative';
INSERT INTO mipas_capability_grant (role_id, capability_id)
SELECT r.id, c.id FROM app_role r JOIN mipas_capability c ON c.code IN ('dashboard.read','claim.read','policy.read','payment.read','customer.read','audit.read','finance.export','rate.read','controls.read')
WHERE r.name = 'Compliance & Auditor';
