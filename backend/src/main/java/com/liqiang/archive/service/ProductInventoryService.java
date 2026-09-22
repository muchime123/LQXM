package com.liqiang.archive.service;

import com.liqiang.archive.security.CurrentUserService;
import com.liqiang.archive.util.ProductFeeFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Service @RequiredArgsConstructor
public class ProductInventoryService {
    private final JdbcTemplate jdbc;private final CurrentUserService users;

    /**
     * 确保一张正式生产任务有一个「交易产品身份」，并返回该身份 id。
     *
     * <p><b>归一口径（2026-09-21 / Q1 拍板）</b>：先按 (名称, 规格, 单位) 找**唯一**的氚云标准
     * SKU（{@code external_id IS NOT NULL}）——找到就直接复用，**不造 {@code MFG-}**；找不到才造
     * {@code MFG-<工单号>} 作为「订单专属产出标识」（编码保留、可追溯到工单）。
     *
     * <p>无论走哪条路，都会保证 {@code canonical_product_id} 有值：它是**库存聚合维度**，
     * 库存余额按它唯一。这样即使某张单造了独立身份，只要它归一到某个标准 SKU，库存就不会
     * 分裂成 N 个 product_id（这正是 Q1 要保留 {@code MFG-} 又不保留缺陷的关键）。
     *
     * <p>匹配刻意保守：命中 0 个或多个一律不归一（{@link #findCanonicalProduct}）。
     * 错合并两件不同的货是不可逆的，各自独立只是暂时没聚合——两者代价不对称。
     */
    /**
     * 取（或建立）工单的产出产品身份。
     *
     * <p>⚠️ <b>P3（V322）起，收货路径不再经过这里</b>。原因见
     * {@code 完工入库到销售出库的身份断链与闭环设计-20260921.md} §4.3：本方法按
     * {@code MFG-<工单号>} <b>给每张工单造一个独立产品身份</b>，而 V320 的归一策略是
     * 「按 (名称,规格,单位) 精确匹配唯一的 H3-」，实测历史 4 个 {@code MFG-}
     * <b>全部匹配 0 个</b>（名字来自自由文本，与 SKU 规范名不一致）→ 归一实际不会发生，
     * 库存仍会按单号分裂。现在收货按<b>工单产出清单</b>取 SKU，身份在建单时就已经是标准 SKU。
     *
     * <p><b>保留原因</b>（Q1 拍板「保留 {@code MFG-} 编码机制」）：它是「主数据里确实没有这件货」
     * （新品 / 非标订做件）时**唯一**能产生新身份的入口，未来由「清单项建档」场景调用，
     * 且必须伴随 {@code PENDING_REVIEW} 审批。删掉它会让非标件无路可走 —— 于是又回到自由文本。
     *
     * <p>当前无调用点（保留待接线），**不要因为 grep 不到调用方就直接删除**。
     */
    @Transactional
    public long ensureManufacturingProduct(long orderId,String orderNo,String name,String specification,String unit){
        Long linked=jdbc.queryForObject("SELECT sales_product_id FROM manufacturing_orders WHERE id=?",Long.class,orderId);if(linked!=null)return linked;
        Long exact=findCanonicalProduct(name,specification,unit);
        long productId;
        if(exact!=null){productId=exact;}
        else{
            String code="MFG-"+orderNo;
            jdbc.update("INSERT INTO sales_products(product_code,product_name,specification,unit,source,status,remark) VALUES(?,?,?,?, 'MANUFACTURING','PENDING_REVIEW','由正式生产任务自动建立的交易产品身份') ON DUPLICATE KEY UPDATE id=id",code,name,blank(specification),unit);
            productId=jdbc.queryForObject("SELECT id FROM sales_products WHERE product_code=?",Long.class,code);
            // 新造的身份先归自身；若将来同一件货在氚云建了标准 SKU，由归一迁移一次性回填
            jdbc.update("UPDATE sales_products SET canonical_product_id=? WHERE id=? AND canonical_product_id IS NULL",productId,productId);
        }
        jdbc.update("UPDATE manufacturing_orders SET sales_product_id=? WHERE id=? AND sales_product_id IS NULL",productId,orderId);return productId;
    }

    /**
     * 归一口径**唯一实现**（与 V320 回填同口径，勿在别处另写一份）。
     *
     * <p>按 (名称, 规格, 单位) 精确匹配、且限定 {@code external_id IS NOT NULL}（只归一到氚云标准 SKU，
     * 不归到另一个 {@code MFG-}）。
     *
     * @return 命中恰好 1 个时返回其 id；命中 0 个或多个返回 {@code null}（不归一）
     */
    private Long findCanonicalProduct(String name,String specification,String unit){
        var hits=jdbc.queryForList("SELECT id FROM sales_products WHERE deleted_at IS NULL AND external_id IS NOT NULL AND product_name=? AND COALESCE(specification,'')=? AND COALESCE(unit,'')=?",Long.class,name,clean(specification),clean(unit));
        return hits.size()==1?hits.getFirst():null;
    }

    /** 产品身份的库存聚合口径（{@code canonical_product_id}）；未设置或查不到时回退自身。 */
    private long canonicalOf(long productId){
        var rows=jdbc.queryForList("SELECT COALESCE(canonical_product_id,id) c FROM sales_products WHERE id=?",Long.class,productId);
        return rows.isEmpty()?productId:rows.getFirst();
    }

    /**
     * 记一笔库存变动：余额按 **canonical 口径**聚合，流水同时记业务身份与归一口径。
     *
     * <p>为什么余额用 canonical 而不是 {@code sales_product_id}（2026-09-21 / Q1）：
     * `MFG-<工单号>` 是按单造的身份，若余额按它唯一，同一件商品会分裂成 N 行、各 1 件，
     * 「有货直接出库」永远判断不出来。改按 canonical 聚合后，多张工单产出的同款货累加在一行，
     * 而流水仍保留 {@code sales_product_id} 供追溯「这笔货是哪张单产出的」。
     *
     * <p>库存不足抛异常 → 由调用方事务整体回滚（余额、流水、单据三者同生共死）。
     */
    @Transactional
    public void post(long warehouseId,long productId,Long lotId,String name,String specification,String unit,String type,BigDecimal change,String sourceType,long sourceId,String sourceNo,LocalDateTime at,String remark){
        long canonicalId=canonicalOf(productId);
        jdbc.update("INSERT INTO product_inventory_balances(warehouse_id,sales_product_id,canonical_product_id,unit,quantity) VALUES(?,?,?,?,0) ON DUPLICATE KEY UPDATE id=id",warehouseId,productId,canonicalId,unit);
        var balance=jdbc.queryForMap("SELECT id,quantity FROM product_inventory_balances WHERE warehouse_id=? AND canonical_product_id=? AND unit=? FOR UPDATE",warehouseId,canonicalId,unit);BigDecimal after=((BigDecimal)balance.get("quantity")).add(change);if(after.signum()<0)throw new IllegalStateException("统一产品库存不足，不能完成操作");
        jdbc.update("UPDATE product_inventory_balances SET quantity=? WHERE id=?",after,balance.get("id"));String tmp="PENDING-"+UUID.randomUUID();var user=users.current();jdbc.update("INSERT INTO product_inventory_transactions(transaction_no,warehouse_id,sales_product_id,canonical_product_id,finished_goods_lot_id,product_name_snapshot,specification_snapshot,unit,transaction_type,quantity_change,balance_after,source_type,source_id,source_no,operator_employee_id,operator_name,occurred_at,remark) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",tmp,warehouseId,productId,canonicalId,lotId,name,blank(specification),unit,type,change,after,sourceType,sourceId,sourceNo,user.employeeId(),user.name(),at,blank(remark));long id=jdbc.queryForObject("SELECT id FROM product_inventory_transactions WHERE transaction_no=?",Long.class,tmp);jdbc.update("UPDATE product_inventory_transactions SET transaction_no=? WHERE id=?","PI"+String.format("%08d",id),id);
    }

    /**
     * 某产品身份的库存流水（含归一到它的、以及它归一出去的那些）。
     *
     * <p>按 canonical 检索而非按 {@code sales_product_id}：否则用 {@code MFG-} 身份造的行
     * 在标准 SKU 的流水页里看不见（反之亦然），用户会以为「库存凭空变了」。
     */
    public List<Map<String,Object>> transactions(long productId){
        return jdbc.queryForList("SELECT t.*,w.warehouse_name warehouseName FROM product_inventory_transactions t JOIN warehouses w ON w.id=t.warehouse_id WHERE t.canonical_product_id=? ORDER BY t.occurred_at DESC,t.id DESC LIMIT 500",canonicalOf(productId));
    }

    /**
     * 可售库存（canonical 口径，数量 &gt; 0），供 ERP 出库选货。
     *
     * <p>只列真正有货的行——「选不到货」本身就是业务信号（该下生产任务），不是错误。
     */
    public List<Map<String,Object>> availableStock(String keyword,Long warehouseId,int limit){
        StringBuilder where=new StringBuilder(" WHERE b.quantity>0");List<Object> p=new ArrayList<>();
        if(warehouseId!=null){where.append(" AND b.warehouse_id=?");p.add(warehouseId);}
        if(keyword!=null&&!keyword.isBlank()){
            where.append(" AND (sp.product_code LIKE ? OR sp.product_name LIKE ? OR COALESCE(sp.specification,'') LIKE ?)");
            String k="%"+keyword.trim()+"%";p.add(k);p.add(k);p.add(k);
        }
        p.add(Math.max(1,Math.min(limit,200)));
        return jdbc.queryForList(
                "SELECT b.id balanceId,b.warehouse_id warehouseId,w.warehouse_name warehouseName," +
                " b.sales_product_id salesProductId,b.canonical_product_id canonicalProductId," +
                " sp.product_code productCode,sp.product_name productName,sp.specification,sp.unit,b.quantity available" +
                " FROM product_inventory_balances b" +
                " JOIN sales_products sp ON sp.id=b.canonical_product_id AND sp.deleted_at IS NULL" +
                " JOIN warehouses w ON w.id=b.warehouse_id" + where +
                " ORDER BY sp.product_name,sp.specification,b.warehouse_id LIMIT ?",p.toArray());
    }

    private static String clean(String s){return s==null?"":s.trim();}private static String blank(String s){s=clean(s);return s.isEmpty()?null:s;}

    /**
     * 库存清单驱动集（2026-09-14 重建为「台账合并视图」）：
     * 氚云「产品信息台账 × 仓库明细」两张台账的并集，而非库存余额表。
     *
     * <p>为什么不再以余额表驱动：台账合并在 {@code ProductInventoryMergeService} 里
     * 跳过零库存行与费用项，余额表因此只覆盖 1551 个产品；15279 个产品里九成不可见。
     *
     * <p>驱动集两个来源：
     * <ol>
     *   <li>仓库明细里<b>实际出现</b>的 (氚云编号, 仓库) 组合。不做「产品 × 全仓」笛卡尔
     *       展开——仓库明细是各仓自己的台账，未出现的仓库存关系并不存在，展开会凭空造出
     *       2/3 的伪零行（实测 13726 个产品只出现在 1 个仓库）。</li>
     *   <li>产品信息台账里有、但仓库明细完全没有该编号的产品，各补一行、仓库留空
     *       （前端显示「未记录仓库」）。</li>
     * </ol>
     * 实测驱动集 16866 行 = 16273（组合）+ 593（台账独有），覆盖 15586 个产品。
     *
     * <p>字段口径：类目/名称以产品信息台账优先并可被仓库明细补齐；数量与采购价以台账为准。
     * 余额表不参与字段取值（实测仅 1 行与台账分叉），只在取到当页数据后单独查询，
     * 用于提供「查看流水」入口与更新时间——这样每页 5 次汇总查询也不必付连接成本。
     */
    private static final String VIEW_FROM =
            " FROM (" +
            " SELECT TRIM(ws.product_code) product_code,COALESCE(m.warehouse_id,w.id) warehouse_id," +
            " w.warehouse_name,ws.product_name,ws.specification,ws.unit," +
            " ws.product_category,ws.quantity,ws.purchase_price" +
            " FROM h3_warehouse_stock ws" +
            " LEFT JOIN h3_warehouse_name_mappings m ON m.source_name=TRIM(ws.warehouse)" +
            " LEFT JOIN warehouses w ON w.active=1 AND TRIM(w.warehouse_name)=TRIM(ws.warehouse)" +
            " WHERE COALESCE(m.warehouse_id,w.id) IS NOT NULL" +
            " UNION ALL" +
            " SELECT TRIM(pc.product_code),NULL,NULL,pc.product_name,pc.specification,pc.unit," +
            " pc.product_category,NULL,pc.purchase_price" +
            " FROM h3_product_catalog pc" +
            " WHERE NOT EXISTS (SELECT 1 FROM h3_warehouse_stock x" +
            " WHERE TRIM(x.product_code)=TRIM(pc.product_code))" +
            ") v" +
            " LEFT JOIN h3_product_catalog pc ON pc.product_code=v.product_code";

    /** 类目：产品信息台账优先，仓库明细补齐。 */
    private static final String CATEGORY_EXPR =
            "COALESCE(NULLIF(TRIM(pc.product_category),''),NULLIF(TRIM(v.product_category),''))";
    /** 名称/规格/单位：以仓库明细该行的事实为准（该行代表仓里实际那件东西），缺失时回退台账。 */
    private static final String NAME_EXPR="COALESCE(NULLIF(TRIM(v.product_name),''),pc.product_name)";
    private static final String SPEC_EXPR="COALESCE(NULLIF(TRIM(v.specification),''),pc.specification)";
    private static final String UNIT_EXPR="COALESCE(NULLIF(TRIM(v.unit),''),NULLIF(TRIM(pc.unit),''),'个')";
    /** 数量：仓库明细最新值（页面即台账合并视图）；台账独有产品无仓库行，按 0 呈现。 */
    private static final String QUANTITY_EXPR="COALESCE(v.quantity,0)";
    /**
     * 采购价：产品信息台账优先，退化仓库明细价。
     * 口径（2026-09-16 固化）：仓库明细的 purchase_price 恒空（氚云导出该列值全 null，
     * 实测 16305 行 100% null），实际唯一来源是产品信息台账；NULLIF 兜底仅防结构变化时丢价。
     */
    private static final String PRICE_EXPR="COALESCE(NULLIF(pc.purchase_price,0),NULLIF(v.purchase_price,0))";
    private static final String AMOUNT_EXPR="ROUND("+QUANTITY_EXPR+"*COALESCE("+PRICE_EXPR+",0),4)";
    /** 零库存判定：合并后的数量为 0。 */
    private static final String ZERO_CONDITION="("+QUANTITY_EXPR+"=0)";
    /** 定做判定：与 ProductInventoryMergeService#isCustomized 同口径（名称或编号含 定做/订做）。 */
    private static final String CUSTOMIZED_EXPR=
            "CASE WHEN "+NAME_EXPR+" LIKE '%定做%' OR "+NAME_EXPR+" LIKE '%订做%'" +
            " OR v.product_code LIKE '%定做%' OR v.product_code LIKE '%订做%' THEN 1 ELSE 0 END";
    /**
     * 编码冲突标记：台账同编号存在、但名称与该仓库明细行不一致。
     *
     * <p>实测有 47 个编号在两张台账里指向完全不同的产品（如编号 1733YX 在台账是
     * 「公主的渔大连海鲜」、在仓库明细是「手抓卤虾黄底」）。这类行的编号不可信，
     * 页面据此打标提醒人工核对，不再静默采信。
     */
    private static final String CODE_CONFLICT_EXPR=
            "CASE WHEN pc.product_code IS NOT NULL AND TRIM(COALESCE(v.product_name,''))<>''" +
            " AND LOWER(CONVERT(TRIM(pc.product_name) USING utf8mb4))" +
            " <> LOWER(CONVERT(TRIM(v.product_name) USING utf8mb4)) THEN 1 ELSE 0 END";

    /**
     * 费用/非产品项过滤：这些行的「数量」列装的是金额（运费 120813、定制片材 1344071 等），
     * 混进清单会虚增库存。类目命中即排除；类目为空时（实测存在 code=加工费、类目为空的行）
     * 用编号兜底。
     *
     * <p>抽成按别名拼接的工厂方法：清单视图的别名是派生表 {@code v}，异常行视图的别名是
     * {@code ws/pc}，两处必须同口径——历史教训是异常统计漏套这段过滤，把 7 行费用项
     * （运费×3 / 优惠 / 加急费 / 样品费×2）报成「缺价待处理」，而它们在清单里已被排除，
     * 用户点进去根本找不到对应行。
     */
    private static String feeFilter(String categoryExpr,String codeExpr){
        return " AND TRIM(COALESCE("+categoryExpr+",'')) NOT IN ("+ProductFeeFilter.categoryInList()+")" +
               " AND TRIM(COALESCE("+codeExpr+",'')) NOT IN ("+ProductFeeFilter.codeInList()+")";
    }

    /** 清单视图（派生表别名 v）的费用过滤。 */
    private static final String FEE_FILTER=feeFilter(CATEGORY_EXPR,"v.product_code");

    /** 异常行视图的类目表达式：产品信息台账优先，仓库明细补齐（与清单 CATEGORY_EXPR 同口径）。 */
    private static final String ISSUE_CATEGORY_EXPR=
            "COALESCE(NULLIF(TRIM(pc.product_category),''),NULLIF(TRIM(ws.product_category),''))";

    /** 异常行视图（别名 ws/pc）的费用过滤：与清单同一份名单，杜绝两处漂移。 */
    private static final String ISSUE_FEE_FILTER=feeFilter(ISSUE_CATEGORY_EXPR,"ws.product_code");

    private static final String VIEW_COLUMNS=
            "SELECT v.warehouse_id warehouseId,v.warehouse_name warehouseName," +
            " v.product_code productCode," +
            " "+NAME_EXPR+" productName,"+SPEC_EXPR+" specification,"+UNIT_EXPR+" unit," +
            " "+QUANTITY_EXPR+" quantity,"+PRICE_EXPR+" purchasePrice,"+AMOUNT_EXPR+" stockAmount," +
            " "+CATEGORY_EXPR+" category,"+CUSTOMIZED_EXPR+" isCustomized," +
            " CASE WHEN v.warehouse_id IS NULL THEN 0 ELSE 1 END warehouseRecorded," +
            " "+CODE_CONFLICT_EXPR+" codeConflict," +
            " pc.shelf_status shelfStatus,pc.shelf_updated_at shelfUpdatedAt";

    /** 汇总统计：5 项聚合压成一个 SELECT，避免同一个派生表被重复物化 5 次（实测省约 1.2s）。 */
    private static final String SUMMARY_COLUMNS=
            "SELECT COUNT(*) total,COUNT(DISTINCT v.product_code) productCount," +
            " COUNT(DISTINCT CASE WHEN NOT "+ZERO_CONDITION+" THEN v.warehouse_id END) warehouseCount," +
            " COALESCE(SUM(CASE WHEN "+ZERO_CONDITION+" THEN 1 ELSE 0 END),0) zeroCount," +
            " COALESCE(SUM("+AMOUNT_EXPR+"),0) totalAmount";

    /** 生命周期状态合法取值（与 V304 迁移注释、前端下拉一致）。 */
    private static final Set<String> SHELF_STATUSES=Set.of("ON_SHELF","OFF_SHELF");

    /** 生命周期筛选片段：ALL 不过滤；ON_SHELF/OFF_SHELF 按产品信息台账状态过滤（仓库独有行两边都不命中）。 */
    private static String shelfFilter(String shelfStatus,List<Object> p){
        if(shelfStatus==null||shelfStatus.isBlank()||"ALL".equals(shelfStatus))return "";
        if(!SHELF_STATUSES.contains(shelfStatus))throw new IllegalArgumentException("生命周期状态无效："+shelfStatus);
        p.add(shelfStatus);
        return " AND pc.shelf_status=?";
    }

    /**
     * 产品库存清单（筛选后分页）+ 全量聚合统计：统计卡片基于整个筛选结果而非当前页。
     *
     * <p>默认返回「完整清单」口径（含零库存产品）；勾选「仅看有库存」只留下数量非 0 的行。
     */
    public Map<String,Object> balances(String keyword,Long warehouseId,String category,boolean onlyInStock,
                                       String shelfStatus,int page,int size){
        if(page<1||size<1||size>100)throw new IllegalArgumentException("分页范围无效");
        StringBuilder where=new StringBuilder(" WHERE 1=1").append(FEE_FILTER);List<Object> p=new ArrayList<>();
        if(warehouseId!=null){where.append(" AND v.warehouse_id=?");p.add(warehouseId);}
        if(keyword!=null&&!keyword.isBlank()){
            where.append(" AND (v.product_code LIKE ? OR "+NAME_EXPR+" LIKE ? OR "+SPEC_EXPR+" LIKE ?)");
            String k="%"+keyword.trim()+"%";p.add(k);p.add(k);p.add(k);
        }
        if(category!=null&&!category.isBlank()){where.append(" AND "+CATEGORY_EXPR+"=?");p.add(category.trim());}
        if(onlyInStock)where.append(" AND NOT "+ZERO_CONDITION);
        where.append(shelfFilter(shelfStatus,p));
        String from=VIEW_FROM+where;

        Map<String,Object> agg=jdbc.queryForMap(SUMMARY_COLUMNS+from,p.toArray());
        List<Object> pp=new ArrayList<>(p);pp.add(size);pp.add((long)(page-1)*size);
        // 有库存的排前面，其次是有仓库记录的行（台账独有产品排在最后），避免真实库存被淹没
        var items=jdbc.queryForList(VIEW_COLUMNS+from+
                " ORDER BY "+ZERO_CONDITION+" ASC,(v.warehouse_id IS NULL) ASC,v.warehouse_id,"+NAME_EXPR+
                " LIMIT ? OFFSET ?",pp.toArray());
        attachBalances(items);
        // 类目下拉与所选类目条件解耦，保证切换类目时选项完整；仓库/关键词维度参与（选项应随之收窄）
        StringBuilder cw=new StringBuilder(" WHERE 1=1").append(FEE_FILTER);List<Object> cp=new ArrayList<>();
        if(warehouseId!=null){cw.append(" AND v.warehouse_id=?");cp.add(warehouseId);}
        if(keyword!=null&&!keyword.isBlank()){
            cw.append(" AND (v.product_code LIKE ? OR "+NAME_EXPR+" LIKE ? OR "+SPEC_EXPR+" LIKE ?)");
            String k="%"+keyword.trim()+"%";cp.add(k);cp.add(k);cp.add(k);
        }
        if(onlyInStock)cw.append(" AND NOT "+ZERO_CONDITION);
        cw.append(shelfFilter(shelfStatus,cp));
        List<String> categories=jdbc.queryForList(
                "SELECT DISTINCT "+CATEGORY_EXPR+" AS c"+VIEW_FROM+cw+
                " AND "+CATEGORY_EXPR+" IS NOT NULL AND "+CATEGORY_EXPR+"<>'' ORDER BY c",String.class,cp.toArray());

        Map<String,Object> summary=new LinkedHashMap<>();
        summary.put("total",num(agg.get("total")));summary.put("productCount",num(agg.get("productCount")));
        summary.put("warehouseCount",num(agg.get("warehouseCount")));summary.put("zeroCount",num(agg.get("zeroCount")));
        summary.put("totalAmount",agg.get("totalAmount"));
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("items",items);result.put("total",summary.get("total"));result.put("page",page);result.put("size",size);
        result.put("summary",summary);result.put("categories",categories);
        result.put("warehouses",jdbc.queryForList("SELECT id,warehouse_code warehouseCode,warehouse_name warehouseName FROM warehouses WHERE active=1 ORDER BY id"));
        return result;
    }

    /**
     * 给当页行补上已确认库存信息（余额行 id、交易产品 id、更新时间）。
     *
     * <p>余额表不参与清单字段取值，只用来判断这一行「是否已确认入库」并给出流水入口。
     * 当页最多 100 行，一次 IN 查询即可，避免在主视图上对 1.7 万行做两次左连接。
     */
    private void attachBalances(List<Map<String,Object>> items){
        if(items.isEmpty())return;
        StringBuilder in=new StringBuilder();
        List<Object> args=new ArrayList<>();
        for(Map<String,Object> r:items){
            Object wid=r.get("warehouseId");Object code=r.get("productCode");
            if(wid==null||code==null)continue;
            if(in.length()>0)in.append(',');
            in.append("(?,?)");args.add(String.valueOf(code));args.add(wid);
        }
        if(in.isEmpty()){
            for(Map<String,Object> r:items){r.put("salesProductId",null);r.put("hasBalance",0);r.put("balanceId",null);r.put("updatedAt",null);}
            return;
        }
        Map<String,Map<String,Object>> byKey=new HashMap<>();
        jdbc.queryForList("SELECT sp.external_id productCode,b.warehouse_id warehouseId,sp.id salesProductId," +
                        " b.id balanceId,b.updated_at updatedAt FROM product_inventory_balances b" +
                        " JOIN sales_products sp ON sp.id=b.sales_product_id AND sp.deleted_at IS NULL" +
                        " WHERE (sp.external_id,b.warehouse_id) IN ("+in+")",args.toArray())
                .forEach(m->byKey.putIfAbsent(m.get("productCode")+"\u0001"+m.get("warehouseId"),m));
        for(Map<String,Object> r:items){
            Map<String,Object> hit=byKey.get(r.get("productCode")+"\u0001"+r.get("warehouseId"));
            r.put("salesProductId",hit==null?null:hit.get("salesProductId"));
            r.put("balanceId",hit==null?null:hit.get("balanceId"));
            r.put("updatedAt",hit==null?null:hit.get("updatedAt"));
            r.put("hasBalance",hit==null?0:1);
        }
    }

    private static long num(Object v){return v==null?0L:((Number)v).longValue();}

    /** 异常行视图的表连接（含处置结论左连接；业务键与 uk_h3ws_wh_code_unit 一致）。 */
    private static final String ISSUE_JOINS=
            " FROM h3_warehouse_stock ws" +
            " LEFT JOIN h3_warehouse_name_mappings m ON m.source_name=TRIM(ws.warehouse)" +
            " LEFT JOIN warehouses w ON w.active=1 AND TRIM(w.warehouse_name)=TRIM(ws.warehouse)" +
            " LEFT JOIN h3_product_catalog pc ON TRIM(pc.product_code)=TRIM(ws.product_code)" +
            " LEFT JOIN h3_inventory_issue_dispositions d" +
            " ON d.warehouse_name=TRIM(COALESCE(ws.warehouse,''))" +
            " AND d.product_code=TRIM(ws.product_code) AND d.unit=COALESCE(TRIM(ws.unit),'')";

    /**
     * 异常判定（不含费用过滤）：「清单里看得见」且命中缺产品或缺价。
     *
     * <p>仓库映射不上的行在清单里根本不出现（VIEW_FROM 要求
     * {@code COALESCE(m.warehouse_id,w.id) IS NOT NULL}），不该出现在异常里。
     */
    private static final String ISSUE_WHERE=
            " WHERE COALESCE(m.warehouse_id,w.id) IS NOT NULL" +
            " AND (pc.id IS NULL OR (COALESCE(ws.quantity,0)>0 AND COALESCE(pc.purchase_price,0)<=0))";

    /** 异常行统一视图：与清单同口径（可见性 + 费用过滤），并带处置状态。 */
    private static final String ISSUE_FROM=ISSUE_JOINS+ISSUE_WHERE+ISSUE_FEE_FILTER;

    private static final String ISSUE_COLUMNS=
            "SELECT TRIM(ws.product_code) productCode,ws.product_name productName," +
            " TRIM(COALESCE(ws.warehouse,'')) warehouseName,COALESCE(m.warehouse_id,w.id) warehouseId," +
            " ws.specification,COALESCE(TRIM(ws.unit),'') unit,COALESCE(ws.quantity,0) quantity," +
            " CASE WHEN pc.id IS NULL THEN 'MISSING_PRODUCT' ELSE 'MISSING_PRICE' END issueType," +
            " d.id dispositionId,d.status dispositionStatus,d.remark dispositionRemark," +
            " d.disposed_by dispositionBy,d.disposed_at dispositionAt";

    /** 处置结论合法取值（与 V302 迁移注释、前端下拉一一对应）。 */
    private static final Set<String> DISPOSITION_STATUSES=Set.of("PENDING_H3","DATA_RESIDUE","NO_PRICE_NEEDED");

    /** 异常类型合法取值（与 ISSUE_COLUMNS 里的 CASE 一致）。 */
    private static final Set<String> ISSUE_TYPES=Set.of("MISSING_PRODUCT","MISSING_PRICE");

    /**
     * 库存快照元信息 + 待处理异常（2026-09-16 第二阶段；处置口径于同日第三阶段修正）。
     *
     * <p>本页定位是「氚云库存快照」：产品信息台账（维表：名称/规格/单位/采购价）
     * × 仓库明细台账（事实：仓库/数量）的组合视图，两份来源的批次与导出时间差
     * 决定了快照的新鲜度——时间差过大说明两份文件不是同一时点导出的，金额口径
     * 会偏，前端据此黄标提示。
     *
     * <p><b>异常口径三层收敛（第三阶段修正的核心）</b>：历史实现直接对
     * {@code h3_warehouse_stock} 跑两个 join，报出 331 行，而真正影响库存金额的只有
     * 28 行——用户面对一个既无法处理、又看不出该处理哪几行的数字。三层收敛后：
     * <ol>
     *   <li><b>与清单同口径</b>：只统计清单里看得见的行（仓库映射得上、不命中
     *       {@link ProductFeeFilter}）。历史漏套费用过滤，把 7 行费用项（运费×3 /
     *       优惠 / 加急费 / 样品费×2，其「数量」列里装的是金额）报成「缺价待处理」，
     *       而它们在清单里已被排除，用户点进去找不到对应行。</li>
     *   <li><b>按是否影响金额分层</b>：数量为 0 的行乘任何价都是 0，不影响库存总额，
     *       归「仅台账残留」；只有数量非 0 才算「影响金额」。历史把 296 行零库存缺产品
     *       与 28 行真影响混成一个数字。</li>
     *   <li><b>扣除已处置</b>：命中 {@code h3_inventory_issue_dispositions} 的行不再计入
     *       待处理（人已给出结论），单独回显为「已处置」——异常数因此能真正收敛到 0，
     *       而不是每次重导后原样冒出来。</li>
     * </ol>
     *
     * <p>两类异常（互斥）：{@code MISSING_PRODUCT} 缺产品——仓库明细有该编号、产品信息
     * 台账没有，数量有效但金额算不出（对应合并预览 PRODUCT_NOT_FOUND）；
     * {@code MISSING_PRICE} 缺价——编号两边都能对上、数量非 0，但产品信息台账采购价
     * 为空或 0（对应合并预览 PRICE_MISSING）。
     */
    public Map<String,Object> snapshotInfo(){
        Map<String,Object> catalog=lastBatch("PRODUCT_INFO");
        Map<String,Object> stock=lastBatch("WAREHOUSE_STOCK");
        Long gap=null;
        Object ca=catalog.get("completedAt"),sa=stock.get("completedAt");
        if(ca instanceof java.time.LocalDateTime a&&sa instanceof java.time.LocalDateTime b)
            gap=Math.abs(java.time.Duration.between(a,b).getSeconds());

        // 一次查询拿到 两个异常类型 × {待处理 / 影响金额 / 已处置} 的全部计数
        Map<String,Map<String,Object>> agg=new HashMap<>();
        jdbc.queryForList(
                "SELECT issueType,SUM(dispositionId IS NULL) pending," +
                " SUM(dispositionId IS NULL AND quantity>0) affected," +
                " SUM(dispositionId IS NOT NULL) disposed," +
                " COUNT(DISTINCT CASE WHEN dispositionId IS NULL THEN productCode END) pendingCodes" +
                " FROM ("+ISSUE_COLUMNS+ISSUE_FROM+") t GROUP BY issueType")
                .forEach(r->agg.put(String.valueOf(r.get("issueType")),r));
        Map<String,Object> mp=agg.getOrDefault("MISSING_PRODUCT",Map.of());
        Map<String,Object> pr=agg.getOrDefault("MISSING_PRICE",Map.of());

        // 被费用过滤挡掉的行数：让「历史为什么报 331」有据可查，也证明没把真问题一起滤掉
        long rawAll=num(jdbc.queryForMap("SELECT COUNT(*) c"+ISSUE_JOINS+ISSUE_WHERE).get("c"));
        long keptAll=num(jdbc.queryForMap("SELECT COUNT(*) c"+ISSUE_FROM).get("c"));

        Map<String,Object> result=new LinkedHashMap<>();
        result.put("catalogBatch",catalog);result.put("stockBatch",stock);result.put("gapSeconds",gap);
        result.put("missingProductCount",num(mp.get("pending")));
        result.put("missingProductCodes",num(mp.get("pendingCodes")));
        result.put("missingProductInStockCount",num(mp.get("affected")));
        result.put("missingPriceCount",num(pr.get("pending")));
        result.put("missingPriceCodes",num(pr.get("pendingCodes")));
        result.put("missingPriceInStockCount",num(pr.get("affected")));
        result.put("disposedCount",num(mp.get("disposed"))+num(pr.get("disposed")));
        // 前端口径条主数字：真正影响库存金额的待处理行（零库存行金额本就是 0，不计）
        result.put("affectedCount",num(mp.get("affected"))+num(pr.get("affected")));
        result.put("pendingCount",num(mp.get("pending"))+num(pr.get("pending")));
        result.put("feeExcludedCount",rawAll-keptAll);
        // 明细各取前 200 行，够人工核对；全量走 issueRowsAll() 导出，不在页面上翻几千行
        result.put("missingProductRows",pendingIssueRows("MISSING_PRODUCT"));
        result.put("missingPriceRows",pendingIssueRows("MISSING_PRICE"));
        result.put("disposedRows",disposedIssueRows());

        // 导入覆盖报告（V304）：超过 30 天未被任何导入批次覆盖、且仍「在用」的行。
        // 语义是「氚云导出里可能已经没有这行了」的软提示——绝不自动降级：台账是手动
        // 导出的文件，部分导入是常态，不在本次文件里 ≠ 已删除；是否下架由人决定
        // （抽屉里一键标记，见 setShelfStatus）。已下架的行不再提示（下架后不再导入是预期）。
        result.put("staleCatalogCount",num(jdbc.queryForMap(
                "SELECT COUNT(*) c FROM h3_product_catalog WHERE shelf_status='ON_SHELF' AND "+staleCond("last_seen_at")).get("c")));
        result.put("staleCatalogRows",jdbc.queryForList(
                "SELECT TRIM(product_code) productCode,product_name productName,product_category category,unit," +
                " purchase_price purchasePrice,last_seen_at lastSeenAt FROM h3_product_catalog" +
                " WHERE shelf_status='ON_SHELF' AND "+staleCond("last_seen_at")+
                " ORDER BY last_seen_at,product_code LIMIT 200"));
        result.put("staleStockCount",num(jdbc.queryForMap(
                "SELECT COUNT(*) c FROM h3_warehouse_stock ws" +
                " LEFT JOIN h3_product_catalog pc ON pc.product_code=ws.product_code" +
                " WHERE COALESCE(pc.shelf_status,'ON_SHELF')='ON_SHELF' AND "+staleCond("ws.last_seen_at")).get("c")));
        result.put("staleStockRows",jdbc.queryForList(
                "SELECT TRIM(ws.product_code) productCode,ws.product_name productName,ws.warehouse," +
                " ws.specification,ws.unit,ws.quantity,ws.last_seen_at lastSeenAt FROM h3_warehouse_stock ws" +
                " LEFT JOIN h3_product_catalog pc ON pc.product_code=ws.product_code" +
                " WHERE COALESCE(pc.shelf_status,'ON_SHELF')='ON_SHELF' AND "+staleCond("ws.last_seen_at")+
                " ORDER BY ws.last_seen_at,ws.product_code LIMIT 200"));
        result.put("staleDays",STALE_DAYS);

        // 正式账偏离（L3 vs L2 快照）：数量>0 的余额行里，台账快照已无此行（vanished）或
        // 数量与当前台账不一致（drifted）。>0 即提示「该跑库存对账了」——把对账时机从
        // 靠人记变成系统提醒。键口径与合并预览一致：编号(trim)+单位+仓库(映射后 id)。
        Map<String,Object> divergence=jdbc.queryForMap(
                "SELECT COALESCE(SUM(ws.code IS NULL),0) vanished," +
                " COALESCE(SUM(ws.code IS NOT NULL AND ws.qty<>b.quantity),0) drifted" +
                " FROM product_inventory_balances b" +
                " JOIN sales_products sp ON sp.id=b.sales_product_id AND sp.deleted_at IS NULL AND sp.external_id IS NOT NULL" +
                " LEFT JOIN ( SELECT TRIM(x.product_code) code,x.unit,COALESCE(m.warehouse_id,w.id) wid,SUM(x.quantity) qty" +
                "   FROM h3_warehouse_stock x" +
                "   LEFT JOIN h3_warehouse_name_mappings m ON m.source_name=TRIM(x.warehouse)" +
                "   LEFT JOIN warehouses w ON w.active=1 AND TRIM(w.warehouse_name)=TRIM(x.warehouse)" +
                "   WHERE COALESCE(m.warehouse_id,w.id) IS NOT NULL" +
                "   GROUP BY TRIM(x.product_code),x.unit,COALESCE(m.warehouse_id,w.id)) ws" +
                " ON ws.code=TRIM(sp.external_id) AND ws.unit=b.unit AND ws.wid=b.warehouse_id" +
                " WHERE b.quantity>0");
        result.put("balanceVanished",num(divergence.get("vanished")));
        result.put("balanceDrifted",num(divergence.get("drifted")));
        return result;
    }

    /** 待处理明细（未处置，数量降序，取前 200）。 */
    private List<Map<String,Object>> pendingIssueRows(String issueType){
        return jdbc.queryForList(
                "SELECT productCode,productName,warehouseName,warehouseId,specification,unit,quantity,issueType" +
                " FROM ("+ISSUE_COLUMNS+ISSUE_FROM+") t" +
                " WHERE issueType=? AND dispositionId IS NULL" +
                " ORDER BY quantity DESC,productCode,unit LIMIT 200",issueType);
    }

    /** 已处置明细（带结论），按处置时间倒序取前 200，供抽屉回显「已处理」。 */
    private List<Map<String,Object>> disposedIssueRows(){
        return jdbc.queryForList(
                "SELECT productCode,productName,warehouseName,warehouseId,specification,unit,quantity,issueType," +
                " dispositionStatus,dispositionRemark,dispositionBy,dispositionAt" +
                " FROM ("+ISSUE_COLUMNS+ISSUE_FROM+") t WHERE dispositionId IS NOT NULL" +
                " ORDER BY dispositionAt DESC LIMIT 200");
    }

    /**
     * 异常全量明细（未处置），供导出——页面只给前 200 行，几百行人工核不完。
     *
     * @param issueType 可选，{@code MISSING_PRODUCT} / {@code MISSING_PRICE}；为空或空串则两类都要
     */
    public List<Map<String,Object>> issueRowsAll(String issueType){
        StringBuilder where=new StringBuilder(" WHERE dispositionId IS NULL");
        List<Object> args=new ArrayList<>();
        if(issueType!=null&&!issueType.isBlank()){
            String t=issueType.trim();
            if(!ISSUE_TYPES.contains(t))throw new IllegalArgumentException("异常类型无效："+issueType);
            where.append(" AND issueType=?");args.add(t);
        }
        return jdbc.queryForList(
                "SELECT productCode,productName,warehouseName,warehouseId,specification,unit,quantity,issueType" +
                " FROM ("+ISSUE_COLUMNS+ISSUE_FROM+") t"+where+
                " ORDER BY issueType,quantity DESC,productCode,unit",args.toArray());
    }

    /**
     * 标记 / 更新一条异常处置结论（按业务键幂等 upsert，同一行重复标记即改判）。
     *
     * <p>只写处置结论，<b>不碰任何台账数据</b>——采购价的唯一来源仍是氚云，补价一律回氚云
     * 改数后重导产品信息台账，避免本地改动被下一次重导静默覆盖。
     */
    /** 覆盖报告阈值（天）：超过该时长未被任何导入批次覆盖、且仍「在用」的行进入提示名单。 */
    private static final long STALE_DAYS=30;

    /** 覆盖报告/陈旧行过滤片段：ON_SHELF 且 last_seen_at 早于阈值。 */
    private String staleCond(String lastSeenColumn){
        return lastSeenColumn+" < DATE_SUB(NOW(), INTERVAL "+STALE_DAYS+" DAY)";
    }

    /**
     * 设置产品生命周期状态（V304）：产品永不物理删除，氚云端「删除/停用」等状态变化
     * 由人在这里表达为 在用/下架，并随行展示。只写本地管理列，不动产品事实字段
     * （氚云仍是唯一来源，导入 upsert 不覆盖本列）。
     *
     * @return 实际更新的台账行数（编号在产品信息台账不存在的会被跳过）
     */
    @Transactional
    public int setShelfStatus(List<String> productCodes,String status){
        if(!SHELF_STATUSES.contains(status))throw new IllegalArgumentException("生命周期状态无效："+status);
        List<String> codes=productCodes==null?List.of():productCodes.stream()
                .filter(c->c!=null&&!c.isBlank()).map(String::trim).distinct().toList();
        if(codes.isEmpty())throw new IllegalArgumentException("产品编号不能为空");
        var user=users.current();
        int updated=0;
        for(int i=0;i<codes.size();i+=500){
            List<String> chunk=codes.subList(i,Math.min(i+500,codes.size()));
            String in=String.join(",",Collections.nCopies(chunk.size(),"?"));
            List<Object> args=new ArrayList<>();
            args.add(status);args.add(user.name());args.addAll(chunk);
            updated+=jdbc.update("UPDATE h3_product_catalog SET shelf_status=?,shelf_updated_at=NOW()," +
                    "shelf_updated_by=? WHERE TRIM(product_code) IN ("+in+")",args.toArray());
        }
        return updated;
    }

    /** 标记 / 更新一条异常处置结论（按业务键幂等 upsert，同一行重复标记即改判）。 */
    @Transactional
    public Map<String,Object> disposeIssue(String warehouseName,String productCode,String unit,
                                           String issueType,String status,String remark){
        String wh=clean(warehouseName),code=clean(productCode),u=clean(unit);
        if(code.isEmpty())throw new IllegalArgumentException("产品编号不能为空");
        String type=clean(issueType);
        if(!ISSUE_TYPES.contains(type))throw new IllegalArgumentException("异常类型无效："+issueType);
        String st=clean(status);
        if(!DISPOSITION_STATUSES.contains(st))throw new IllegalArgumentException("处置结论无效："+status);
        var user=users.current();
        jdbc.update("INSERT INTO h3_inventory_issue_dispositions" +
                        "(warehouse_name,warehouse_id,product_code,unit,issue_type,status,remark,disposed_by)" +
                        " VALUES(?,?,?,?,?,?,?,?)" +
                        " ON DUPLICATE KEY UPDATE warehouse_id=VALUES(warehouse_id),issue_type=VALUES(issue_type)," +
                        " status=VALUES(status),remark=VALUES(remark),disposed_by=VALUES(disposed_by),disposed_at=NOW()",
                wh,resolveWarehouseId(wh),code,u,type,st,blank(remark),user.name());
        Map<String,Object> r=new LinkedHashMap<>();
        r.put("warehouseName",wh);r.put("productCode",code);r.put("unit",u);
        r.put("issueType",type);r.put("status",st);r.put("disposedBy",user.name());
        return r;
    }

    /** 撤销一条处置结论（业务键定位）：判错了，或氚云补完数据该行已不再是异常时使用。 */
    @Transactional
    public int undoIssueDisposition(String warehouseName,String productCode,String unit){
        return jdbc.update("DELETE FROM h3_inventory_issue_dispositions" +
                        " WHERE warehouse_name=? AND product_code=? AND unit=?",
                clean(warehouseName),clean(productCode),clean(unit));
    }

    /** 由氚云仓库名解析本系统仓库 id（映射表优先，其次按名称精确匹配）；解析不到返回 null。 */
    private Long resolveWarehouseId(String warehouseName){
        return jdbc.queryForObject(
                "SELECT COALESCE((SELECT m.warehouse_id FROM h3_warehouse_name_mappings m" +
                        "                  WHERE m.source_name=? LIMIT 1)," +
                        "                (SELECT w.id FROM warehouses w" +
                        "                  WHERE w.active=1 AND TRIM(w.warehouse_name)=? LIMIT 1))",
                Long.class,warehouseName,warehouseName);
    }

    /** 最近一次已完成的指定类型导入批次（快照来源标识）。 */
    private Map<String,Object> lastBatch(String dataType){
        List<Map<String,Object>> rows=jdbc.queryForList(
                "SELECT id,file_name fileName,created_at createdAt,completed_at completedAt" +
                " FROM h3_import_batches WHERE data_type=? AND status='COMPLETED'" +
                " ORDER BY completed_at DESC,id DESC LIMIT 1",dataType);
        return rows.isEmpty()?Map.of():rows.getFirst();
    }

    /**
     * 单行库存详情：把「这一行是怎么拼出来的」摊开给用户看（2026-09-16）。
     *
     * <p>列表行是 (仓库 × 产品编号) 的台账合并结果，本方法按同一口径回查该行本身，
     * 并附带列表页看不到的三块信息：
     * <ol>
     *   <li>{@code catalogSource} / {@code stockSource}：两份来源台账行（各自带导入批次、
     *       导入时间与记录 id）——前端据此展示来源卡片、跳转台账详情、拉取更新明细；</li>
     *   <li>{@code spread}：同编号在各仓的分布（含本行）；台账单行看不到该产品的全貌；</li>
     *   <li>{@code spreadTotal}：分布行数 / 有货仓库数 / 金额合计——数量跨单位相加无意义，
     *       故只给金额与计数（与列表汇总卡片同口径）。</li>
     * </ol>
     *
     * <p>定位键为 (仓库, 编号, 单位)：V295 之后同一仓库同一编号可存在多单位行，仅凭编号
     * 无法唯一定位；仓库为 null 表示「产品信息台账独有、仓库明细无该产品」的行。
     */
    public Map<String,Object> rowDetail(Long warehouseId,String productCode,String unit){
        String code=clean(productCode);
        if(code.isEmpty())throw new IllegalArgumentException("产品编号不能为空");
        String wantUnit=blank(unit);

        StringBuilder where=new StringBuilder(" WHERE 1=1").append(FEE_FILTER);
        List<Object> args=new ArrayList<>();
        where.append(" AND TRIM(v.product_code)=?");args.add(code);
        where.append(" AND v.warehouse_id <=> ?");args.add(warehouseId);
        if(wantUnit!=null){where.append(" AND ").append(UNIT_EXPR).append("=?");args.add(wantUnit);}
        List<Map<String,Object>> found=jdbc.queryForList(
                VIEW_COLUMNS+VIEW_FROM+where+" ORDER BY v.warehouse_id LIMIT 1",args.toArray());
        if(found.isEmpty())
            throw new IllegalArgumentException("未找到库存行："+(wantUnit==null?code:code+" / "+wantUnit));
        Map<String,Object> row=new LinkedHashMap<>(found.getFirst());
        attachBalances(List.of(row));

        // 来源①：产品信息台账行（唯一键 product_code，至多一行）
        Map<String,Object> catalog=firstOrNull(
                "SELECT id,import_batch_id batchId,product_category category,TRIM(product_code) productCode," +
                        " product_name productName,specification,unit,purchase_price purchasePrice," +
                        " source_object_id sourceObjectId,source_title sourceTitle,created_by_name createdByName," +
                        " department_name departmentName,owner_name ownerName,data_status dataStatus," +
                        " source_created_at sourceCreatedAt,source_modified_at sourceModifiedAt," +
                        " last_synced_at lastSyncedAt,created_at createdAt,updated_at updatedAt" +
                        " FROM h3_product_catalog WHERE TRIM(product_code)=?",code);
        // 来源②：仓库明细行——按 (仓库映射后 id, 编号) 取候选，再按与 UNIT_EXPR 同口径的单位选出该行，
        // 避免「仓库明细单位为空、单位取自产品信息台账」时错配到别的单位行
        List<Map<String,Object>> stockRows=warehouseId==null?List.of():jdbc.queryForList(
                "SELECT ws.id,ws.import_batch_id batchId,ws.warehouse,TRIM(ws.product_code) productCode," +
                        " ws.product_name productName,ws.product_category category,ws.specification,ws.unit," +
                        " ws.quantity,ws.purchase_price purchasePrice,ws.source_object_id sourceObjectId," +
                        " ws.source_title sourceTitle,ws.created_by_name createdByName," +
                        " ws.department_name departmentName,ws.owner_name ownerName,ws.data_status dataStatus," +
                        " ws.source_created_at sourceCreatedAt,ws.source_modified_at sourceModifiedAt," +
                        " ws.last_synced_at lastSyncedAt,ws.created_at createdAt,ws.updated_at updatedAt" +
                        " FROM h3_warehouse_stock ws" +
                        " LEFT JOIN h3_warehouse_name_mappings m ON m.source_name=TRIM(ws.warehouse)" +
                        " LEFT JOIN warehouses w ON w.active=1 AND TRIM(w.warehouse_name)=TRIM(ws.warehouse)" +
                        " WHERE TRIM(ws.product_code)=? AND COALESCE(m.warehouse_id,w.id) <=> ?" +
                        " ORDER BY ws.id DESC",code,warehouseId);
        Map<String,Object> stock=null;
        for(Map<String,Object> s:stockRows){
            String eff=effUnitOf(s.get("unit"),catalog==null?null:catalog.get("unit"));
            if(wantUnit==null||wantUnit.equals(eff)){stock=s;break;}
        }

        // 同编号跨仓库分布（含本行；零库存行不入分布，只统计有货仓库）
        List<Map<String,Object>> spread=jdbc.queryForList(
                "SELECT v.warehouse_id warehouseId,v.warehouse_name warehouseName,"+UNIT_EXPR+" unit," +
                        QUANTITY_EXPR+" quantity,"+PRICE_EXPR+" purchasePrice,"+AMOUNT_EXPR+" stockAmount" +
                        VIEW_FROM+" WHERE 1=1"+FEE_FILTER+" AND TRIM(v.product_code)=?" +
                        " AND NOT "+ZERO_CONDITION+" ORDER BY "+QUANTITY_EXPR+" DESC,v.warehouse_id",code);
        BigDecimal amount=BigDecimal.ZERO;Set<Long> warehouseIds=new HashSet<>();
        for(Map<String,Object> s:spread){
            Object a=s.get("stockAmount");
            if(a!=null)amount=amount.add(new BigDecimal(String.valueOf(a)));
            Object wid=s.get("warehouseId");
            if(wid instanceof Number n)warehouseIds.add(n.longValue());
        }
        Map<String,Object> total=new LinkedHashMap<>();
        total.put("rowCount",spread.size());
        total.put("warehouseCount",warehouseIds.size());
        total.put("totalAmount",amount);

        Map<String,Object> result=new LinkedHashMap<>();
        result.put("row",row);
        result.put("catalogSource",catalog);
        result.put("stockSource",stock);
        result.put("spread",spread);
        result.put("spreadTotal",total);
        return result;
    }

    /** 单行查询：无命中返回 null（详情接口据此区分「来源台账缺该行」与「查询失败」）。 */
    private Map<String,Object> firstOrNull(String sql,Object... args){
        List<Map<String,Object>> rows=jdbc.queryForList(sql,args);
        return rows.isEmpty()?null:rows.getFirst();
    }

    /** 与 {@link #UNIT_EXPR} 同口径的单位兜底：仓库明细单位 → 产品信息台账单位 →「个」。 */
    private static String effUnitOf(Object stockUnit,Object catalogUnit){
        String s=stockUnit==null?null:blank(String.valueOf(stockUnit));
        if(s!=null)return s;
        String c=catalogUnit==null?null:blank(String.valueOf(catalogUnit));
        return c!=null?c:"个";
    }

}
