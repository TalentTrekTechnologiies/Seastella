-- ============================================================================
--  identity-access: a person's WhatsApp number for alerts
--
--  Asked for by the client in October 2026, beyond SoW s7 (in-app and email).
--  A number is used only once its owner has agreed to WhatsApp alerts: Meta
--  requires that opt-in, and the time it was given is kept as its record.
--  Each person sets or removes their own number; nobody sets it for them.
-- ============================================================================

ALTER TABLE app_user ADD COLUMN whatsapp_number VARCHAR(20);
ALTER TABLE app_user ADD COLUMN whatsapp_opt_in_at TIMESTAMP WITH TIME ZONE;
