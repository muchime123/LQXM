package com.liqiang.archive.service;

import com.liqiang.archive.util.H3LedgerMatchKey;
import com.liqiang.archive.util.ProductFeeFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/**
 * 氚云销售出库只读分析；按已确认批次和类目归属缓存汇总。
 *
 * <p><b>「实体编号」口径</b>：出库明细的 {@code product_code} 是氚云里人工填写的文本、会写错，
 * 而氚云自身是按<b>产品名称</b>关联的。因此本页与产品库存清单共用 {@link H3LedgerMatchKey}
 * （名称优先、编号兜底）解析出行的实体编号，再按实体编号 × 类目聚合，并据此关联产品信息与库存。
 * 明细原样录入的编号保留在 {@code rawCodes} 里供展示与搜索，编号与名称不符的行由
 * {@code codeRewritten} 标记。
 */
@Service
@RequiredArgsConstructor
public class H3SalesProductAnalysisService {
    private final JdbcTemplate jdbc;
    /**
     * 快照按「数据版本 + 出库日期区间」缓存，保留最近 {@value #CACHE_SLOTS} 份。
     *
     * <p>单槽缓存在接入时间维度后不够用：换一个日期区间就要重跑一遍全量聚合（实测 1~2s），
     * 而用户在「今年 / 去年 / 全部」之间来回切是很常见的动作。accessOrder=true 让最近用过的
     * 区间留在缓存里，切回去是瞬时的。
     */
    private static final int CACHE_SLOTS=6;
    private final Map<String,Snapshot> cache=new LinkedHashMap<>(16,0.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,Snapshot> eldest) {
            return size()>CACHE_SLOTS;
        }
    };
    private record Snapshot(List<Map<String,Object>> batches,Map<String,Object> scope,
                            List<Map<String,Object>> categories,List<Map<String,Object>> groups,
                            List<Map<String,Object>> items) {}

    /**
     * 出库明细的 {@code FROM} 与 {@code WHERE} 刻意拆开：让「按名称解析编号」的明细查询
     * （{@link #itemsSql}）能在两者之间插入 {@link H3LedgerMatchKey#UNIQUE_NAME_CODE} 的 LEFT JOIN。
     */
    static final String BASE_FROM=" FROM sales_outbound_items i JOIN sales_outbounds o ON o.id=i.sales_outbound_id";

    /**
     * 出库明细的公共过滤片段。费用/非产品项的判据必须走 {@link ProductFeeFilter}（项目唯一实现），
     * 不得在这里另写名单——「同一概念禁两套表」。
     *
     * <p>历史教训：本片段曾自写 {@code category NOT LIKE '%费用%'} + 11 个编号字面量，与
     * {@code ProductFeeFilter} 的「精确类目集 ∪ 编号兜底」边界不同。类目为「运费」但编号不是费用
     * 名的行（实测 8 行：开模费 7 + 定制片材 1）因此被漏进产品统计，直到 2026-10-07 才收口。
     */
    static final String BASE_WHERE=" WHERE o.external_source='H3YUN' AND i.line_type='PRODUCT' AND i.quantity>0"+
            " AND NULLIF(TRIM(i.product_code),'') IS NOT NULL"+
            " AND TRIM(COALESCE(i.category,'')) NOT IN ("+ProductFeeFilter.categoryInList()+")"+
            " AND TRIM(i.product_code) NOT IN ("+ProductFeeFilter.codeInList()+")";

    /** {@link #BASE_FROM} + {@link #BASE_WHERE}；与拆分前的口径逐字相同（有断言钉死）。 */
    static final String BASE=BASE_FROM+BASE_WHERE;

    /** 允许的服务端排序字段。其余（以及未指定）一律回落到默认排序「出库单数降序」。 */
    public static final List<String> SORTABLE_FIELDS=List.of(
            "sampleNames","orderCount","salesAmount","lineCount","lastShip","nonZeroStockRows");

    private static final java.util.regex.Pattern ISO_DATE=java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /**
     * 在 {@link #BASE} 上追加出库日期区间。两端都为空即「全部历史」，与加时间维度之前完全同口径。
     *
     * <p>右端用半开区间 {@code < dateTo + 1 天} 而不是 {@code <= dateTo}：{@code ship_date} 目前是
     * {@code DATE}，两种写法等价；但换成 {@code DATETIME} 后 {@code <= dateTo} 会把当天零点之后的行
     * 全部漏掉，半开写法两种列型都对。
     */
    static String base(String dateFrom,String dateTo) {
        return BASE_FROM+baseWhere(dateFrom,dateTo);
    }

    /** {@link #BASE_WHERE} 后追加可选的日期限制；两端为空时与 {@link #BASE_WHERE} 逐字相同。 */
    static String baseWhere(String dateFrom,String dateTo) {
        StringBuilder sql=new StringBuilder(BASE_WHERE);
        if(!dateFrom.isEmpty())sql.append(" AND o.ship_date>='").append(dateFrom).append('\'');
        if(!dateTo.isEmpty())sql.append(" AND o.ship_date<DATE_ADD('").append(dateTo).append("',INTERVAL 1 DAY)");
        return sql.toString();
    }

    /**
     * 出库明细聚合 SQL：<b>按「实体编号」而非氚云录入的原编号分组</b>。
     *
     * <p>实体编号由 {@link H3LedgerMatchKey#matchCode} 解析（名称优先、编号兜底）——氚云是按产品名称
     * 关联的，{@code product_code} 只是人工填写的文本。实测 171,367 行里有 <b>40,651 行（23.7%）</b>
     * 的编号与名称所指产品不一致：既有整体偏移一位（{@code 029SDPM}→{@code 028SDPM}、
     * {@code SLH239}→{@code 239SLH}），也有一批把大类名当编号填的（{@code 模具} 一个值压了 5,669 行、
     * 横跨 248 个真实产品）。按原编号分组会把同一产品拆成多行，并让 <b>29%</b> 的行挂不上产品信息。
     *
     * <p>行数守恒：归并只改行的归属，不增不减明细行（有真库断言）。
     *
     * @return 列：{@code productCode}（实体编号）、{@code rawCodes}（氚云实际录入的编号，逗号分隔）、
     *         {@code codeRewritten}（1 = 本行含编号与名称不符的明细）
     */
    static String itemsSql(String dateFrom,String dateTo) {
        String resolvedCode=H3LedgerMatchKey.matchCode("i.product_code","nm");
        return "SELECT "+resolvedCode+" productCode,"+
                " COALESCE(NULLIF(TRIM(i.category),''),'未分类') category,"+
                " COUNT(DISTINCT i.sales_outbound_id) orderCount,COUNT(*) lineCount,"+
                " COUNT(DISTINCT NULLIF(TRIM(i.product_name),'')) nameCount,"+
                " COUNT(DISTINCT NULLIF(TRIM(i.unit),'')) unitCount,"+
                " MIN(o.ship_date) firstShip,MAX(o.ship_date) lastShip,"+
                " ROUND(SUM(COALESCE(i.sales_total,0)),2) salesAmount,"+
                " SUBSTRING_INDEX(GROUP_CONCAT(DISTINCT TRIM(i.product_name) ORDER BY TRIM(i.product_name) SEPARATOR ' / '),' / ',3) sampleNames,"+
                " SUBSTRING_INDEX(GROUP_CONCAT(DISTINCT TRIM(i.unit) ORDER BY TRIM(i.unit) SEPARATOR ' / '),' / ',3) sampleUnits,"+
                // 名称未解析时 resolvedCode 就是原编号，两式相等 → 0；因此这一列只在「编号被名称纠回」时为 1。
                " MAX(TRIM(i.product_code)<>"+resolvedCode+") codeRewritten,"+
                // 该组合在氚云里实际用过的原始编号，逗号分隔、不截断（实测 12,737 个组合最多 4 个，
                // GROUP_CONCAT 默认 1024 字节绰绰有余）：既用于页面展示，也原样传给台账做「查看出库」下钻。
                " GROUP_CONCAT(DISTINCT TRIM(i.product_code) ORDER BY TRIM(i.product_code) SEPARATOR ',') rawCodes"+
                BASE_FROM+
                " LEFT JOIN "+H3LedgerMatchKey.UNIQUE_NAME_CODE+" nm"+
                " ON "+H3LedgerMatchKey.nameJoinOn("nm","i.product_name")+
                baseWhere(dateFrom,dateTo)+
                " GROUP BY "+resolvedCode+",COALESCE(NULLIF(TRIM(i.category),''),'未分类')";
    }

    /**
     * 归一化并校验出库日期入参。只接受 {@code yyyy-MM-dd}：日期是拼进 SQL 的，
     * 用白名单格式校验比转义更可靠（拼错的日期字符串在 MySQL 里会静默变成 NULL 条件）。
     */
    static String normDate(String value) {
        if(value==null)return "";
        String text=value.trim();
        if(text.isEmpty())return "";
        if(!ISO_DATE.matcher(text).matches())
            throw new IllegalArgumentException("出库日期格式应为 yyyy-MM-dd，实际收到：" + text);
        return text;
    }

    public Map<String,Object> analyze(String keyword,String view,String category,String productGroup,
                                      boolean onlyInStock,String sort,String direction,
                                      String dateFrom,String dateTo,int page,int size) {
        int p=Math.max(1,page),s=Math.max(1,Math.min(100,size));
        String group=productGroup==null?"":productGroup.trim();
        if(!group.isEmpty()&&!List.of("BOX","FILM","MACHINE","UNASSIGNED").contains(group))
            throw new IllegalArgumentException("产品大类无效");
        String sortField=sort==null?"":sort.trim();
        if(!SORTABLE_FIELDS.contains(sortField))sortField="";
        boolean desc="desc".equalsIgnoreCase(direction==null?"":direction.trim());
        // 变量名避开分页用的 from/to。
        String dateStart=normDate(dateFrom),dateEnd=normDate(dateTo);
        // 区间反向时交换而不是报错：用户在日期选择器里先点结束日很常见，直接给结果更顺手。
        // ISO 日期串的字典序即时间序，可以直接比。
        if(!dateStart.isEmpty()&&!dateEnd.isEmpty()&&dateStart.compareTo(dateEnd)>0) {
            String swap=dateStart;dateStart=dateEnd;dateEnd=swap;
        }
        Snapshot snap=snapshot(dateStart,dateEnd);
        String key=keyword==null?"":keyword.trim().toLowerCase(Locale.ROOT);
        String cat=category==null?"":category.trim();
        List<Map<String,Object>> filtered=new ArrayList<>();
        List<Map<String,Object>> groupRows=new ArrayList<>();
        for(Map<String,Object> row:snap.items()) {
            if(!key.isEmpty()&&!str(row.get("productCode")).toLowerCase(Locale.ROOT).contains(key)
                    &&!str(row.get("sampleNames")).toLowerCase(Locale.ROOT).contains(key)
                    // rawCodes = 氚云实际录入的编号。编号被名称改写后 productCode 已是解析值，
                    // 用户按屏幕上看惯的原始编号搜也要能搜到。
                    &&!str(row.get("rawCodes")).toLowerCase(Locale.ROOT).contains(key))continue;
            if(!cat.isEmpty()&&!cat.equals(row.get("category")))continue;
            if(onlyInStock&&num(row.get("nonZeroStockRows"))==0)continue;
            if("REVIEW".equals(view)&&num(row.get("nameCount"))==1&&num(row.get("unitCount"))==1
                    &&num(row.get("categoryCount"))==1
                    &&num(row.get("inCatalog"))>0
                    &&str(row.get("sampleNames")).equals(str(row.get("catalogName")))
                    &&str(row.get("sampleUnits")).equals(str(row.get("catalogUnit"))))continue;
            if("CANDIDATE".equals(view)&&(num(row.get("nameCount"))!=1||num(row.get("unitCount"))!=1
                    ||num(row.get("categoryCount"))!=1||num(row.get("inCatalog"))==0
                    ||!str(row.get("sampleNames")).equals(str(row.get("catalogName")))
                    ||!str(row.get("sampleUnits")).equals(str(row.get("catalogUnit")))
                    ||num(row.get("nonZeroStockRows"))==0))continue;
            groupRows.add(row);
            if(!group.isEmpty()&&!group.equals(row.get("productGroup")))continue;
            filtered.add(row);
        }
        if(!sortField.isEmpty())filtered.sort(comparatorFor(sortField,desc));
        int from=(int)Math.min(filtered.size(),((long)p-1)*s),to=Math.min(filtered.size(),from+s);
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("batches",snap.batches());out.put("scope",snap.scope());out.put("categories",snap.categories());
        out.put("groups",groupSummary(groupRows));
        Set<String> codes=new HashSet<>();
        long lineCount=0;
        BigDecimal salesAmount=BigDecimal.ZERO;
        for(Map<String,Object> row:filtered) {
            codes.add(str(row.get("productCode")));
            lineCount+=num(row.get("lineCount"));
            salesAmount=salesAmount.add(new BigDecimal(str(row.get("salesAmount"))));
        }
        out.put("summary",Map.of("combinations",filtered.size(),"productCount",codes.size(),
                "lineCount",lineCount,"salesAmount",salesAmount));
        out.put("total",filtered.size());out.put("items",filtered.subList(from,to));out.put("page",p);out.put("size",s);
        return out;
    }

    private Snapshot snapshot(String dateFrom,String dateTo) {
        Map<String,Object> state=jdbc.queryForMap("SELECT"+
                " (SELECT COALESCE(MAX(id),0) FROM h3_import_batches WHERE data_type='OUTBOUND' AND status='COMPLETED') batchId,"+
                " (SELECT COALESCE(MAX(id),0) FROM h3_import_batches WHERE data_type='PRODUCT_INFO' AND status='COMPLETED') catalogBatchId,"+
                " (SELECT COALESCE(MAX(id),0) FROM h3_import_batches WHERE data_type='WAREHOUSE_STOCK' AND status='COMPLETED') stockBatchId,"+
                " (SELECT COALESCE(SUM(CRC32(CONCAT(source_category,':',product_group))),0) FROM h3_product_category_groups) groupsHash,"+
                " (SELECT COALESCE(SUM(CRC32(CONCAT(source_name,':',warehouse_id))),0) FROM h3_warehouse_name_mappings) mappingsHash,"+
                " (SELECT COALESCE(SUM(CRC32(CONCAT(id,':',warehouse_name,':',active))),0) FROM warehouses) warehousesHash");
        String version=state.get("batchId")+":"+state.get("catalogBatchId")+":"+
                state.get("stockBatchId")+":"+state.get("groupsHash")+":"+
                state.get("mappingsHash")+":"+state.get("warehousesHash")+
                "|"+dateFrom+"~"+dateTo;
        // 缓存用 accessOrder 的 LinkedHashMap，读写都会改内部链表 —— 必须整体加锁，不能像单槽 volatile
        // 版本那样做无锁快路径读。
        synchronized(this) {
            Snapshot snap=cache.get(version);
            if(snap!=null)return snap;
            snap=build(dateFrom,dateTo);
            cache.put(version,snap);
            return snap;
        }
    }

    /** 快照内容只依赖数据版本与日期区间，二者相同即等价，故 {@code version} 无需存进快照里。 */
    private Snapshot build(String dateFrom,String dateTo) {
        String where=base(dateFrom,dateTo);
        List<Map<String,Object>> batches=jdbc.queryForList("SELECT id,total_count,completed_at FROM h3_import_batches"+
                " WHERE data_type='OUTBOUND' AND status='COMPLETED' ORDER BY id DESC LIMIT 5");
        Map<String,Object> scope=jdbc.queryForMap("SELECT COUNT(DISTINCT o.id) orders,COUNT(i.id) lineCount,"+
                " MIN(o.ship_date) firstShip,MAX(o.ship_date) lastShip,"+
                " COALESCE(SUM(i.line_type='PRODUCT' AND i.quantity>0),0) positiveProductLines,"+
                " COALESCE(SUM(i.line_type='PRODUCT' AND i.quantity<0),0) returnLines,"+
                " COALESCE(SUM(i.line_type<>'PRODUCT'),0) nonProductLines"+
                " FROM sales_outbounds o LEFT JOIN sales_outbound_items i ON i.sales_outbound_id=o.id"+
                " WHERE o.external_source='H3YUN'");
        List<Map<String,Object>> categories=jdbc.queryForList("SELECT COALESCE(NULLIF(TRIM(i.category),''),'未分类') category,"+
                " COUNT(*) lineCount,COUNT(DISTINCT i.sales_outbound_id) orderCount,"+
                " ROUND(SUM(COALESCE(i.sales_total,0)),2) salesAmount"+where+
                " GROUP BY COALESCE(NULLIF(TRIM(i.category),''),'未分类') ORDER BY lineCount DESC");
        Map<String,Map<String,Object>> catalog=new HashMap<>();
        jdbc.queryForList("SELECT product_code,product_name,unit FROM h3_product_catalog")
                .forEach(r->catalog.putIfAbsent(str(r.get("product_code")),r));
        Map<String,String> groups=new HashMap<>();
        jdbc.queryForList("SELECT source_category,product_group FROM h3_product_category_groups")
                .forEach(r->groups.put(str(r.get("source_category")),str(r.get("product_group"))));
        for(Map<String,Object> row:categories)
            row.put("productGroup",groups.getOrDefault(str(row.get("category")),"UNASSIGNED"));
        // 与产品库存清单一致：只统计能映射到仓库、非费用项、数量不为 0 的仓库明细。
        // 关联口径统一走 H3LedgerMatchKey（名称优先、编号兜底）—— 氚云是按产品名称关联的，
        // 编号是人工填写的文本、会写错；只按编号会让写错编号的库存行挂到一个不存在的编号上。
        Map<String,Map<String,Object>> stockByCode=new HashMap<>();
        jdbc.queryForList("SELECT "+H3LedgerMatchKey.matchCode("ws.product_code","nm")+" productCode,"+
                " COUNT(*) nonZeroStockRows,"+
                " COUNT(DISTINCT COALESCE(m.warehouse_id,w.id)) stockWarehouseCount"+
                " FROM h3_warehouse_stock ws"+
                " LEFT JOIN "+H3LedgerMatchKey.UNIQUE_NAME_CODE+" nm"+
                " ON "+H3LedgerMatchKey.nameJoinOn("nm","ws.product_name")+
                " LEFT JOIN h3_product_catalog pc ON pc.product_code="+
                H3LedgerMatchKey.matchCode("ws.product_code","nm")+
                " LEFT JOIN h3_warehouse_name_mappings m ON m.source_name=TRIM(ws.warehouse)"+
                " LEFT JOIN warehouses w ON w.active=1 AND TRIM(w.warehouse_name)=TRIM(ws.warehouse)"+
                " WHERE COALESCE(m.warehouse_id,w.id) IS NOT NULL AND COALESCE(ws.quantity,0)<>0"+
                " AND TRIM(COALESCE(NULLIF(TRIM(pc.product_category),''),ws.product_category,'')) NOT IN ("+
                ProductFeeFilter.categoryInList()+")"+
                " AND TRIM(ws.product_code) NOT IN ("+ProductFeeFilter.codeInList()+")"+
                " GROUP BY "+H3LedgerMatchKey.matchCode("ws.product_code","nm"))
                .forEach(r->stockByCode.put(str(r.get("productCode")),r));
        List<Map<String,Object>> rows=jdbc.queryForList(itemsSql(dateFrom,dateTo));
        for(Map<String,Object> row:rows) {
            Map<String,Object> item=catalog.get(str(row.get("productCode")));
            row.put("productGroup",groups.getOrDefault(str(row.get("category")),"UNASSIGNED"));
            row.put("inCatalog",item==null?0:1);
            row.put("catalogName",item==null?null:item.get("product_name"));
            row.put("catalogUnit",item==null?null:item.get("unit"));
            Map<String,Object> stock=stockByCode.get(str(row.get("productCode")));
            row.put("nonZeroStockRows",stock==null?0:stock.get("nonZeroStockRows"));
            row.put("stockWarehouseCount",stock==null?0:stock.get("stockWarehouseCount"));
        }
        Map<String,Long> categoryCounts=new HashMap<>();
        for(Map<String,Object> row:rows)categoryCounts.merge(str(row.get("productCode")),1L,Long::sum);
        for(Map<String,Object> row:rows)
            row.put("categoryCount",categoryCounts.getOrDefault(str(row.get("productCode")),1L));
        List<Map<String,Object>> summaries=groupSummary(rows);
        rows.sort(comparatorFor("orderCount",true));
        return new Snapshot(batches,scope,categories,summaries,rows);
    }

    private static List<Map<String,Object>> groupSummary(List<Map<String,Object>> rows) {
        Map<String,Map<String,Object>> groupSummary=new LinkedHashMap<>();
        for(String group:List.of("BOX","FILM","MACHINE","UNASSIGNED")) {
            Map<String,Object> summary=new LinkedHashMap<>();
            summary.put("productGroup",group);summary.put("lineCount",0L);
            summary.put("salesAmount",BigDecimal.ZERO);summary.put("categoryCount",0L);
            summary.put("productCount",0L);summary.put("reviewCount",0L);
            groupSummary.put(group,summary);
        }
        Map<String,java.util.Set<String>> categoriesByGroup=new HashMap<>(),productsByGroup=new HashMap<>();
        for(Map<String,Object> row:rows) {
            String code=str(row.get("productCode")),group=str(row.get("productGroup"));
            Map<String,Object> summary=groupSummary.get(group);
            summary.put("lineCount",num(summary.get("lineCount"))+num(row.get("lineCount")));
            summary.put("salesAmount",((BigDecimal)summary.get("salesAmount")).add(new BigDecimal(str(row.get("salesAmount")))));
            categoriesByGroup.computeIfAbsent(group,k->new java.util.HashSet<>()).add(str(row.get("category")));
            productsByGroup.computeIfAbsent(group,k->new java.util.HashSet<>()).add(code);
            if(num(row.get("nameCount"))!=1||num(row.get("unitCount"))!=1||num(row.get("categoryCount"))!=1
                    ||num(row.get("inCatalog"))==0
                    ||!str(row.get("sampleNames")).equals(str(row.get("catalogName")))
                    ||!str(row.get("sampleUnits")).equals(str(row.get("catalogUnit"))))
                summary.put("reviewCount",num(summary.get("reviewCount"))+1);
        }
        for(String group:groupSummary.keySet()) {
            groupSummary.get(group).put("categoryCount",categoriesByGroup.getOrDefault(group,java.util.Set.of()).size());
            groupSummary.get(group).put("productCount",productsByGroup.getOrDefault(group,java.util.Set.of()).size());
        }
        return new ArrayList<>(groupSummary.values());
    }

    /**
     * 「产品名差不多的放在一起」的名称排序：先比名称归并键（忽略规格数字与标点），再比完整名称，最后比编号。
     * 归并键相同的行必然相邻，因此同族产品（如仅尺寸不同的同一款）会聚成一段。
     * 三段都用 {@link #nameNaturalCompare}：汉字走 {@link Collator}（zh_CN，实测为拼音序，非码点序），
     * 连续数字段按数值比（A-3 在 A-18 前），保证同族内的读数顺序与人工书写一致、且完全可复现。
     */
    static Comparator<Map<String,Object>> nameComparator() {
        Collator collator=Collator.getInstance(Locale.CHINA);
        Comparator<String> text=(a,b)->nameNaturalCompare(a,b,collator);
        return Comparator.comparing((Map<String,Object> r)->nameSkeleton(str(r.get("sampleNames"))),text)
                .thenComparing(r->str(r.get("sampleNames")),text)
                .thenComparing(r->str(r.get("productCode")),text);
    }

    /**
     * 名称自然序比较：把字符串切成「数字段 / 非数字段」交替的片段逐个比。
     * 数字段按数值比（去前导零后先比长度再比字典序），非数字段交给中文 Collator。
     * 例：{@code A-3豆腐盒} &lt; {@code A-18豆腐盒}（若按字符逐个比会得到相反结果）。
     */
    static int nameNaturalCompare(String a,String b,Collator collator) {
        int i=0,j=0;
        while(i<a.length()&&j<b.length()) {
            boolean digits=Character.isDigit(a.charAt(i))&&Character.isDigit(b.charAt(j));
            int i2=i,j2=j;
            while(i2<a.length()&&Character.isDigit(a.charAt(i2))==digits)i2++;
            while(j2<b.length()&&Character.isDigit(b.charAt(j2))==digits)j2++;
            int c;
            if(digits) {
                String na=stripLeadingZeros(a.substring(i,i2)),nb=stripLeadingZeros(b.substring(j,j2));
                c=na.length()!=nb.length()?Integer.compare(na.length(),nb.length()):na.compareTo(nb);
            }
            else c=collator.compare(a.substring(i,i2),b.substring(j,j2));
            if(c!=0)return c;
            i=i2;j=j2;
        }
        return Integer.compare(a.length()-i,b.length()-j);
    }

    private static String stripLeadingZeros(String s) {
        int k=0;
        while(k<s.length()-1&&s.charAt(k)=='0')k++;
        return s.substring(k);
    }

    /**
     * 构造服务端排序列的比较器：主键按 {@code desc} 翻转，兜底链 {@link #tieBreak()} 方向固定不翻转。
     *
     * <p>兜底链不跟着翻转是刻意的——「按名称降序」只应把名称的大方向倒过来，同族内部仍按出库单数、
     * 销售额降序排，否则每次加一列排序都要顺手翻转一遍兜底口径，顺序会变得难以解释。
     *
     * <p>金额列必须走 {@link BigDecimal} 比，不能用 {@code num()} 的 {@code longValue()}：后者会截掉
     * 小数位，金额相近的行被判成相等后顺序退化为不确定。末次出库按 ISO 文本比（{@code yyyy-MM-dd}），
     * 字典序与日期序一致；无出库日期时为空串，升序排最前、降序排最后。
     */
    static Comparator<Map<String,Object>> comparatorFor(String field,boolean desc) {
        Comparator<Map<String,Object>> primary;
        switch(field==null?"":field) {
            case "sampleNames" -> primary=nameComparator();
            case "salesAmount" -> primary=Comparator.comparing(
                    r->new BigDecimal(str(r.get("salesAmount"))));
            case "lineCount" -> primary=Comparator.comparingLong(r->num(r.get("lineCount")));
            case "nonZeroStockRows" -> primary=Comparator.comparingLong(r->num(r.get("nonZeroStockRows")));
            case "lastShip" -> primary=Comparator.comparing(r->str(r.get("lastShip")));
            default -> primary=Comparator.comparingLong(r->num(r.get("orderCount")));
        }
        return (desc?primary.reversed():primary).thenComparing(tieBreak());
    }

    /** 排序兜底链：出库单数降 → 销售额降 → 编号升。让任何主键下都得到确定、可复现的顺序。 */
    private static Comparator<Map<String,Object>> tieBreak() {
        return Comparator.<Map<String,Object>>comparingLong(r->num(r.get("orderCount"))).reversed()
                .thenComparing((a,b)->new BigDecimal(str(b.get("salesAmount")))
                        .compareTo(new BigDecimal(str(a.get("salesAmount")))))
                .thenComparing(r->str(r.get("productCode")));
    }

    /**
     * 名称归并键：转小写 → 去掉所有数字 → 去掉标点/符号/空白（含多名称的 {@code " / "} 分隔符）。
     * 例：「三边封袋 140*200+40mm」与「三边封袋 150*220+40mm」→ 同为 {@code 三边封袋mm}，排序后相邻。
     * 仅用于列表排序归并，不参与任何匹配、判等或统计口径。
     */
    static String nameSkeleton(String name) {
        if(name==null||name.isEmpty())return "";
        return name.toLowerCase(Locale.ROOT).replaceAll("\\p{N}+","").replaceAll("[\\p{P}\\p{S}\\s]+","");
    }

    private static String str(Object value){return value==null?"":String.valueOf(value);}
    private static long num(Object value){return value instanceof Number n?n.longValue():0;}
}
