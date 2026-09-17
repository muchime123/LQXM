-- Explicitly maintained defaults; do not infer a routing from workshop names.
ALTER TABLE manufacturing_workshops
 ADD COLUMN default_process_template_id BIGINT NULL,
 ADD CONSTRAINT fk_workshop_default_template FOREIGN KEY(default_process_template_id)
 REFERENCES manufacturing_process_templates(id) ON DELETE SET NULL;
