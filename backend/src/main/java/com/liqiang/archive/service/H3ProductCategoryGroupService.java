package com.liqiang.archive.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 用户维护的氚云原始类目到产品大类映射，不修改源台账。 */
@Service
@RequiredArgsConstructor
public class H3ProductCategoryGroupService {
    private final JdbcTemplate jdbc;
    private static final Set<String> GROUPS=Set.of("BOX","FILM","MACHINE");

    public List<Map<String,Object>> categories() {
        return jdbc.queryForList("SELECT c.sourceCategory,COALESCE(g.product_group,'UNASSIGNED') productGroup," +
                " c.catalogRows,c.stockRows,c.salesLines FROM (" +
                " SELECT sourceCategory,SUM(catalogRows) catalogRows,SUM(stockRows) stockRows,SUM(salesLines) salesLines FROM (" +
                " SELECT TRIM(product_category) sourceCategory,COUNT(*) catalogRows,0 stockRows,0 salesLines" +
                " FROM h3_product_catalog WHERE NULLIF(TRIM(product_category),'') IS NOT NULL GROUP BY TRIM(product_category)" +
                " UNION ALL SELECT TRIM(product_category),0,COUNT(*),0 FROM h3_warehouse_stock" +
                " WHERE NULLIF(TRIM(product_category),'') IS NOT NULL GROUP BY TRIM(product_category)" +
                " UNION ALL SELECT TRIM(category),0,0,COUNT(*) FROM sales_outbound_items" +
                " WHERE NULLIF(TRIM(category),'') IS NOT NULL GROUP BY TRIM(category)" +
                " ) x GROUP BY sourceCategory) c LEFT JOIN h3_product_category_groups g" +
                " ON g.source_category=c.sourceCategory ORDER BY c.salesLines DESC,c.stockRows DESC,c.sourceCategory");
    }

    public void assign(String sourceCategory,String productGroup) {
        String category=sourceCategory==null?"":sourceCategory.trim();
        String group=productGroup==null?"UNASSIGNED":productGroup.trim();
        if(category.isEmpty()||category.length()>150)throw new IllegalArgumentException("请选择有效的原始产品类目");
        if(!"UNASSIGNED".equals(group)&&!GROUPS.contains(group))throw new IllegalArgumentException("产品大类只能是盒子、膜或机器");
        Long exists=jdbc.queryForObject("SELECT COUNT(*) FROM ("+
                " SELECT product_category c FROM h3_product_catalog UNION ALL"+
                " SELECT product_category FROM h3_warehouse_stock UNION ALL"+
                " SELECT category FROM sales_outbound_items) x WHERE TRIM(x.c)=?",Long.class,category);
        if(exists==null||exists==0)throw new IllegalArgumentException("原始产品类目不存在");
        if("UNASSIGNED".equals(group))jdbc.update("DELETE FROM h3_product_category_groups WHERE source_category=?",category);
        else jdbc.update("INSERT INTO h3_product_category_groups(source_category,product_group) VALUES(?,?)"+
                " ON DUPLICATE KEY UPDATE product_group=VALUES(product_group)",category,group);
    }

    /** 整批先校验再写入，同一事务内全部成功或全部回滚。 */
    @Transactional
    public int assignBatch(List<String> sourceCategories,String productGroup) {
        if(sourceCategories==null||sourceCategories.isEmpty())throw new IllegalArgumentException("请先选择产品类目");
        if(sourceCategories.size()>500)throw new IllegalArgumentException("单次最多处理 500 个类目");
        String group=productGroup==null?"":productGroup.trim();
        if(!"UNASSIGNED".equals(group)&&!GROUPS.contains(group))throw new IllegalArgumentException("产品大类无效");
        List<String> names=sourceCategories.stream().map(s->s==null?"":s.trim()).distinct().toList();
        if(names.stream().anyMatch(s->s.isEmpty()||s.length()>150))throw new IllegalArgumentException("存在无效的产品类目");
        Set<String> known=categories().stream().map(r->String.valueOf(r.get("sourceCategory"))).collect(Collectors.toSet());
        if(!known.containsAll(names))throw new IllegalArgumentException("部分产品类目已不在当前台账中，请刷新后重试");
        if("UNASSIGNED".equals(group)) {
            jdbc.batchUpdate("DELETE FROM h3_product_category_groups WHERE source_category=?",
                    names.stream().map(s->new Object[]{s}).toList());
        } else {
            jdbc.batchUpdate("INSERT INTO h3_product_category_groups(source_category,product_group) VALUES(?,?)"+
                    " ON DUPLICATE KEY UPDATE product_group=VALUES(product_group)",
                    names.stream().map(s->new Object[]{s,group}).toList());
        }
        return names.size();
    }
}
