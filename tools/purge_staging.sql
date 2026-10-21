-- 存储过程：清理「已终态」批次的暂存行（与 H3StagingCleanupService 判据一致）
-- 参数：p_chunk 每批删除行数；p_dry_run 1=试算(不删) 0=真删
DROP PROCEDURE IF EXISTS sp_purge_staging_terminal;
DELIMITER $$
CREATE PROCEDURE sp_purge_staging_terminal(IN p_chunk INT, IN p_dry_run TINYINT)
BEGIN
    DECLARE v_id BIGINT;
    DECLARE v_done INT DEFAULT 0;
    DECLARE v_rows INT DEFAULT 0;
    DECLARE v_total INT DEFAULT 0;
    DECLARE v_batches INT DEFAULT 0;
    DECLARE cur CURSOR FOR
        SELECT b.id
          FROM h3_import_batches b
         WHERE b.status IN ('COMPLETED', 'PARTIAL_FAILED')
           AND b.preview_json IS NOT NULL AND b.preview_json <> ''
           AND EXISTS (SELECT 1 FROM h3_import_staging_rows s WHERE s.batch_id = b.id)
         ORDER BY b.id;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_done = 1;

    OPEN cur;
    read_loop: LOOP
        FETCH cur INTO v_id;
        IF v_done = 1 THEN LEAVE read_loop; END IF;
        SET v_batches = v_batches + 1;
        IF p_dry_run = 1 THEN
            SELECT COUNT(*) INTO v_rows FROM h3_import_staging_rows WHERE batch_id = v_id;
            SET v_total = v_total + v_rows;
        ELSE
            del_loop: LOOP
                DELETE FROM h3_import_staging_rows WHERE batch_id = v_id LIMIT p_chunk;
                SET v_rows = ROW_COUNT();
                SET v_total = v_total + v_rows;
                IF v_rows < p_chunk THEN LEAVE del_loop; END IF;
            END LOOP;
        END IF;
    END LOOP;
    CLOSE cur;

    SELECT v_batches AS batch_count, v_total AS row_count, p_dry_run AS dry_run;
END$$
DELIMITER ;

CALL sp_purge_staging_terminal(5000, 1);
