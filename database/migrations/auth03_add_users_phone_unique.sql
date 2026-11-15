-- MANUAL ONLY. Select the intended database explicitly before running this file.
-- Back up and verify recovery first; pause registration writers during application.
-- Does not modify existing account values. Invalid legacy phones are permitted.
-- Use mysql without --force: stop on the first error. No automatic Spring execution.
DELIMITER $$
CREATE PROCEDURE auth03_add_users_phone_unique()
BEGIN
    IF DATABASE() IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Select the intended database first.';
    END IF;
    IF EXISTS (SELECT 1 FROM users WHERE phone IS NOT NULL GROUP BY phone HAVING COUNT(*) > 1) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Duplicate phone values: migration stopped.';
    END IF;
    -- Valid legacy numbers must already use canonical form: never silently rewrite them.
    IF EXISTS (
        SELECT 1 FROM (
            SELECT phone, CASE
                WHEN REGEXP_REPLACE(phone, '[[:space:]]', '') LIKE '+84%'
                    THEN CONCAT('0', SUBSTRING(REGEXP_REPLACE(phone, '[[:space:]]', ''), 4))
                ELSE REGEXP_REPLACE(phone, '[[:space:]]', '') END AS canonical
            FROM users WHERE phone IS NOT NULL
        ) p WHERE REGEXP_LIKE(canonical, '^0[35789][0-9]{8}$')
            AND BINARY phone <> BINARY canonical
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Valid legacy phone needs normalization approval: migration stopped.';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'users'
            AND non_unique = 0 AND index_name = 'uk_users_phone'
        GROUP BY index_name HAVING COUNT(*) = 1 AND MAX(column_name) = 'phone'
    ) THEN
        ALTER TABLE users ADD CONSTRAINT uk_users_phone UNIQUE (phone);
    END IF;
END$$
CALL auth03_add_users_phone_unique()$$
DROP PROCEDURE auth03_add_users_phone_unique$$
DELIMITER ;
