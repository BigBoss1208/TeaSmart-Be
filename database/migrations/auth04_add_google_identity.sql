-- MANUAL ONLY. Back up/verify recovery and pause writers before application.
-- Never executed by Spring Boot. Select teasmart or a uniquely named test database.
-- No account DML. MySQL DDL implicitly commits: on error STOP, inspect metadata
-- and the helper procedure; do not use --force or automatically retry/rollback.
DELIMITER $$
CREATE PROCEDURE auth04_add_google_identity()
BEGIN
    IF DATABASE() IS NULL OR NOT (DATABASE() = 'teasmart'
        OR REGEXP_LIKE(DATABASE(), '^teasmart_[a-z0-9_]*test_[a-f0-9]{32}$', 'c')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'AUTH04: unexpected database; stopped.';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE()
        AND table_name='users' AND table_type='BASE TABLE' AND engine='InnoDB') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'AUTH04: users must be an InnoDB table.';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
        AND table_name='users' AND column_name='password_hash' AND column_type='varchar(255)'
        AND collation_name='utf8mb4_unicode_ci' AND column_default IS NULL AND extra='') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'AUTH04: password_hash metadata mismatch.';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
        AND table_name='users' AND column_name='email' AND column_type='varchar(255)' AND is_nullable='NO')
        OR NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
        AND table_name='users' AND column_name='phone' AND column_type='varchar(20)' AND is_nullable='YES') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'AUTH04: email/phone metadata mismatch.';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
        AND table_name='users' AND index_name='PRIMARY' AND non_unique=0
        GROUP BY index_name HAVING COUNT(*)=1 AND MAX(column_name)='user_id')
        OR NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
        AND table_name='users' AND index_name='uk_users_email' AND non_unique=0 AND sub_part IS NULL
        GROUP BY index_name HAVING COUNT(*)=1 AND MAX(column_name)='email') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'AUTH04: users identity indexes mismatch.';
    END IF;
    -- Inspect ALL preconditions before the first ALTER to avoid predictable partial DDL.
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
        AND table_name='users' AND column_name='google_sub') THEN
        IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
            AND table_name='users' AND column_name='google_sub' AND column_type='varchar(255)'
            AND is_nullable='YES' AND collation_name='utf8mb4_bin' AND column_default IS NULL AND extra='') THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'AUTH04: google_sub metadata mismatch.';
        END IF;
        IF EXISTS (SELECT 1 FROM users WHERE google_sub IS NOT NULL GROUP BY google_sub HAVING COUNT(*)>1) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'AUTH04: duplicate google_sub; stopped.';
        END IF;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
        AND table_name='users' AND index_name='uk_users_google_sub')
        AND NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
            AND table_name='users' AND index_name='uk_users_google_sub'
            GROUP BY index_name HAVING COUNT(*)=1 AND MAX(column_name)='google_sub'
                AND MAX(non_unique)=0 AND SUM(sub_part IS NOT NULL)=0 AND MAX(index_type)='BTREE'
                AND MIN(is_visible)='YES') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'AUTH04: google_sub index mismatch.';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
        AND table_name='users' AND column_name='google_sub') THEN
        ALTER TABLE users ADD COLUMN google_sub VARCHAR(255)
            CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
        AND table_name='users' AND index_name='uk_users_google_sub') THEN
        ALTER TABLE users ADD CONSTRAINT uk_users_google_sub UNIQUE (google_sub);
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
        AND table_name='users' AND column_name='password_hash' AND is_nullable='NO') THEN
        ALTER TABLE users MODIFY COLUMN password_hash VARCHAR(255)
            CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
        AND table_name='users' AND column_name='password_hash' AND is_nullable='YES')
        OR NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
        AND table_name='users' AND column_name='google_sub' AND column_type='varchar(255)'
        AND is_nullable='YES' AND collation_name='utf8mb4_bin')
        OR NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
        AND table_name='users' AND index_name='uk_users_google_sub' AND non_unique=0 AND sub_part IS NULL
        GROUP BY index_name HAVING COUNT(*)=1 AND MAX(column_name)='google_sub') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'AUTH04: postcondition failed; inspect schema.';
    END IF;
END$$
CALL auth04_add_google_identity()$$
DROP PROCEDURE auth04_add_google_identity$$
DELIMITER ;
