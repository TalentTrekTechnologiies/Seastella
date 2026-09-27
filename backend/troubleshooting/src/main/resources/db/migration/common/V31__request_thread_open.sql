-- =====================================================================
--  troubleshooting: the request thread is open to everyone on the request
--
--  SRS s21 asks for a communication thread on every service request, where
--  the people on it ask for and give information, with the history kept.
--  The thread is therefore no longer only the Captain's live chat with the
--  Coordinator: it is OPEN for the whole of the request's life, LIVE while a
--  live agent is engaged, and CLOSED only when the request itself is finished.
-- =====================================================================

ALTER TABLE conversation DROP CONSTRAINT ck_conversation_status;
ALTER TABLE conversation ADD CONSTRAINT ck_conversation_status
    CHECK (status IN ('ASSISTANT', 'OPEN', 'LIVE', 'CLOSED'));

-- Threads the old rule closed when a live chat ended, on requests that are
-- still in progress, open again.
UPDATE conversation
   SET status = 'OPEN', closed_at = NULL
 WHERE status = 'CLOSED'
   AND service_request_id IN (
       SELECT id FROM service_request
        WHERE status NOT IN ('REJECTED', 'CLOSED_NO_COST', 'COMPLETED'));

-- The chat list finds a person's threads by vessel.
CREATE INDEX ix_conversation_vessel ON conversation (vessel_id);
