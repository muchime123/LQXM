-- V392: 修正证书审查员字段误填（2026-10-20）
-- 背景：certificates.examiner（审查员）从未真实填写，37 条专利记录里存的是
--       从官方审查信息页「代理机构」区块误复制的代理人姓名
--       （程春生/洪中清/江杰/朱琳/刘成文等 = 温州市品创所代理人；王红/谭丽莎/谢永/姜爱君/陈孝政 = 其他四家代理机构代理人）。
-- 处理：1) examiner 值挪到 agency_contact（代理联系人，原值为空）；2) 删除 examiner 列。
-- 备份：根目录 db_backup_examiner_20261020.tsv（37 行）。

UPDATE certificates
SET agency_contact = examiner
WHERE examiner IS NOT NULL
  AND examiner <> ''
  AND (agency_contact IS NULL OR agency_contact = '');

ALTER TABLE certificates DROP COLUMN examiner;
