ALTER TABLE certificates ADD COLUMN deleted_at TIMESTAMP NULL;
CREATE INDEX idx_cert_deleted_at ON certificates (deleted_at);
