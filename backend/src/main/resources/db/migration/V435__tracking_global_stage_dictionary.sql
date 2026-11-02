-- Stable, workshop-independent vocabulary for tracking nodes. Existing
-- per-kind rows remain as default-flow and compatibility bindings.
CREATE TABLE production_tracking_stage_dictionary (
  stage_code VARCHAR(30) NOT NULL PRIMARY KEY,
  stage_label VARCHAR(50) NOT NULL,
  optional TINYINT(1) NOT NULL DEFAULT 0,
  match_keywords VARCHAR(200) NULL,
  exclude_keywords VARCHAR(200) NULL,
  header_aliases VARCHAR(200) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Prefer the established sealer wording when the same code has two labels
-- (for example MOLD). The per-kind labels and all task snapshots stay intact.
INSERT INTO production_tracking_stage_dictionary
  (stage_code,stage_label,optional,match_keywords,exclude_keywords,header_aliases)
SELECT d.stage_code,
  SUBSTRING_INDEX(GROUP_CONCAT(d.stage_label ORDER BY (d.workshop_kind='SEALER') DESC SEPARATOR '|||'),'|||',1),
  MAX(d.optional),MAX(d.match_keywords),MAX(d.exclude_keywords),MAX(d.header_aliases)
FROM production_tracking_stage_defs d
GROUP BY d.stage_code;
