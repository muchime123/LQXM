package com.liqiang.archive.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liqiang.archive.erp.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static com.liqiang.archive.service.ProductConfigurationNaming.*;

@Service
public class ProductConfigurationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ErpActorResolver actors;
    private final ErpChangeLogWriter audit;
    public ProductConfigurationService(JdbcTemplate jdbc,ObjectMapper mapper,ErpActorResolver actors,
                                       @Qualifier("archiveAuditWriter") ErpChangeLogWriter audit) {
        this.jdbc=jdbc;this.mapper=mapper;this.actors=actors;this.audit=audit;
    }
    public record Save(int revision,Definition definition) {}
    public record Preview(long baseProductId,Long ruleId,Map<String,Object> configuration) {}

    public Map<String,Object> directory(long workshopId) {
        var products=jdbc.queryForList("SELECT c.id,c.config_code code,c.config_name name,c.product_type productType,"
                +"(SELECT r.id FROM manufacturing_product_config_rules r WHERE r.base_product_id=c.id AND r.status='PUBLISHED' LIMIT 1) ruleId"
                +" FROM manufacturing_task_config_items c JOIN manufacturing_workshops w ON w.id=c.workshop_id"
                +" WHERE c.workshop_id=? AND c.config_type='PRODUCT_NAME' AND c.active=1 AND w.enabled=1 ORDER BY c.sort_order,c.id",workshopId);
        var options=jdbc.queryForList("SELECT id,config_code code,config_name name,option_group_code groupCode"
                +" FROM manufacturing_task_config_items WHERE workshop_id=? AND config_type='OPTIONAL_CONFIG' AND active=1 ORDER BY sort_order,id",workshopId);
        return Map.of("products",products,"options",options);
    }
    public List<Map<String,Object>> history(long productId) {
        product(productId,false);
        return jdbc.queryForList("SELECT id,base_product_id baseProductId,version_no version,status,draft_revision revision,"
                +"CAST(rules_json AS CHAR CHARACTER SET utf8mb4) definitionJson,published_at publishedAt"
                +" FROM manufacturing_product_config_rules WHERE base_product_id=? ORDER BY version_no DESC",productId);
    }
    public Map<String,Object> rule(long productId,Long ruleId,boolean draftAllowed) {
        product(productId,false);
        var rows=ruleId==null?jdbc.queryForList("SELECT * FROM manufacturing_product_config_rules WHERE base_product_id=? AND status='PUBLISHED'",productId)
                :jdbc.queryForList("SELECT * FROM manufacturing_product_config_rules WHERE id=? AND base_product_id=?",ruleId,productId);
        if(rows.size()!=1)throw new IllegalArgumentException("产品规则不存在或尚未发布");
        var row=rows.getFirst();
        if(!draftAllowed&&"DRAFT".equals(row.get("status")))throw new IllegalArgumentException("草稿规则不能用于业务配置");
        var labels="DRAFT".equals(row.get("status"))?labels(productId,decode(row.get("rules_json"),Definition.class)):
                decode(row.get("definition_snapshot_json"),Labels.class);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("id",row.get("id"));result.put("baseProductId",productId);result.put("version",row.get("version_no"));
        result.put("status",row.get("status"));result.put("revision",row.get("draft_revision"));
        result.put("definition",decode(row.get("rules_json"),Definition.class));result.put("labels",labels);
        return result;
    }
    @Transactional
    public Map<String,Object> createDraft(long productId) {
        var p=product(productId,true);
        var drafts=jdbc.queryForList("SELECT id FROM manufacturing_product_config_rules WHERE base_product_id=? AND status='DRAFT'",productId);
        if(!drafts.isEmpty())return rule(productId,((Number)drafts.getFirst().get("id")).longValue(),true);
        String type=Objects.toString(p.get("product_type"),"MACHINE");
        Definition definition;
        var published=jdbc.queryForList("SELECT rules_json FROM manufacturing_product_config_rules WHERE base_product_id=? AND status='PUBLISHED'",productId);
        if(!published.isEmpty())definition=decode(published.getFirst().get("rules_json"),Definition.class);
        else {
            var options=jdbc.queryForList("SELECT id FROM manufacturing_task_config_items WHERE workshop_id=? AND config_type='OPTIONAL_CONFIG' AND active=1",p.get("workshop_id"));
            var fields="MACHINE".equals(type)?MACHINE_REQUIRED:"MOLD".equals(type)?List.of("compatibleModel","moldType","boxShape","moldArrangement","drawingVersion"):List.of("specificName","partSpecification");
            definition=new Definition(type,fields,options.stream().map(o->new Option(((Number)o.get("id")).longValue(),"UNCLASSIFIED",false,"")).toList(),true);
        }
        Integer version=jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0)+1 FROM manufacturing_product_config_rules WHERE base_product_id=?",Integer.class,productId);
        jdbc.update("INSERT INTO manufacturing_product_config_rules(base_product_id,version_no,rules_json,created_by) VALUES(?,?,?,?)",productId,version,json(definition),actors.current().employeeId());
        long id=jdbc.queryForObject("SELECT id FROM manufacturing_product_config_rules WHERE base_product_id=? AND version_no=?",Long.class,productId,version);
        log(id,"创建产品配置规则草稿");return rule(productId,id,true);
    }
    @Transactional
    public void save(long productId,long ruleId,Save request) {
        product(productId,true);validate(productId,request.definition());
        int changed=jdbc.update("UPDATE manufacturing_product_config_rules SET rules_json=?,draft_revision=draft_revision+1 WHERE id=? AND base_product_id=? AND status='DRAFT' AND draft_revision=?",json(request.definition()),ruleId,productId,request.revision());
        if(changed!=1)throw new IllegalStateException("规则已被修改或发布，请重新加载后再保存");
        log(ruleId,"修改产品配置规则草稿");
    }
    @Transactional
    public void publish(long productId,long ruleId,int revision) {
        var p=product(productId,true);
        if(!Boolean.TRUE.equals(p.get("active"))&&!(p.get("active") instanceof Number n&&n.intValue()==1))throw new IllegalStateException("停用产品不能发布规则");
        var r=rule(productId,ruleId,true);
        if(!"DRAFT".equals(r.get("status"))||((Number)r.get("revision")).intValue()!=revision)throw new IllegalStateException("草稿版本已变化，请重新加载");
        Definition d=(Definition)r.get("definition");validate(productId,d);
        jdbc.update("UPDATE manufacturing_product_config_rules SET status='RETIRED' WHERE base_product_id=? AND status='PUBLISHED'",productId);
        jdbc.update("UPDATE manufacturing_product_config_rules SET status='PUBLISHED',definition_snapshot_json=?,published_at=NOW(),published_by=? WHERE id=?",json(labels(productId,d)),actors.current().employeeId(),ruleId);
        jdbc.update("UPDATE manufacturing_task_config_items SET product_type=? WHERE id=?",d.productType(),productId);
        log(ruleId,"发布产品配置规则");
    }
    public Map<String,Object> preview(Preview request,boolean draftAllowed) {
        if(request==null || request.configuration()==null)throw new IllegalArgumentException("请提供产品配置");
        if(request.configuration()==null||request.configuration().size()>50)throw new IllegalArgumentException("配置参数不正确");
        var r=rule(request.baseProductId(),request.ruleId(),draftAllowed);
        Result result=generate((Definition)r.get("definition"),(Labels)r.get("labels"),request.configuration());
        var output=new LinkedHashMap<String,Object>();
        output.put("baseProductId",request.baseProductId());output.put("configRuleId",r.get("id"));output.put("configRuleVersion",r.get("version"));
        output.put("productType",((Definition)r.get("definition")).productType());output.put("namingRuleVersion",1);
        output.put("namePreview",result.namePreview());output.put("specificationPreview",result.specificationPreview());
        output.put("completionState",result.completionState());output.put("fieldErrors",result.fieldErrors());output.put("confirmationItems",result.confirmationItems());
        output.put("normalizedConfiguration",result.normalizedConfiguration());return output;
    }
    /** Recompute on writes, ignoring client-authored previews and snapshots. Legacy products without rules are unchanged. */
    public Map<String,Object> snapshot(Long workshopId,String productName,Map<String,Object> config) {
        if(config==null)return null;
        Long id=config.get("baseProductId") instanceof Number n?n.longValue():null;
        if(id==null) {
            var rows=jdbc.queryForList("SELECT c.id FROM manufacturing_task_config_items c WHERE c.workshop_id=? AND c.config_name=? AND c.config_type='PRODUCT_NAME' AND EXISTS(SELECT 1 FROM manufacturing_product_config_rules r WHERE r.base_product_id=c.id AND r.status='PUBLISHED')",workshopId,productName);
            if(rows.isEmpty())return config;
            if(rows.size()!=1)throw new IllegalArgumentException("基础产品不唯一，请重新选择");
            id=((Number)rows.getFirst().get("id")).longValue();
        }
        var p=product(id,false);
        if(!Objects.equals(((Number)p.get("workshop_id")).longValue(),workshopId))throw new IllegalArgumentException("基础产品不属于所选产线");
        if(!Objects.equals(String.valueOf(p.get("config_name")),productName))throw new IllegalArgumentException("产品名称与基础产品不一致，请重新选择产品");
        Long ruleId=config.get("configRuleId") instanceof Number n?n.longValue():null;
        var r=preview(new Preview(id,ruleId,config),false);
        if(!((Map<?,?>)r.get("fieldErrors")).isEmpty())throw new IllegalArgumentException("产品配置未完整："+r.get("fieldErrors"));
        @SuppressWarnings("unchecked") var normalized=new LinkedHashMap<>((Map<String,Object>)r.get("normalizedConfiguration"));
        normalized.put("productName",productName);
        normalized.put("workshopId",workshopId);normalized.put("baseProductId",id);normalized.put("configRuleId",r.get("configRuleId"));
        normalized.put("configRuleVersion",r.get("configRuleVersion"));normalized.put("namingRuleVersion",1);normalized.put("productType",r.get("productType"));
        normalized.put("nameSnapshot",r.get("namePreview"));normalized.put("specificationSnapshot",r.get("specificationPreview"));normalized.put("confirmationItems",r.get("confirmationItems"));
        return normalized;
    }
    private Map<String,Object> product(long id,boolean lock) {
        var rows=jdbc.queryForList("SELECT * FROM manufacturing_task_config_items WHERE id=? AND config_type='PRODUCT_NAME'"+(lock?" FOR UPDATE":""),id);
        if(rows.isEmpty())throw new IllegalArgumentException("基础产品不存在");return rows.getFirst();
    }
    private void validate(long id,Definition d) {
        if(d==null||d.productType()==null||!Set.of("MACHINE","MOLD","PART").contains(d.productType())||d.requiredFields()==null||d.requiredFields().stream().anyMatch(Objects::isNull)||!FIELDS.containsAll(d.requiredFields())||d.options()==null||d.options().stream().anyMatch(Objects::isNull)||d.options().size()>100)throw new IllegalArgumentException("规则字段或产品类型不正确");
        if(new HashSet<>(d.requiredFields()).size()!=d.requiredFields().size())throw new IllegalArgumentException("必填字段重复");
        var mandatory="MACHINE".equals(d.productType())?List.of("boxShape","moldArrangement"):"MOLD".equals(d.productType())?List.of("compatibleModel","moldType","boxShape","moldArrangement","drawingVersion"):List.of("specificName","partSpecification");
        if(!d.requiredFields().containsAll(mandatory))throw new IllegalArgumentException("缺少此产品类型的必要字段");
        Set<String> typeFields="MACHINE".equals(d.productType())?Set.copyOf(MACHINE_REQUIRED):"MOLD".equals(d.productType())?Set.of("compatibleModel","moldType","boxShape","moldArrangement","drawingVersion","boxHeight"):Set.of("specificName","partSpecification","compatibleModel");
        if(!typeFields.containsAll(d.requiredFields()))throw new IllegalArgumentException("所选必填字段不适用于此产品类型");
        if(!"MACHINE".equals(d.productType()) && d.requiredFields().stream().anyMatch(f->Set.of("voltage","plug","brand","boxMaterial").contains(f)))throw new IllegalArgumentException("模具和配件不使用机器电气与盒材质必填字段");
        if("PART".equals(d.productType()) && d.requiredFields().stream().anyMatch(f->Set.of("boxShape","boxHeight","moldArrangement","drawingVersion").contains(f)))throw new IllegalArgumentException("配件不使用模具尺寸必填字段");
        var ids=new HashSet<Long>();
        for(Option o:d.options()) {
            if(!ids.add(o.optionId())||o.groupCode()==null||!Set.of("PROCESS","FEEDING","FILM","FILLING","CODING","STRUCTURE","ACCESSORY","PACKAGING","UNCLASSIFIED").contains(o.groupCode())||o.exclusiveGroup()!=null&&o.exclusiveGroup().length()>50)throw new IllegalArgumentException("配置项分组或互斥组不正确");
            if("PACKAGING".equals(o.groupCode())&&o.technical())throw new IllegalArgumentException("包装不能作为机器名称技术选配");
        }
        labels(id,d);
    }
    private Labels labels(long id,Definition d) {
        var p=product(id,false);var names=new LinkedHashMap<String,String>();var codes=new LinkedHashMap<String,String>();
        for(Option o:d.options()) {
            var options=jdbc.queryForList("SELECT config_code,config_name FROM manufacturing_task_config_items WHERE id=? AND workshop_id=? AND config_type='OPTIONAL_CONFIG' AND active=1",o.optionId(),p.get("workshop_id"));
            if(options.size()!=1)throw new IllegalArgumentException("规则引用了停用或其他产线的配置项");
            names.put(o.optionId()+"",String.valueOf(options.getFirst().get("config_name")));codes.put(o.optionId()+"",String.valueOf(options.getFirst().get("config_code")));
        }
        return new Labels(String.valueOf(p.get("config_name")),String.valueOf(p.get("config_code")),names,codes);
    }
    private <T>T decode(Object value,Class<T> type){try{return mapper.readValue(value instanceof byte[] b?new String(b,java.nio.charset.StandardCharsets.UTF_8):String.valueOf(value),type);}catch(Exception e){throw new IllegalStateException("规则数据无法读取",e);}}
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("规则无法保存",e);}}
    private void log(long id,String reason){audit.write(ErpChangeLogWriter.of(actors.current(),"product_archive.manufacturing_product_config_rules",id,"UPDATE",Map.of("action",reason),null,reason,null,null));}
}
