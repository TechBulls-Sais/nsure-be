INSERT INTO premium_frequency (frequency_name, months, short_label, period_word, display_order) VALUES
('Monthly', 1, '/mo', 'month', 1),
('Quarterly', 3, '/qtr', 'quarter', 2),
('Semi-Annually', 6, '/6mo', 'half-year', 3),
('Annual', 12, '/yr', 'year', 4),
('One-time', 0, '(once)', 'payment', 5);
INSERT INTO relationship_type (relationship_name, display_order, is_self_relationship) VALUES
('Main Insured', 1, TRUE),
('Spouse', 2, FALSE),
('Child', 3, FALSE),
('Parents', 4, FALSE),
('Parents-In-Laws', 5, FALSE),
('Grand Parents', 6, FALSE),
('Sibling', 7, FALSE),
('Other', 8, FALSE);
INSERT INTO courtesy (name) VALUES ('Mr'), ('Mrs'), ('Ms'), ('Dr'), ('Prof'), ('Rev');
INSERT INTO proof_of_identity (name) VALUES ('Omang'), ('Passport');
INSERT INTO payout_method (name) VALUES ('Bank Account'), ('Mobile Wallet');
INSERT INTO gender (name) VALUES ('Male'), ('Female'), ('Other');
INSERT INTO relationship_screen_filter (screen, screen_label) VALUES
('life-assured', 'Life assured'),
('beneficiary', 'Beneficiary');
INSERT INTO relationship_screen_filter_item (filter_id, relationship_type_id, display_order)
SELECT f.id, r.id, 1 FROM relationship_screen_filter f JOIN relationship_type r ON r.relationship_name = 'Spouse' WHERE f.screen = 'life-assured';
INSERT INTO relationship_screen_filter_item (filter_id, relationship_type_id, display_order)
SELECT f.id, r.id, 2 FROM relationship_screen_filter f JOIN relationship_type r ON r.relationship_name = 'Child' WHERE f.screen = 'life-assured';
INSERT INTO relationship_screen_filter_item (filter_id, relationship_type_id, display_order)
SELECT f.id, r.id, 1 FROM relationship_screen_filter f JOIN relationship_type r ON r.relationship_name = 'Main Insured' WHERE f.screen = 'beneficiary';
