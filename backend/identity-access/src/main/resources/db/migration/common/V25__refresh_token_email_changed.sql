-- =====================================================================
--  identity-access: a sign-in address that changes ends the sessions
--
--  Correcting someone's email (IAM-08) changes how they sign in, so every
--  session on the old address is revoked. The reason is recorded like any
--  other, which means the check constraint has to know about it.
-- =====================================================================

ALTER TABLE refresh_token DROP CONSTRAINT ck_refresh_token_reason;

ALTER TABLE refresh_token ADD CONSTRAINT ck_refresh_token_reason CHECK (
    revoked_reason IS NULL OR revoked_reason IN (
        'ROTATED', 'SIGNED_OUT', 'REUSE_DETECTED', 'ACCOUNT_SUSPENDED', 'PASSWORD_RESET', 'EMAIL_CHANGED'));
