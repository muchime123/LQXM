-- 设备视频库治理闭环：内容状态收敛为受控六态
-- 待处理（刚上传/待剪辑）→ 待确认（已识别建议）→ 可用 → 精选；停用（退役）；异常（画质差等不可用）
UPDATE product_assets SET video_content_status = '待处理' WHERE video_content_status IS NULL OR video_content_status = '';
UPDATE product_assets SET video_content_status = '待处理' WHERE video_content_status = '待剪辑';
UPDATE product_assets SET video_content_status = '停用'   WHERE video_content_status = '已过时';
UPDATE product_assets SET video_content_status = '异常'   WHERE video_content_status = '画质较差';
-- 待确认 / 可用 语义不变，保持原值
