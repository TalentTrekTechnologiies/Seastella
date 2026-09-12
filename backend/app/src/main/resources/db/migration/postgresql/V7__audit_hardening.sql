-- =====================================================================
--  AUD-03: make the audit trail tamper-resistant at the database level.
--
--  The application-level guarantee (AuditEntry has no setters, and the
--  repository exposes no mutating method) is necessary but not sufficient:
--  any future service could issue native SQL. This is the part that makes
--  the guarantee real.
--
--  PostgreSQL only. On H2 (local development) the application-level
--  protection still applies and AuditImmutabilityTest asserts it; this
--  migration is what runs in every deployed environment.
-- =====================================================================

CREATE OR REPLACE FUNCTION seastella_audit_is_append_only()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_entry is append-only: % is not permitted', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_entry_no_update
    BEFORE UPDATE ON audit_entry
    FOR EACH ROW EXECUTE FUNCTION seastella_audit_is_append_only();

CREATE TRIGGER trg_audit_entry_no_delete
    BEFORE DELETE ON audit_entry
    FOR EACH ROW EXECUTE FUNCTION seastella_audit_is_append_only();

-- Belt and braces: revoke the privileges outright from the application role.
-- SEASTELLA_APP_ROLE is substituted by Flyway from the deployment
-- configuration (flyway.placeholders.appRole).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${appRole}') THEN
        EXECUTE 'REVOKE UPDATE, DELETE, TRUNCATE ON audit_entry FROM ${appRole}';
        EXECUTE 'GRANT INSERT, SELECT ON audit_entry TO ${appRole}';
    END IF;
END
$$;
