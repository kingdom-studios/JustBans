-- Tracks which expired punishments have already been announced with a PUNISHMENT_EXPIRED event, so the expiry
-- sweeper emits exactly one event per punishment. NULL means "not announced (yet)".
ALTER TABLE jb_punishments
    ADD COLUMN expiry_event_at TIMESTAMP(3) NULL AFTER revoke_reason,
    ADD INDEX idx_expiry_sweep (expiry_event_at, expires_at);

-- Everything that already expired before this migration is history, not news: mark it announced so the first
-- sweep does not flood Redis with events for old punishments.
UPDATE jb_punishments
SET expiry_event_at = expires_at
WHERE expires_at IS NOT NULL AND expires_at <= CURRENT_TIMESTAMP(3);
