package com.liqiang.archive.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 建单时的制程解析：**制程跟随产线**（LQ 2026-09-23 定）。
 *
 * <p>解析顺序：该产线下**此产品名称**在《任务单产品与配置》里单独指定的制程 → 车间默认制程。
 * 前者如今恒为空（氚云三张源表里没有「制程 ↔ 产品名称」这个字段），因此实务上等于"用车间默认制程"。
 *
 * <p><b>无制程是合法状态，不是错误</b>（2026-09-23 放开）：氚云《产品零部件制程》里
 * 称重机车间与「无需报工」都标「是否有制程=否」，且氚云《生产任务单管理》5,691 张单里
 * 有 180 张是显式选了「无需报工」的（同一张表单里 `选择制程(必填)` 与 `产线选择(必填)` 并列）。
 * 因此这里**不再抛错**，改为返回 {@code templateId == null} 的 Selection，
 * 由建单方落一张「不展开工序、直接待收货」的工单（见 {@code ManufacturingExecutionService#markNoProcessOrder}）。
 *
 * <p>仍然抛错的只有两种**真配置事故**：车间不存在/停用、该产线下此产品名称的产品配置不唯一（0 条或多条）。
 */
@Service
@RequiredArgsConstructor
public class ManufacturingTaskTemplateResolver {
    private final JdbcTemplate jdbc;

    /** {@code templateId} 为 {@code null} = 该产线（及该产品）无制程 —— 免报工工单。 */
    public record Selection(Long templateId, String workshopName) {}

    public Selection resolve(long workshopId, String productName) {
        var workshops = jdbc.queryForList("SELECT workshop_name,default_process_template_id FROM manufacturing_workshops WHERE id=? AND enabled=1",workshopId);
        if(workshops.isEmpty()) throw new IllegalArgumentException("车间不存在或已停用");
        var products = jdbc.queryForList("SELECT process_template_id FROM manufacturing_task_config_items WHERE workshop_id=? AND config_name=? AND config_type='PRODUCT_NAME' AND active=1",workshopId,productName);
        if(products.size()!=1) throw new IllegalArgumentException("请选择该车间唯一有效的产品配置");
        Object productTemplate = products.getFirst().get("process_template_id");
        Object selected = productTemplate != null ? productTemplate : workshops.getFirst().get("default_process_template_id");
        String workshopName=(String)workshops.getFirst().get("workshop_name");
        if(selected == null) return new Selection(null,workshopName);
        long templateId = ((Number)selected).longValue();
        if(jdbc.queryForObject("SELECT COUNT(*) FROM manufacturing_process_templates WHERE id=? AND active=1",Integer.class,templateId)==0)
            throw new IllegalArgumentException("选定制程已停用，请维护产品或车间制程配置");
        return new Selection(templateId,workshopName);
    }
}
