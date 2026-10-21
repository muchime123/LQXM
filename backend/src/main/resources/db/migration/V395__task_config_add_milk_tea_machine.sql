-- Source: 任务单配置项_2026-10-20-16-21-44-394.xlsx, Sheet1（120 行，全部「已生效」）。
-- 与 V265（2026-09-04 导出，119 行）按「车间 + 类型 + 名称」逐项比对：119 项完全一致，
-- 无缺失、无多余、无停用差异；唯一缺口是「封口机车间 / 产品名称 / 全自动奶茶机」。
-- 口径与 V265 种子一致：external_source=H3YUN、external_id=氚云 ObjectId、车间名冗余存一份。
INSERT INTO manufacturing_task_config_items(external_source,external_id,config_name,workshop_name,workshop_id,config_type,duplicate_detection_key,source_owner_name,source_status,config_code,product_type)
SELECT 'H3YUN','0d2d41ca-408a-4db8-95d1-4982c3f6e238','全自动奶茶机','封口机车间',w.id,'PRODUCT_NAME','全自动奶茶机产品名称封口机车间','曹义山','已生效','BP-H3YUN-0d2d41ca','MACHINE'
FROM manufacturing_workshops w
WHERE w.workshop_name='封口机车间'
  AND NOT EXISTS (SELECT 1 FROM manufacturing_task_config_items WHERE external_source='H3YUN' AND external_id='0d2d41ca-408a-4db8-95d1-4982c3f6e238');

-- 编码口径对齐 V347：BP- + 8 位补零 id（config_code 建唯一键，必须与既有 119 行同形）。
UPDATE manufacturing_task_config_items
SET config_code=CONCAT('BP-',LPAD(id,8,'0'))
WHERE external_source='H3YUN'
  AND external_id='0d2d41ca-408a-4db8-95d1-4982c3f6e238'
  AND config_code LIKE 'BP-H3YUN-%';
