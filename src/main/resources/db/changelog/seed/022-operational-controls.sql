INSERT INTO operational_control (control_key, name, description, control_type, enabled, numeric_value, fail_mode, min_value, max_value, requires_reason) VALUES
('claim.four_eyes', 'Claim four-eyes', 'The person who assessed a claim cannot approve its payout.', 'TOGGLE', TRUE, NULL, 'CLOSED', NULL, NULL, TRUE),
('claim.stp_enabled', 'Straight-through processing', 'Master switch for automatic claim approval.', 'TOGGLE', TRUE, NULL, 'CLOSED', NULL, NULL, TRUE),
('claim.stp_ceiling', 'STP ceiling', 'Maximum payout that can be approved automatically.', 'THRESHOLD', TRUE, 5000.00, 'CLOSED', 0, NULL, TRUE),
('claim.stp_requires_verified_payout', 'STP verified payout', 'Automatic approval requires a verified payout account.', 'TOGGLE', TRUE, NULL, 'CLOSED', NULL, NULL, TRUE),
('rate.four_eyes', 'Rate four-eyes', 'The author of a rate version cannot publish it.', 'TOGGLE', TRUE, NULL, 'CLOSED', NULL, NULL, TRUE),
('rate.notice_days', 'Re-rate notice', 'Minimum days of notice before a re-rate takes effect.', 'DURATION', TRUE, 90, 'CLOSED', 0, NULL, TRUE),
('policy.payment_window_hours', 'Payment window', 'Hours a pending payment may stay open.', 'DURATION', TRUE, 24, 'CLOSED', 1, NULL, TRUE),
('declaration.otp_max_attempts', 'Declaration OTP attempts', 'Failed OTP attempts before payment lockout.', 'THRESHOLD', TRUE, 3, 'CLOSED', 1, NULL, TRUE),
('declaration.otp_lock_minutes', 'Declaration OTP lock', 'Minutes a payer stays locked out.', 'DURATION', TRUE, 30, 'CLOSED', 1, NULL, TRUE),
('payment.settlement_tamper_check', 'Settlement tamper check', 'Re-check amount and currency before a policy is activated.', 'TOGGLE', TRUE, NULL, 'CLOSED', NULL, NULL, TRUE);
