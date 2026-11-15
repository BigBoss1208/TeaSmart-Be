-- Manual additive migration; test first. No automatic production execution.
ALTER TABLE seller_applications
 ADD COLUMN business_type VARCHAR(40) NULL,
 ADD COLUMN representative_email VARCHAR(254) NULL,
 ADD COLUMN province VARCHAR(100) NULL,
 ADD COLUMN ward VARCHAR(100) NULL,
 ADD COLUMN address_detail VARCHAR(250) NULL,
 ADD COLUMN application_logo_url VARCHAR(500) NULL,
 ADD COLUMN cover_url VARCHAR(500) NULL;
ALTER TABLE shops
 ADD COLUMN business_type VARCHAR(40) NULL,
 ADD COLUMN representative_email VARCHAR(254) NULL,
 ADD COLUMN province VARCHAR(100) NULL,
 ADD COLUMN ward VARCHAR(100) NULL,
 ADD COLUMN address_detail VARCHAR(250) NULL,
 ADD COLUMN application_logo_url VARCHAR(500) NULL,
 ADD COLUMN cover_url VARCHAR(500) NULL;
