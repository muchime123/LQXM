package com.liqiang.archive.service;

import com.liqiang.archive.security.CurrentUserService;
import com.liqiang.archive.util.ProductSourceKey;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 销售出库确定性关联治理：只自动处理唯一精确命中，并持久化重导可复用规则。 */
@Service
@RequiredArgsConstructor
public class OutboundRelationGovernanceService {
    private final JdbcTemplate jdbc;
    private final CurrentUserService users;
    private final AuditLogService audit;
    private final SourceIdentityService sourceIdentityService;

    /**
     * 出库明细行的产品来源键表达式。已收敛到唯一口径来源 {@link ProductSourceKey#sqlExpr(String)}，
     * 与 Java 侧 {@link ProductSourceKey#of} 逐字对齐——历史上这里是一份手工复制的 SQL 副本，
     * 与 Java 实现在「全角空格编码」「首尾制表符/换行」上语义不同，会导致人工归类写进去、重导读不回来。
     * 三处口径一致性是「重导后人工结论可恢复」的前提，改动务必改 util，不要在此处写第二份副本。
     */
    private static final String PRODUCT_KEY_EXPR = ProductSourceKey.sqlExpr("i");

    public Map<String,Object> preview() {
        // 排序规则说明：本库历史表用 utf8mb4_unicode_ci、新表用 utf8mb4_0900_ai_ci（schema 默认与 MySQL 8+ 默认不一致），
        // 两列直接比较会抛 ERROR 1267 非法混合排序规则。统一 CONVERT 到 utf8mb4 默认排序规则再比，才与建表时间无关。
        List<Map<String,Object>> employees = jdbc.queryForList("""
            SELECT TRIM(o.salesperson_name) sourceName,MIN(e.id) targetId,MIN(e.name) targetName,
                   COUNT(DISTINCT o.id) outboundCount,COUNT(DISTINCT e.id) candidateCount
              FROM sales_outbounds o
              LEFT JOIN employees e ON e.status='ACTIVE'
                AND LOWER(CONVERT(TRIM(e.name) USING utf8mb4))=LOWER(CONVERT(TRIM(o.salesperson_name) USING utf8mb4))
             WHERE o.external_source='H3YUN' AND o.employee_id IS NULL
               AND NOT (o.outbound_status='DRAFT' OR UPPER(TRIM(COALESCE(o.status,''))) IN ('草稿','DRAFT'))
               AND o.salesperson_name IS NOT NULL AND TRIM(o.salesperson_name)<>''
             GROUP BY TRIM(o.salesperson_name)
            HAVING COUNT(DISTINCT e.id)=1
             ORDER BY outboundCount DESC
            """);
        List<Map<String,Object>> warehouses = jdbc.queryForList("""
            SELECT TRIM(o.warehouse) sourceName,MIN(w.id) targetId,MIN(w.warehouse_name) targetName,
                   COUNT(DISTINCT o.id) outboundCount,COUNT(DISTINCT w.id) candidateCount
              FROM sales_outbounds o
              LEFT JOIN warehouses w ON w.active=1
                AND LOWER(CONVERT(TRIM(w.warehouse_name) USING utf8mb4))=LOWER(CONVERT(TRIM(o.warehouse) USING utf8mb4))
             WHERE o.external_source='H3YUN' AND o.warehouse_id IS NULL
               AND NOT (o.outbound_status='DRAFT' OR UPPER(TRIM(COALESCE(o.status,''))) IN ('草稿','DRAFT'))
               AND o.warehouse IS NOT NULL AND TRIM(o.warehouse)<>''
             GROUP BY TRIM(o.warehouse)
            HAVING COUNT(DISTINCT w.id)=1
             ORDER BY outboundCount DESC
            """);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("employees", employees);
        result.put("warehouses", warehouses);
        result.put("employeeSourceCount", employees.size());
        result.put("employeeOutboundCount", sum(employees));
        result.put("warehouseSourceCount", warehouses.size());
        result.put("warehouseOutboundCount", sum(warehouses));
        return result;
    }

    @Transactional
    public Map<String,Object> applyExact() {
        return applyExact(users.current().name());
    }

    Map<String,Object> applyExact(String operator) {
        Map<String,Object> p = preview();
        int employeeRules=0, employeeOutbounds=0, warehouseRules=0, warehouseOutbounds=0;
        for (Map<String,Object> row : rows(p,"employees")) {
            String source=String.valueOf(row.get("sourceName")); long target=((Number)row.get("targetId")).longValue();
            jdbc.update("""
                INSERT INTO employee_external_mappings(employee_id,source_system,external_user_id,external_name,is_primary,mapped_by,remark)
                VALUES(?,'H3YUN',?,?,1,?,'销售出库业务员姓名唯一精确匹配')
                ON DUPLICATE KEY UPDATE employee_id=VALUES(employee_id),external_name=VALUES(external_name),mapped_by=VALUES(mapped_by),mapped_at=NOW(),remark=VALUES(remark)
                """,target,"NAME:"+source,source,operator);
            employeeRules++;
            employeeOutbounds += jdbc.update("""
                UPDATE sales_outbounds o SET o.employee_id=?
                 WHERE o.external_source='H3YUN' AND o.employee_id IS NULL AND TRIM(o.salesperson_name)=?
                   AND NOT (o.outbound_status='DRAFT' OR UPPER(TRIM(COALESCE(o.status,''))) IN ('草稿','DRAFT'))
                   AND NOT EXISTS(SELECT 1 FROM sales_outbound_commissions c WHERE c.sales_outbound_id=o.id AND c.status='SETTLED')
                """,target,source);
        }
        for (Map<String,Object> row : rows(p,"warehouses")) {
            String source=String.valueOf(row.get("sourceName")); long target=((Number)row.get("targetId")).longValue();
            jdbc.update("""
                INSERT INTO h3_warehouse_name_mappings(source_name,warehouse_id,match_method,confirmed_by)
                VALUES(?,?,'EXACT_NAME',?)
                ON DUPLICATE KEY UPDATE warehouse_id=VALUES(warehouse_id),match_method='EXACT_NAME',confirmed_by=VALUES(confirmed_by),confirmed_at=NOW()
                """,source,target,operator);
            warehouseRules++;
            warehouseOutbounds += jdbc.update("UPDATE sales_outbounds SET warehouse_id=? WHERE external_source='H3YUN' AND warehouse_id IS NULL AND TRIM(warehouse)=? AND NOT (outbound_status='DRAFT' OR UPPER(TRIM(COALESCE(status,''))) IN ('草稿','DRAFT'))",target,source);
        }
        audit.log("SalesOutbound",null,"确定性关联","H3_OUTBOUND_RELATION_AUTO",
                "员工规则 "+employeeRules+" 条/出库 "+employeeOutbounds+" 张；仓库规则 "+warehouseRules+" 条/出库 "+warehouseOutbounds+" 张");
        return Map.of("employeeRules",employeeRules,"employeeOutbounds",employeeOutbounds,
                "warehouseRules",warehouseRules,"warehouseOutbounds",warehouseOutbounds);
    }

    public Map<String,Object> customerPreview() {
        List<Map<String,Object>> candidates=customerCandidates();
        Map<String,Long> methods=new LinkedHashMap<>();
        candidates.forEach(row->methods.merge(text(row.get("method")),1L,Long::sum));
        return Map.of("outboundCount",candidates.size(),"byMethod",methods,
                "items",candidates.size()>500?candidates.subList(0,500):candidates);
    }

    private List<Map<String,Object>> customerCandidates(){
        Map<String,Long> clusterTargets=new HashMap<>();
        jdbc.queryForList("SELECT m.normalized_source_name,m.cluster_key,m.customer_id FROM customer_source_cluster_mappings m JOIN customers c ON c.id=m.customer_id AND c.deleted_at IS NULL WHERE m.source_system='H3YUN' AND m.status='CONFIRMED'")
                .forEach(row->clusterTargets.put(text(row.get("normalized_source_name"))+"|"+text(row.get("cluster_key")),number(row.get("customer_id"))));
        Map<String,Long> resolvedNames=new HashMap<>();
        jdbc.queryForList("SELECT a.normalized_name,a.target_id FROM source_identity_aliases a JOIN customers c ON c.id=a.target_id AND c.deleted_at IS NULL WHERE a.source_system='H3YUN' AND a.entity_type='CUSTOMER' AND a.resolution_status IN ('AUTO','CONFIRMED') AND a.target_id IS NOT NULL")
                .forEach(row->resolvedNames.putIfAbsent(text(row.get("normalized_name")),number(row.get("target_id"))));
        Map<String,Set<Long>> phoneTargets=new HashMap<>();
        jdbc.queryForList("""
            SELECT k.customer_id,COALESCE(k.mobile,'') mobile,COALESCE(k.phone_raw,'') phone FROM customer_contacts k JOIN customers c ON c.id=k.customer_id AND c.deleted_at IS NULL
            UNION ALL SELECT a.customer_id,COALESCE(a.contact_phone,''),' ' FROM customer_addresses a JOIN customers c ON c.id=a.customer_id AND c.deleted_at IS NULL
            """).forEach(row->{
                long customerId=number(row.get("customer_id"));
                sourceIdentityService.customerPhoneTokens(text(row.get("mobile"))+" "+text(row.get("phone")))
                        .forEach(phone->phoneTargets.computeIfAbsent(phone,k->new java.util.LinkedHashSet<>()).add(customerId));
            });
        Map<Long,String> targetNames=new HashMap<>();
        jdbc.queryForList("SELECT id,customer_name FROM customers WHERE deleted_at IS NULL")
                .forEach(row->targetNames.put(number(row.get("id")),text(row.get("customer_name"))));
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> row:jdbc.queryForList("""
            SELECT id,outbound_no,customer_name,customer_address,contact_phone FROM sales_outbounds
             WHERE external_source='H3YUN' AND (customer_id IS NULL OR NOT EXISTS(SELECT 1 FROM customers live WHERE live.id=sales_outbounds.customer_id AND live.deleted_at IS NULL))
               AND NOT (outbound_status='DRAFT' OR UPPER(TRIM(COALESCE(status,''))) IN ('草稿','DRAFT')) ORDER BY id
            """)){
            String name=text(row.get("customer_name")),address=text(row.get("customer_address")),phone=text(row.get("contact_phone"));
            Long target=clusterTargets.get(sourceIdentityService.customerIdentityKey(name,address,phone));
            String matchMethod=target==null?null:"CONFIRMED_CLUSTER",reason=target==null?null:"已确认电话/地址身份簇";
            Set<Long> phoneCandidates=new java.util.LinkedHashSet<>();
            outboundPhoneTokens(phone,address).forEach(token->phoneCandidates.addAll(phoneTargets.getOrDefault(token,Set.of())));
            if(target==null){
                if(phoneCandidates.size()==1){target=phoneCandidates.iterator().next();matchMethod="UNIQUE_PHONE";reason="电话唯一命中";}
            }
            if(target==null){
                String normalized=normalize(name); Long mapped=resolvedNames.get(normalized);
                List<String> evidencePhones=outboundPhoneTokens(phone,address);
                if(SourceIdentityService.canReuseNameDecision(mapped,evidencePhones,new ArrayList<>(phoneCandidates))){
                    target=mapped;matchMethod="RESOLVED_IDENTITY";reason="已有确认客户身份映射（无新电话冲突）";
                }
            }
            if(target==null||!targetNames.containsKey(target)) continue;
            Map<String,Object> item=outboundIdentityItem(row);
            item.put("targetId",target);item.put("method",matchMethod);item.put("reason",reason);item.put("targetName",targetNames.get(target));result.add(item);
        }
        return result;
    }

    @Transactional
    public Map<String,Object> applyCustomerSafe(){
        List<Map<String,Object>> candidates=customerCandidates(); int updated=0; Map<String,Long> methods=new LinkedHashMap<>();
        for(Map<String,Object> row:candidates){
            long id=number(row.get("id")),target=number(row.get("targetId"));
            updated+=jdbc.update("UPDATE sales_outbounds o SET o.customer_id=? WHERE o.id=? AND (o.customer_id IS NULL OR NOT EXISTS(SELECT 1 FROM customers c WHERE c.id=o.customer_id AND c.deleted_at IS NULL))",target,id);
            sourceIdentityService.rememberCustomerCluster(text(row.get("customerName")),text(row.get("customerAddress")),text(row.get("contactPhone")),target);
            methods.merge(text(row.get("method")),1L,Long::sum);
        }
        audit.log("SalesOutbound",null,"客户关联","H3_OUTBOUND_RELATION_AUTO","安全关联客户 "+updated+" 张；依据="+methods);
        return Map.of("customerOutbounds",updated,"byMethod",methods);
    }

    private record NewCustomerCluster(String phone,String customerName,List<Map<String,Object>> rows,
                                      java.util.Set<String> sourceNames,java.util.Set<String> addresses) {}

    public Map<String,Object> newCustomerClusterPreview(){
        List<NewCustomerCluster> clusters=newCustomerClusters();
        long outbounds=clusters.stream().mapToLong(c->c.rows().size()).sum();
        List<Map<String,Object>> items=clusters.stream().limit(500).map(c->Map.<String,Object>of(
                "phone",c.phone(),"customerName",c.customerName(),"outboundCount",c.rows().size(),
                "sourceNames",c.sourceNames(),"addressCount",c.addresses().size())).toList();
        return Map.of("clusterCount",clusters.size(),"outboundCount",outbounds,"items",items);
    }

    /** 只对恰好一个有效电话、且电话尚未属于任何客户档案的出库建档。 */
    private List<NewCustomerCluster> newCustomerClusters(){
        java.util.Set<String> existingPhones=new java.util.HashSet<>();
        jdbc.queryForList("""
            SELECT COALESCE(mobile,'') mobile,COALESCE(phone_raw,'') phone FROM customer_contacts
            UNION ALL SELECT COALESCE(contact_phone,''),' ' FROM customer_addresses
            """).forEach(row->existingPhones.addAll(sourceIdentityService.customerPhoneTokens(
                    text(row.get("mobile"))+" "+text(row.get("phone")))));
        Map<String,List<Map<String,Object>>> grouped=new LinkedHashMap<>();
        for(Map<String,Object> row:jdbc.queryForList("""
            SELECT id,outbound_no,customer_name,customer_address,contact_phone FROM sales_outbounds
             WHERE external_source='H3YUN' AND (customer_id IS NULL OR NOT EXISTS(SELECT 1 FROM customers live WHERE live.id=sales_outbounds.customer_id AND live.deleted_at IS NULL))
               AND NOT (outbound_status='DRAFT' OR UPPER(TRIM(COALESCE(status,''))) IN ('草稿','DRAFT')) ORDER BY id
            """)){
            List<String> phones=outboundPhoneTokens(text(row.get("contact_phone")),text(row.get("customer_address")));
            if(phones.size()!=1||existingPhones.contains(phones.getFirst())) continue;
            grouped.computeIfAbsent(phones.getFirst(),k->new ArrayList<>()).add(row);
        }
        Set<String> occupiedNames=new java.util.HashSet<>();
        jdbc.queryForList("SELECT customer_name FROM customers WHERE deleted_at IS NULL")
                .forEach(row->occupiedNames.add(normalize(text(row.get("customer_name")))));
        List<NewCustomerCluster> result=new ArrayList<>();
        for(var entry:grouped.entrySet()){
            Map<String,Long> nameCounts=new LinkedHashMap<>();
            java.util.Set<String> names=new java.util.LinkedHashSet<>(),addresses=new java.util.LinkedHashSet<>();
            for(var row:entry.getValue()){
                String rawName=text(row.get("customer_name")); names.add(rawName); nameCounts.merge(rawName,1L,Long::sum);
                String address=text(row.get("customer_address")); if(!address.isEmpty()) addresses.add(address);
            }
            String preferred=nameCounts.entrySet().stream().max(Map.Entry.<String,Long>comparingByValue()
                    .thenComparing(Map.Entry.comparingByKey())).map(Map.Entry::getKey).orElse("客户");
            String customerName=sourceIdentityService.suggestedCustomerName(preferred,entry.getKey());
            String normalizedName=normalize(customerName);
            if(occupiedNames.contains(normalizedName)) {
                String digits=entry.getKey().replaceAll("\\D","");
                String suffix=digits.length()>6?digits.substring(digits.length()-6):digits;
                customerName=customerName+"["+suffix+"]";
                normalizedName=normalize(customerName);
            }
            occupiedNames.add(normalizedName);
            result.add(new NewCustomerCluster(entry.getKey(),customerName,
                    entry.getValue(),names,addresses));
        }
        return result;
    }

    /** 联系电话列优先；仅当该列没有有效号码时，才从历史地址文本中提取。 */
    private List<String> outboundPhoneTokens(String contactPhone,String address){
        List<String> contacts=sourceIdentityService.customerPhoneTokens(contactPhone);
        return contacts.isEmpty()?sourceIdentityService.customerPhoneTokens(address):contacts;
    }

    /** 返回安全关联和安全建档都无法处理的真实例外，不把尚未点击执行的安全候选混进来。 */
    public Map<String,Object> customerExceptionPreview(){
        Set<Long> safeIds=new java.util.HashSet<>();
        customerCandidates().forEach(row->safeIds.add(number(row.get("id"))));
        newCustomerClusters().forEach(cluster->cluster.rows().forEach(row->safeIds.add(number(row.get("id")))));
        Map<String,Set<Long>> phoneTargets=new HashMap<>();
        jdbc.queryForList("""
            SELECT customer_id,COALESCE(mobile,'') mobile,COALESCE(phone_raw,'') phone FROM customer_contacts
            UNION ALL SELECT customer_id,COALESCE(contact_phone,''),' ' FROM customer_addresses
            """).forEach(row->{
            long customerId=number(row.get("customer_id"));
            sourceIdentityService.customerPhoneTokens(text(row.get("mobile"))+" "+text(row.get("phone")))
                    .forEach(phone->phoneTargets.computeIfAbsent(phone,k->new java.util.LinkedHashSet<>()).add(customerId));
        });
        Map<String,Long> counts=new LinkedHashMap<>();
        List<Map<String,Object>> items=new ArrayList<>();
        for(Map<String,Object> row:jdbc.queryForList("""
            SELECT id,outbound_no,customer_name,customer_address,contact_phone FROM sales_outbounds
             WHERE external_source='H3YUN' AND (customer_id IS NULL OR NOT EXISTS(SELECT 1 FROM customers live WHERE live.id=sales_outbounds.customer_id AND live.deleted_at IS NULL))
               AND NOT (outbound_status='DRAFT' OR UPPER(TRIM(COALESCE(status,''))) IN ('草稿','DRAFT')) ORDER BY id
            """)){
            if(safeIds.contains(number(row.get("id")))) continue;
            List<String> phones=outboundPhoneTokens(text(row.get("contact_phone")),text(row.get("customer_address")));
            String category,reason;
            if(phones.isEmpty()){category="NO_VALID_PHONE";reason="联系电话和地址中均未提取到有效电话";}
            else if(phones.size()>1){category="MULTIPLE_PHONES";reason="联系电话中存在多个号码，无法确定客户主电话";}
            else {
                int targetCount=phoneTargets.getOrDefault(phones.getFirst(),Set.of()).size();
                category=targetCount>1?"SHARED_PHONE":"UNRESOLVED";
                reason=targetCount>1?"该电话已属于多个客户档案":"未形成可安全执行的唯一关系";
            }
            counts.merge(category,1L,Long::sum);
            if(items.size()<500){
                Map<String,Object> item=outboundIdentityItem(row);
                item.put("phones",phones);item.put("category",category);item.put("reason",reason);items.add(item);
            }
        }
        long total=counts.values().stream().mapToLong(Long::longValue).sum();
        return Map.of("exceptionCount",total,"byCategory",counts,"items",items);
    }

    private static Map<String,Object> outboundIdentityItem(Map<String,Object> row){
        Map<String,Object> item=new LinkedHashMap<>();
        item.put("id",number(row.get("id")));
        item.put("outboundNo",text(row.get("outbound_no")));
        item.put("customerName",text(row.get("customer_name")));
        item.put("customerAddress",text(row.get("customer_address")));
        item.put("contactPhone",text(row.get("contact_phone")));
        return item;
    }

    private record MultiPhoneCluster(String key,String customerName,String address,List<String> phones,
                                     List<Map<String,Object>> rows,Long targetId,String targetName,String decision) {}

    public Map<String,Object> multiPhonePreview(){
        List<MultiPhoneCluster> clusters=multiPhoneClusters();
        long linkClusters=clusters.stream().filter(c->"LINK".equals(c.decision())).count();
        long createClusters=clusters.stream().filter(c->"CREATE".equals(c.decision())).count();
        long reviewClusters=clusters.size()-linkClusters-createClusters;
        long actionableOutbounds=clusters.stream().filter(c->!"REVIEW".equals(c.decision())).mapToLong(c->c.rows().size()).sum();
        List<Map<String,Object>> items=clusters.stream().map(c->{
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("customerName",c.customerName());item.put("address",c.address());item.put("phones",c.phones());
            item.put("outboundCount",c.rows().size());item.put("targetId",c.targetId());item.put("targetName",c.targetName());item.put("decision",c.decision());
            return item;
        }).toList();
        return Map.of("clusterCount",clusters.size(),"linkClusters",linkClusters,"createClusters",createClusters,
                "reviewClusters",reviewClusters,"actionableOutbounds",actionableOutbounds,"items",items);
    }

    /** 多电话仅按完全一致的来源名称、地址和电话集合聚簇；不做模糊相似合并。 */
    private List<MultiPhoneCluster> multiPhoneClusters(){
        Map<String,Set<Long>> phoneTargets=new HashMap<>();
        jdbc.queryForList("""
            SELECT customer_id,COALESCE(mobile,'') mobile,COALESCE(phone_raw,'') phone FROM customer_contacts
            UNION ALL SELECT customer_id,COALESCE(contact_phone,''),' ' FROM customer_addresses
            """).forEach(row->{
            long customerId=number(row.get("customer_id"));
            sourceIdentityService.customerPhoneTokens(text(row.get("mobile"))+" "+text(row.get("phone")))
                    .forEach(phone->phoneTargets.computeIfAbsent(phone,k->new java.util.LinkedHashSet<>()).add(customerId));
        });
        Map<Long,String> customerNames=new HashMap<>();
        Set<String> occupiedNames=new java.util.HashSet<>();
        jdbc.queryForList("SELECT id,customer_name FROM customers WHERE deleted_at IS NULL")
                .forEach(row->{String name=text(row.get("customer_name"));customerNames.put(number(row.get("id")),name);occupiedNames.add(normalize(name));});
        Map<String,List<Map<String,Object>>> grouped=new LinkedHashMap<>();
        Map<String,List<String>> phonesByKey=new HashMap<>();
        for(Map<String,Object> row:jdbc.queryForList("""
            SELECT id,outbound_no,customer_name,customer_address,contact_phone FROM sales_outbounds
             WHERE external_source='H3YUN' AND (customer_id IS NULL OR NOT EXISTS(SELECT 1 FROM customers live WHERE live.id=sales_outbounds.customer_id AND live.deleted_at IS NULL))
               AND NOT (outbound_status='DRAFT' OR UPPER(TRIM(COALESCE(status,''))) IN ('草稿','DRAFT')) ORDER BY id
            """)){
            List<String> phones=outboundPhoneTokens(text(row.get("contact_phone")),text(row.get("customer_address"))).stream().sorted().toList();
            if(phones.size()<2) continue;
            String name=text(row.get("customer_name")),address=text(row.get("customer_address"));
            String key=normalize(name)+"|"+normalize(address)+"|"+String.join(",",phones);
            grouped.computeIfAbsent(key,k->new ArrayList<>()).add(row);phonesByKey.put(key,phones);
        }
        List<MultiPhoneCluster> result=new ArrayList<>();
        for(var entry:grouped.entrySet()){
            Map<String,Object> first=entry.getValue().getFirst();List<String> phones=phonesByKey.get(entry.getKey());
            Set<Long> targets=new java.util.LinkedHashSet<>();phones.forEach(p->targets.addAll(phoneTargets.getOrDefault(p,Set.of())));
            Long target=targets.size()==1?targets.iterator().next():null;
            String sourceName=text(first.get("customer_name")),decision=targets.size()>1||sourceName.isEmpty()?"REVIEW":target==null?"CREATE":"LINK";
            String proposed=target==null?sourceIdentityService.suggestedCustomerName(sourceName,phones.getFirst()):customerNames.get(target);
            if("CREATE".equals(decision)&&occupiedNames.contains(normalize(proposed))){
                String digits=phones.getLast().replaceAll("\\D","");String suffix=digits.length()>6?digits.substring(digits.length()-6):digits;
                proposed=proposed+"["+suffix+"]";
            }
            if("CREATE".equals(decision)) occupiedNames.add(normalize(proposed));
            result.add(new MultiPhoneCluster(entry.getKey(),proposed,text(first.get("customer_address")),phones,entry.getValue(),target,customerNames.get(target),decision));
        }
        return result;
    }

    @Transactional
    public Map<String,Object> applyMultiPhone(){
        int created=0,linked=0,outbounds=0;
        for(MultiPhoneCluster cluster:multiPhoneClusters()){
            if("REVIEW".equals(cluster.decision())) continue;
            long customerId;
            if("LINK".equals(cluster.decision())){customerId=cluster.targetId();linked++;}
            else {customerId=sourceIdentityService.createOutboundCustomer(cluster.customerName(),cluster.phones(),cluster.address());created++;}
            for(Map<String,Object> row:cluster.rows()){
                outbounds+=jdbc.update("UPDATE sales_outbounds SET customer_id=? WHERE id=? AND customer_id IS NULL",customerId,number(row.get("id")));
                sourceIdentityService.rememberCustomerCluster(text(row.get("customer_name")),text(row.get("customer_address")),text(row.get("contact_phone")),customerId);
            }
        }
        audit.log("Customer",null,"多电话身份簇处理","H3_OUTBOUND_MULTI_PHONE","关联既有 "+linked+" 簇，新建 "+created+" 个客户，关联出库 "+outbounds+" 张");
        return Map.of("linkedClusters",linked,"createdCustomers",created,"customerOutbounds",outbounds);
    }

    @Transactional
    public Map<String,Object> applyNewCustomerClusters(){
        List<NewCustomerCluster> clusters=newCustomerClusters(); int outbounds=0;
        for(NewCustomerCluster cluster:clusters){
            String address=cluster.addresses().stream().findFirst().orElse(null);
            long customerId=sourceIdentityService.createOutboundCustomer(cluster.customerName(),cluster.phone(),address);
            for(Map<String,Object> row:cluster.rows()){
                outbounds+=jdbc.update("UPDATE sales_outbounds SET customer_id=? WHERE id=? AND customer_id IS NULL",customerId,number(row.get("id")));
                sourceIdentityService.rememberCustomerCluster(text(row.get("customer_name")),text(row.get("customer_address")),
                        text(row.get("contact_phone")),customerId);
            }
        }
        audit.log("Customer",null,"电话身份簇建档","H3_OUTBOUND_CUSTOMER_CREATE",
                "新建客户 "+clusters.size()+" 个，关联出库 "+outbounds+" 张");
        return Map.of("createdCustomers",clusters.size(),"customerOutbounds",outbounds);
    }

    /** 产品主档候选索引（sales_products 为千级，内存匹配足够且便于表达「多候选即不自动处理」）。 */
    private record ProductTarget(long id, String code, String name) {}

    private record ProductLookup(Map<String, List<ProductTarget>> byCode,
                                 Map<String, List<ProductTarget>> byNsu,
                                 Map<String, List<ProductTarget>> byName) {}
    private record CatalogProduct(String code,String name,String specification,String unit,String category) {}
    private record ProductIndex(Map<Long,ProductLookup> byWarehouse,
                                Map<String,List<CatalogProduct>> catalogByCode,
                                Map<Long,Map<String,CatalogProduct>> stockByWarehouseCode,
                                Map<String,CatalogProduct> stockByCode,
                                Map<String,List<CatalogProduct>> stockByNsu,
                                Map<String,List<CatalogProduct>> stockByName,
                                List<CatalogProduct> allStockProducts) {}

    private ProductIndex productIndex() {
        Map<Long,ProductLookup> byWarehouse = new HashMap<>();
        jdbc.queryForList("""
                SELECT DISTINCT b.warehouse_id,p.id,COALESCE(p.product_code,'') product_code,
                       COALESCE(p.product_name,'') product_name,COALESCE(p.specification,'') specification,
                       COALESCE(b.unit,p.unit,'') unit
                  FROM product_inventory_balances b
                  JOIN sales_products p ON p.id=b.sales_product_id AND p.deleted_at IS NULL
                """).forEach(row -> {
            long warehouseId = number(row.get("warehouse_id"));
            ProductLookup lookup = byWarehouse.computeIfAbsent(warehouseId,
                    k -> new ProductLookup(new HashMap<>(),new HashMap<>(),new HashMap<>()));
            ProductTarget target = new ProductTarget(number(row.get("id")),
                    text(row.get("product_code")), text(row.get("product_name")));
            if (!target.code().isEmpty()) lookup.byCode().computeIfAbsent(productCodeKey(target.code()), k -> new ArrayList<>()).add(target);
            lookup.byNsu().computeIfAbsent(nsuKey(target.name(), text(row.get("specification")), text(row.get("unit"))),
                    k -> new ArrayList<>()).add(target);
            String name = normalize(target.name());
            if (name != null) lookup.byName().computeIfAbsent(name, k -> new ArrayList<>()).add(target);
        });
        Map<String,List<CatalogProduct>> catalogByCode=new HashMap<>();
        jdbc.queryForList("SELECT product_code,product_name,COALESCE(specification,'') specification,"+
                "COALESCE(unit,'') unit,COALESCE(product_category,'') category FROM h3_product_catalog")
                .forEach(row->{
                    String code=text(row.get("product_code"));
                    if(!code.isEmpty()) catalogByCode.computeIfAbsent(productCodeKey(code),k->new ArrayList<>()).add(new CatalogProduct(
                            code,text(row.get("product_name")),text(row.get("specification")),text(row.get("unit")),text(row.get("category"))));
                });
        Map<Long,Map<String,CatalogProduct>> stockByWarehouseCode=new HashMap<>();
        Map<String,CatalogProduct> stockByCode=new HashMap<>();
        Map<String,List<CatalogProduct>> stockByNsu=new HashMap<>(),stockByName=new HashMap<>();
        Map<String,CatalogProduct> allStockProducts=new LinkedHashMap<>();
        jdbc.queryForList("""
                SELECT COALESCE(m.warehouse_id,w.id) warehouse_id,TRIM(s.product_code) product_code,
                       COALESCE(s.product_name,'') product_name,COALESCE(s.specification,'') specification,
                       COALESCE(s.unit,'') unit,COALESCE(s.product_category,'') category
                  FROM h3_warehouse_stock s
                  JOIN (SELECT TRIM(warehouse) warehouse,TRIM(product_code) product_code,MAX(id) id
                          FROM h3_warehouse_stock GROUP BY TRIM(warehouse),TRIM(product_code)) latest ON latest.id=s.id
                  LEFT JOIN h3_warehouse_name_mappings m ON m.source_name=TRIM(s.warehouse)
                  LEFT JOIN warehouses w ON w.active=1 AND TRIM(w.warehouse_name)=TRIM(s.warehouse)
                 WHERE COALESCE(m.warehouse_id,w.id) IS NOT NULL AND s.product_code IS NOT NULL AND TRIM(s.product_code)<>''
                """).forEach(row->{
                    String codeKey=productCodeKey(text(row.get("product_code")));
                    CatalogProduct product=new CatalogProduct(text(row.get("product_code")),text(row.get("product_name")),text(row.get("specification")),text(row.get("unit")),text(row.get("category")));
                    stockByWarehouseCode.computeIfAbsent(number(row.get("warehouse_id")),k->new HashMap<>()).put(codeKey,product);
                    stockByCode.putIfAbsent(codeKey,product);
                    addCatalogCandidate(stockByNsu,nsuKey(product.name(),product.specification(),product.unit()),product);
                    String nameKey=normalize(product.name());if(nameKey!=null)addCatalogCandidate(stockByName,nameKey,product);
                    allStockProducts.putIfAbsent(codeKey,product);
                });
        return new ProductIndex(byWarehouse,catalogByCode,stockByWarehouseCode,stockByCode,stockByNsu,stockByName,new ArrayList<>(allStockProducts.values()));
    }

    /**
     * 产品归类建议：按「来源产品编码 → 名称+规格+单位 → 仅名称」逐级降级，只对唯一命中给出建议。
     * 多候选与无候选都不自动处理，交人工判断；未归类只影响按产品的统计，不阻塞销售金额与提成。
     */
    public Map<String,Object> productPreview() {
        return productPreview(true);
    }

    /** preview 响应限制样本量；执行端必须取得全量建议，不能误把界面展示上限当成处理上限。 */
    private Map<String,Object> productPreview(boolean limitItems) {
        List<Map<String,Object>> groups = jdbc.queryForList("""
                SELECT (""" + PRODUCT_KEY_EXPR + """
                ) source_key,
                       MAX(TRIM(COALESCE(i.product_code,''))) source_code,
                       MAX(TRIM(COALESCE(i.product_name,''))) source_name,
                       MAX(TRIM(COALESCE(i.specification,''))) source_spec,
                       MAX(TRIM(COALESCE(i.unit,''))) source_unit,
                       COUNT(*) line_count,
                       COUNT(DISTINCT i.sales_outbound_id) outbound_count,
                       GROUP_CONCAT(DISTINCT o.warehouse_id ORDER BY o.warehouse_id) warehouse_ids,
                       SUM(CASE WHEN o.warehouse_id IS NULL THEN 1 ELSE 0 END) missing_warehouse_lines
                  FROM sales_outbound_items i
                  JOIN sales_outbounds o ON o.id=i.sales_outbound_id AND o.external_source='H3YUN'
                   AND NOT (o.outbound_status='DRAFT' OR UPPER(TRIM(COALESCE(o.status,''))) IN ('草稿','DRAFT'))
                 WHERE i.line_type='PRODUCT' AND i.sales_product_id IS NULL
                 GROUP BY source_key
                 ORDER BY line_count DESC,source_key
                """);
        ProductIndex index = productIndex();
        List<Map<String,Object>> items = new ArrayList<>();
        long lines = 0, suggestedLines = 0;
        long suggestedGroups = 0, ambiguousGroups = 0, notFoundGroups = 0, waitingWarehouseGroups = 0;
        for (Map<String,Object> group : groups) {
            String sourceKey = text(group.get("source_key"));
            String code = text(group.get("source_code"));
            String name = text(group.get("source_name"));
            String spec = text(group.get("source_spec"));
            String unit = text(group.get("source_unit"));
            long lineCount = number(group.get("line_count"));
            List<Long> warehouseIds = parseIds(text(group.get("warehouse_ids")));
            boolean waitingWarehouse = number(group.get("missing_warehouse_lines")) > 0 || warehouseIds.isEmpty();
            List<ProductTarget> candidates = List.of();
            String method = null;
            if (!waitingWarehouse) {
                if (!code.isEmpty()) {
                    candidates = commonCandidates(index,warehouseIds,"CODE",productCodeKey(code));
                    if (!candidates.isEmpty()) method = "CODE";
                }
                if (candidates.isEmpty()) {
                    candidates = commonCandidates(index,warehouseIds,"NSU",nsuKey(name,spec,unit));
                    if (!candidates.isEmpty()) method = "NAME_SPEC_UNIT";
                }
                if (candidates.isEmpty()) {
                    String normalizedName=normalize(name);
                    candidates=commonCandidates(index,warehouseIds,"NAME",normalizedName==null?"":normalizedName);
                    if (!candidates.isEmpty()) method="NAME";
                }
            }
            CatalogProduct catalogProduct=null;
            if(!waitingWarehouse && candidates.isEmpty() && !code.isEmpty()){
                String codeKey=productCodeKey(code);
                List<CatalogProduct> catalog=index.catalogByCode().getOrDefault(codeKey,List.of());
                boolean existsInEveryWarehouse=warehouseIds.stream().allMatch(id->
                        index.stockByWarehouseCode().getOrDefault(id,Map.of()).containsKey(codeKey));
                if(catalog.size()==1){
                    catalogProduct=catalog.getFirst();
                    method="CATALOG_CODE";
                } else if(index.stockByCode().containsKey(codeKey)) {
                    // 产品身份是全局的；仓库明细只需任一仓留有该编码，库存余额仍按仓库分别维护。
                    catalogProduct=index.stockByCode().get(codeKey);
                    method="WAREHOUSE_STOCK_CODE";
                }
            }
            if(!waitingWarehouse && candidates.isEmpty() && catalogProduct==null && !name.isEmpty()){
                List<CatalogProduct> stock=index.stockByNsu().getOrDefault(nsuKey(name,spec,unit),List.of());
                if(stock.size()==1){catalogProduct=stock.getFirst();method="WAREHOUSE_STOCK_NSU";}
                else {
                    String nameKey=normalize(name);stock=nameKey==null?List.of():index.stockByName().getOrDefault(nameKey,List.of());
                    if(stock.size()==1){catalogProduct=stock.getFirst();method="WAREHOUSE_STOCK_NAME";}
                }
            }
            if(!waitingWarehouse && candidates.isEmpty() && catalogProduct==null && !name.isEmpty()){
                List<CatalogProduct> stock=coreNameCandidates(index.allStockProducts(),name,spec,unit);
                if(stock.size()==1){catalogProduct=stock.getFirst();method="WAREHOUSE_STOCK_CORE_NAME";}
            }
            String decision = waitingWarehouse ? "WAIT_WAREHOUSE"
                    : candidates.size() == 1 || catalogProduct != null ? "SUGGEST"
                    : candidates.isEmpty() ? "NOT_FOUND" : "AMBIGUOUS";
            if ("SUGGEST".equals(decision)) { suggestedGroups++; suggestedLines += lineCount; }
            else if ("AMBIGUOUS".equals(decision)) ambiguousGroups++;
            else if ("WAIT_WAREHOUSE".equals(decision)) waitingWarehouseGroups++;
            else notFoundGroups++;
            lines += lineCount;
            Map<String,Object> item = new LinkedHashMap<>();
            item.put("sourceKey", sourceKey);
            item.put("sourceCode", code);
            item.put("sourceName", name);
            item.put("sourceSpec", spec);
            item.put("sourceUnit", unit);
            item.put("lineCount", lineCount);
            item.put("outboundCount", number(group.get("outbound_count")));
            item.put("candidateCount", candidates.size());
            item.put("warehouseIds", warehouseIds);
            item.put("matchMethod", method);
            item.put("decision", decision);
            item.put("targetId", candidates.size() == 1 ? candidates.getFirst().id() : null);
            item.put("targetName", candidates.size() == 1 ? candidates.getFirst().name()
                    : catalogProduct == null ? null : catalogProduct.name());
            item.put("targetSourceCode",catalogProduct==null?null:catalogProduct.code());
            items.add(item);
        }
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("items", limitItems && items.size() > 500 ? items.subList(0, 500) : items);
        result.put("groupCount", groups.size());
        result.put("lineCount", lines);
        result.put("suggestedGroups", suggestedGroups);
        result.put("suggestedLines", suggestedLines);
        result.put("ambiguousGroups", ambiguousGroups);
        result.put("notFoundGroups", notFoundGroups);
        result.put("waitingWarehouseGroups", waitingWarehouseGroups);
        return result;
    }

    private List<ProductTarget> commonCandidates(ProductIndex index,List<Long> warehouseIds,String kind,String key) {
        Map<Long,ProductTarget> common = null;
        for (Long warehouseId : warehouseIds) {
            ProductLookup lookup=index.byWarehouse().get(warehouseId);
            List<ProductTarget> current=lookup==null?List.of():switch(kind){
                case "CODE" -> lookup.byCode().getOrDefault(key,List.of());
                case "NSU" -> lookup.byNsu().getOrDefault(key,List.of());
                default -> lookup.byName().getOrDefault(key,List.of());
            };
            Map<Long,ProductTarget> currentMap=new LinkedHashMap<>();
            current.forEach(p->currentMap.put(p.id(),p));
            if(common==null) common=currentMap; else common.keySet().retainAll(currentMap.keySet());
            if(common.isEmpty()) return List.of();
        }
        return common==null?List.of():new ArrayList<>(common.values());
    }

    private List<Long> parseIds(String csv) {
        if(csv==null||csv.isBlank()) return List.of();
        return java.util.Arrays.stream(csv.split(",")).map(String::trim).filter(s->!s.isEmpty()).map(Long::valueOf).toList();
    }

    /**
     * 执行产品归类：只落唯一命中的建议，同时写入领域映射，使重导后由
     * {@code SourceIdentityService.resolveProduct} 自动复用，不再要求人工重复确认。
     */
    @Transactional
    public Map<String,Object> applyProductExact() {
        return applyProductExact(users.current().name());
    }

    Map<String,Object> applyProductExact(String operator) {
        Map<String,Object> preview = productPreview(false);
        int rules = 0;
        long lines = 0;
        // 同一张出库可能被多条归类规则命中，覆盖出库数必须去重后统计
        java.util.Set<Long> affectedOutbounds = new java.util.LinkedHashSet<>();
        for (Map<String,Object> item : rows(preview, "items")) {
            if (!"SUGGEST".equals(item.get("decision"))) continue;
            Long targetId = item.get("targetId") instanceof Number n ? n.longValue() : null;
            String sourceKey = text(item.get("sourceKey"));
            String sourceCode = text(item.get("sourceCode"));
            String sourceName = text(item.get("sourceName"));
            String sourceSpec = text(item.get("sourceSpec"));
            String sourceUnit = text(item.get("sourceUnit"));
            String method = text(item.get("matchMethod"));
            String targetSourceCode=text(item.get("targetSourceCode"));
            if(targetId==null && "CATALOG_CODE".equals(method)) targetId=ensureCatalogProduct(sourceCode);
            if(targetId==null && "WAREHOUSE_STOCK_CODE".equals(method)) targetId=ensureWarehouseStockProduct(sourceCode);
            if(targetId==null && ("WAREHOUSE_STOCK_NSU".equals(method)||"WAREHOUSE_STOCK_NAME".equals(method)||"WAREHOUSE_STOCK_CORE_NAME".equals(method))) targetId=ensureWarehouseStockProduct(targetSourceCode);
            if(targetId==null) continue;
            lines += jdbc.update("UPDATE sales_outbound_items i JOIN sales_outbounds o ON o.id=i.sales_outbound_id SET i.sales_product_id=?"
                    + " WHERE i.sales_product_id IS NULL AND i.line_type='PRODUCT' AND (" + PRODUCT_KEY_EXPR + ")=?"
                    + " AND o.external_source='H3YUN' AND NOT (o.outbound_status='DRAFT' OR UPPER(TRIM(COALESCE(o.status,''))) IN ('草稿','DRAFT'))",
                    targetId, sourceKey);
            affectedOutbounds.addAll(jdbc.queryForList("SELECT DISTINCT i.sales_outbound_id FROM sales_outbound_items i"
                    + " JOIN sales_outbounds o ON o.id=i.sales_outbound_id WHERE i.line_type='PRODUCT' AND i.sales_product_id=? AND (" + PRODUCT_KEY_EXPR + ")=?"
                    + " AND o.external_source='H3YUN' AND NOT (o.outbound_status='DRAFT' OR UPPER(TRIM(COALESCE(o.status,''))) IN ('草稿','DRAFT'))",
                    Long.class, targetId, sourceKey));
            // 领域映射（重导复用主通道）：resolveProduct 优先读 source_identity_aliases.source_key
            jdbc.update("""
                INSERT INTO source_identity_aliases
                  (source_system,entity_type,source_key,source_name,normalized_name,target_id,
                   resolution_status,issue_code,confidence,resolution_note,resolved_by_name,resolved_at)
                VALUES('H3YUN','PRODUCT',?,?,?,?,'AUTO',NULL,?,?,?,NOW())
                ON DUPLICATE KEY UPDATE source_name=VALUES(source_name),normalized_name=VALUES(normalized_name),
                  target_id=IF(resolution_status='CONFIRMED',target_id,VALUES(target_id)),
                  resolution_status=IF(resolution_status='CONFIRMED',resolution_status,VALUES(resolution_status)),
                  confidence=IF(resolution_status='CONFIRMED',confidence,VALUES(confidence)),
                  resolution_note=IF(resolution_status='CONFIRMED',resolution_note,VALUES(resolution_note)),
                  occurrence_count=occurrence_count+1,updated_at=NOW()
                """, sourceKey, sourceName, normalize(sourceName), targetId,
                    confidenceOf(method), "销售出库关联工作台产品归类：" + methodLabel(method), operator);
            // 兼容库存合并侧的按编码别名（external_id 与来源键同值，编码行与既有写法一致）
            jdbc.update("""
                INSERT INTO sales_product_source_aliases
                  (source_system,external_id,source_row,source_product_code,source_name,source_specification,
                   source_unit,source_hash,resolution_status,sales_product_id,last_seen_at)
                VALUES('H3YUN',?,0,?,?,?,?,?,'RESOLVED',?,NOW())
                ON DUPLICATE KEY UPDATE source_product_code=VALUES(source_product_code),source_name=VALUES(source_name),
                  source_specification=VALUES(source_specification),source_unit=VALUES(source_unit),
                  source_hash=VALUES(source_hash),resolution_status='RESOLVED',
                  sales_product_id=VALUES(sales_product_id),last_seen_at=NOW()
                """, sourceKey, sourceCode.isEmpty() ? null : sourceCode, sourceName, sourceSpec, sourceUnit,
                    sha256(sourceKey + "|" + sourceName + "|" + sourceSpec), targetId);
            rules++;
        }
        audit.log("SalesOutbound", null, "产品归类", "H3_OUTBOUND_RELATION_AUTO",
                "产品归类规则 " + rules + " 条/出库明细 " + lines + " 行");
        return Map.of("productRules", rules, "productLines", lines, "productOutbounds", affectedOutbounds.size());
    }

    /** 手工重试入口：与导入后自动编排使用同一套严格规则。 */
    @Transactional
    public Map<String,Object> applyAllDeterministic() {
        String operator = users.current().name();
        Map<String,Object> exact = applyExact(operator);
        Map<String,Object> customers = applyCustomerSafe();
        Map<String,Object> newCustomers = applyNewCustomerClusters();
        Map<String,Object> multiPhone = applyMultiPhone();
        Map<String,Object> products = applyProductExact(operator);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("employeeWarehouse", exact);
        result.put("customerLinks", customers);
        result.put("newCustomers", newCustomers);
        result.put("multiPhoneCustomers", multiPhone);
        result.put("products", products);
        return result;
    }

    /** 当前库存为零时余额表没有该产品；按产品台账唯一编号补建交易产品身份，不虚构库存数量。 */
    private long ensureCatalogProduct(String sourceCode){
        List<Map<String,Object>> catalog=jdbc.queryForList("SELECT product_name,specification,unit,product_category FROM h3_product_catalog WHERE product_code=?",sourceCode);
        if(catalog.size()!=1) throw new IllegalStateException("产品台账编号不是唯一记录："+sourceCode);
        String code="H3-"+sourceCode;
        Map<String,Object> p=catalog.getFirst();
        jdbc.update("""
            INSERT INTO sales_products(product_code,product_name,specification,unit,category,source,status,
              is_customized,external_source,external_id,remark)
            VALUES(?,?,?,?,?,'H3YUN_IMPORT','PENDING_REVIEW',0,'氚云',?,'由销售出库与仓库台账唯一编号建立；不代表当前有库存')
            ON DUPLICATE KEY UPDATE deleted_at=NULL
            """,code,text(p.get("product_name")),text(p.get("specification")),text(p.get("unit")),text(p.get("product_category")),sourceCode);
        return jdbc.queryForObject("SELECT id FROM sales_products WHERE product_code=?",Long.class,code);
    }

    /** 产品信息台账缺行时，直接使用仓库明细最新一条同编码记录建立产品身份；不产生库存流水或库存数量。 */
    private long ensureWarehouseStockProduct(String sourceCode){
        List<Map<String,Object>> stock=jdbc.queryForList("""
            SELECT product_code,product_name,specification,unit,product_category
              FROM h3_warehouse_stock
             WHERE UPPER(REPLACE(REPLACE(TRIM(product_code),' ',''),'　',''))=?
             ORDER BY id DESC LIMIT 1
            """,productCodeKey(sourceCode));
        if(stock.isEmpty()) throw new IllegalStateException("仓库明细中找不到产品编号："+sourceCode);
        Map<String,Object> p=stock.getFirst();String actualCode=text(p.get("product_code"));String code="H3-"+actualCode;
        jdbc.update("""
            INSERT INTO sales_products(product_code,product_name,specification,unit,category,source,status,
              is_customized,external_source,external_id,remark)
            VALUES(?,?,?,?,?,'H3YUN_IMPORT','PENDING_REVIEW',0,'氚云',?,'由销售出库与对应仓库明细产品编号建立；不代表当前有库存')
            ON DUPLICATE KEY UPDATE deleted_at=NULL
            """,code,text(p.get("product_name")),text(p.get("specification")),text(p.get("unit")),text(p.get("product_category")),actualCode);
        return jdbc.queryForObject("SELECT id FROM sales_products WHERE product_code=?",Long.class,code);
    }

    /** 名称+规格+单位键（包内可见：库存合并的身份解析降级链复用同一口径）。 */
    static String nsuKey(String name, String spec, String unit) {
        return java.util.Objects.toString(normalize(name), "") + "|"
                + java.util.Objects.toString(normalize(spec), "") + "|"
                + java.util.Objects.toString(normalize(unit), "");
    }

    private static void addCatalogCandidate(Map<String,List<CatalogProduct>> index,String key,CatalogProduct product){
        List<CatalogProduct> values=index.computeIfAbsent(key,k->new ArrayList<>());
        if(values.stream().noneMatch(p->productCodeKey(p.code()).equals(productCodeKey(product.code())))) values.add(product);
    }

    private static List<CatalogProduct> coreNameCandidates(List<CatalogProduct> products,String name,String spec,String unit){
        String sourceCore=productNameCore(name),sourceSpec=normalize(spec),sourceUnit=normalize(unit);
        if(sourceCore.length()<5) return List.of();
        Map<String,CatalogProduct> result=new LinkedHashMap<>();
        for(CatalogProduct product:products){
            if(sourceUnit!=null&&!sourceUnit.equals(normalize(product.unit()))) continue;
            if(sourceSpec!=null&&!sourceSpec.isEmpty()&&!sourceSpec.equals(normalize(product.specification()))) continue;
            String targetCore=productNameCore(product.name());
            if(targetCore.length()>=5&&(sourceCore.contains(targetCore)||targetCore.contains(sourceCore))) result.put(productCodeKey(product.code()),product);
        }
        return new ArrayList<>(result.values());
    }

    private static String productNameCore(String value){
        if(value==null) return "";
        return value.toLowerCase(Locale.ROOT).replace("定做","订做")
                .replaceAll("[-－—]?\\d+只(?:/箱)?$","")
                .replaceAll("[\\s,，。.;；、·_\\-/\\\\()（）【】\\[\\]+]+","");
    }

    /** 与库内 SQL 的 LOWER(REPLACE(REPLACE(TRIM(x),' ',''),'　','')) 保持同一语义。 */
    private static String normalize(String value) {
        return value == null || value.isBlank() ? null
                : value.trim().replace(" ", "").replace("　", "").toLowerCase(Locale.ROOT);
    }

    /** 产品编号键（包内可见：库存合并的身份解析降级链复用同一口径）。 */
    static String productCodeKey(String value){return value==null?"":value.trim().replace(" ","").replace("　","").toUpperCase(Locale.ROOT);}

    private static Double confidenceOf(String method) {
        return switch (method == null ? "" : method) {
            case "CODE" -> 1.0;
            case "NAME_SPEC_UNIT" -> 0.98;
            case "NAME" -> 0.85;
            case "CATALOG_CODE" -> 1.0;
            case "WAREHOUSE_STOCK_CODE" -> 1.0;
            case "WAREHOUSE_STOCK_NSU" -> 0.99;
            case "WAREHOUSE_STOCK_NAME" -> 0.9;
            case "WAREHOUSE_STOCK_CORE_NAME" -> 0.85;
            default -> null;
        };
    }

    private static String methodLabel(String method) {
        return switch (method == null ? "" : method) {
            case "CODE" -> "来源产品编码唯一命中";
            case "NAME_SPEC_UNIT" -> "名称、规格与单位唯一命中";
            case "NAME" -> "仅名称唯一命中";
            case "CATALOG_CODE" -> "产品台账编号与出库仓库明细唯一命中";
            case "WAREHOUSE_STOCK_CODE" -> "出库产品编号在对应仓库明细中命中";
            case "WAREHOUSE_STOCK_NSU" -> "名称、规格、单位在仓库明细中唯一命中";
            case "WAREHOUSE_STOCK_NAME" -> "产品名称在仓库明细中全局唯一命中";
            case "WAREHOUSE_STOCK_CORE_NAME" -> "核心名称、规格与单位在仓库明细中唯一命中";
            default -> "未命中";
        };
    }

    private static String sha256(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", ex);
        }
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }

    private static long number(Object value) { return value instanceof Number n ? n.longValue() : 0L; }

    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> rows(Map<String,Object> map,String key){return (List<Map<String,Object>>)map.get(key);}
    private long sum(List<Map<String,Object>> rows){return rows.stream().mapToLong(r->((Number)r.get("outboundCount")).longValue()).sum();}
}
