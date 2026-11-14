-- Manual migration; run only after backup and schema verification.
-- Adds the verified address returned with the already-unique Google subject.
ALTER TABLE users ADD COLUMN google_email VARCHAR(255) NULL;
