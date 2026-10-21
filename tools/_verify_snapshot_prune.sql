DROP TABLE IF EXISTS h3sc_latest;
CREATE TABLE h3sc_latest (eid VARCHAR(100) COLLATE utf8mb4_0900_ai_ci NOT NULL PRIMARY KEY, latest_id BIGINT NOT NULL) ENGINE=InnoDB;
INSERT INTO h3sc_latest(eid, latest_id) SELECT r.external_id, MAX(r.id) FROM h3_import_rows r JOIN h3_import_batches b ON b.id=r.batch_id WHERE b.data_type IN ('PURCHASE_IN','PRODUCTION') AND r.external_id IS NOT NULL GROUP BY r.external_id;
DROP TABLE IF EXISTS h3sc_keep;
CREATE TABLE h3sc_keep (batch_id BIGINT NOT NULL PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO h3sc_keep(batch_id) SELECT id FROM (SELECT id, ROW_NUMBER() OVER (PARTITION BY data_type ORDER BY id DESC) rn FROM h3_import_batches WHERE data_type IN ('PURCHASE_IN','PRODUCTION') AND status IN ('COMPLETED','PARTIAL_FAILED')) t WHERE t.rn <= 3;
SELECT COUNT(*) AS targets FROM h3_import_rows r JOIN h3_import_batches b ON b.id=r.batch_id LEFT JOIN h3sc_latest m ON m.eid=r.external_id LEFT JOIN h3sc_keep kb ON kb.batch_id=r.batch_id WHERE b.data_type IN ('PURCHASE_IN','PRODUCTION') AND b.status IN ('COMPLETED','PARTIAL_FAILED') AND kb.batch_id IS NULL AND (m.latest_id IS NULL OR r.id < m.latest_id);
SELECT COUNT(*) AS scanned FROM h3_import_rows r JOIN h3_import_batches b ON b.id=r.batch_id WHERE b.data_type IN ('PURCHASE_IN','PRODUCTION') AND b.status IN ('COMPLETED','PARTIAL_FAILED');
