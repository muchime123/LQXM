package com.liqiang.archive.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ManufacturingTaskTemplateResolver {
    private final JdbcTemplate jdbc;
    public record Selection(long templateId, String workshopName) {}

    public Selection resolve(long workshopId, String productName) {
        var workshops = jdbc.queryForList("SELECT workshop_name,default_process_template_id FROM manufacturing_workshops WHERE id=? AND enabled=1",workshopId);
        if(workshops.isEmpty()) throw new IllegalArgumentException("车间不存在或已停用");
        var products = jdbc.queryForList("SELECT process_template_id FROM manufacturing_task_config_items WHERE workshop_id=? AND config_name=? AND config_type='PRODUCT_NAME' AND active=1",workshopId,productName);
        if(products.size()!=1) throw new IllegalArgumentException("请选择该车间唯一有效的产品配置");
        Object productTemplate = products.getFirst().get("process_template_id");
        Object selected = productTemplate != null ? productTemplate : workshops.getFirst().get("default_process_template_id");
        if(selected == null) throw new IllegalArgumentException("产品未关联制程，且车间未设置默认制程");
        long templateId = ((Number)selected).longValue();
        if(jdbc.queryForObject("SELECT COUNT(*) FROM manufacturing_process_templates WHERE id=? AND active=1",Integer.class,templateId)==0)
            throw new IllegalArgumentException("选定制程已停用，请维护产品或车间制程配置");
        return new Selection(templateId,(String)workshops.getFirst().get("workshop_name"));
    }
}
