package com.liqiang.archive.controller;

import com.liqiang.archive.erp.ErpActorResolver;
import com.liqiang.archive.erp.ErpChangeLogWriter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 「任务单产品与配置」定位变更后的契约（2026-10-21 LQ 定）。
 *
 * <p>定位：两类配置都是氚云生产任务单下拉字典的<b>镜像</b>——
 * {@code PRODUCT_NAME} ← 「产品名称」下拉项，{@code OPTIONAL_CONFIG} ← 「可选配置」下拉项。
 * 能不能被选中由氚云决定，LQXM 既不重复建设主数据，也不保留「启用/停用」：
 * 否则会出现「氚云照常在用、这边被停用」的假状态，而页面上不会有任何提示。
 *
 * <p>本测试钉死几条容易被「照抄旧写法」带回来的东西：
 * <ol>
 *   <li>产品匹配（覆盖率 productMatched / benchmarkMatched、跟单参考工期、产品级基准矩阵）
 *       都不许再判 {@code active}；</li>
 *   <li>列表查询不许再 select {@code active}（V400 已删列，留着会在运行期报 Unknown column）；</li>
 *   <li>不存在改名端点：名称是源字典的镜像，本地改名只断自己的匹配；纠错路径只有「删除后重录」；</li>
 *   <li>新增只写身份列（名称 / 车间 / 类型），不再写 {@code active}；</li>
 *   <li>删除只对本地补录（{@code MANUAL}）行开放：氚云字典镜像行（{@code H3YUN}）没被用过也不许删；
 *       MANUAL 行再受使用证据硬保护：产品看基准行 + 历史任务，可选配置看历史任务文本。</li>
 * </ol>
 */
class ManufacturingTaskConfigControllerTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final ErpChangeLogWriter changeLog=mock(ErpChangeLogWriter.class);
    private final ErpActorResolver actors=mock(ErpActorResolver.class);
    private final ManufacturingTaskConfigController controller=new ManufacturingTaskConfigController(jdbc,changeLog,actors);

    private static Map<String,Object> row(Object... kv) {
        var m=new LinkedHashMap<String,Object>();
        for(int i=0;i<kv.length;i+=2)m.put((String)kv[i],kv[i+1]);
        return m;
    }

    private static String controllerSource() throws IOException {
        return Files.readString(Path.of("src","main","java","com","liqiang","archive","controller",
                "ManufacturingTaskConfigController.java"), StandardCharsets.UTF_8);
    }

    @Test
    void durationCoverageCountsProductsWithoutActiveFlag() {
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        when(jdbc.queryForMap(sql.capture())).thenReturn(new LinkedHashMap<String,Object>());

        controller.durationCoverage();

        String value=sql.getValue();
        assertTrue(value.contains("productMatched")&&value.contains("benchmarkMatched"),
                "覆盖率必须仍然返回两个命中数："+value);
        assertFalse(value.matches("(?s).*\\bc\\s*\\.\\s*active.*"),
                "覆盖率匹配产品时不许再判 active —— 停用开关会让本地跟单匹配凭空失效："+value);
        // 唯一匹配（2026-10-21 复查 P1）：覆盖率必须与 benchmarksForOrder 的 items.size()==1 同判据，
        // 重名歧义行不算已匹配，否则「统计说匹配、详情用车间基准」两处口径漂移。
        assertTrue(value.contains("=1) AS productMatched"),
                "productMatched 必须用「恰好一行配置」的唯一匹配判据："+value);
        assertTrue(value.contains("=1 "
                        +"AND EXISTS(SELECT 1 FROM manufacturing_task_config_items"),
                "benchmarkMatched 必须先唯一匹配、再要求该产品已发布间隔："+value);
    }

    /**
     * 覆盖率下钻：必须按「车间 + 产品名」分组，并带回 configCount（唯一匹配判据）与 configId（点行进产品）。
     * 不能改成一个只返回汇总的数——页面统计数字点开就是要看「哪些产品」，没有分组行就等于没下钻。
     */
    @Test
    void durationCoverageBreakdownGroupsByWorkshopAndProduct() {
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        when(jdbc.queryForList(sql.capture())).thenReturn(List.of());

        controller.durationCoverageBreakdown();

        String value=sql.getValue();
        assertTrue(value.contains("GROUP BY p.production_line,p.product_name"),
                "下钻明细必须按车间+产品名分组，否则点开只有一个总数："+value);
        assertTrue(value.contains("AS configCount")&&value.contains("AS configId"),
                "必须带回 configCount（唯一匹配）与 configId（点行进产品）："+value);
        assertTrue(value.contains("AS publishedStages"),
                "必须带回该产品已发布节点数，才能筛『未命中已发布间隔』："+value);
    }

    /** 跟单侧：产品级参考工期与基准矩阵都按「车间 + 产品名」精确匹配，不看启用状态。 */
    @Test
    void trackingServiceNeverFiltersProductsByActive() throws IOException {
        String source=Files.readString(
                Path.of("src","main","java","com","liqiang","archive","service","ProductionTrackingService.java"),
                StandardCharsets.UTF_8);
        // 该文件里别名 c 只用于 manufacturing_task_config_items 与 production_order_components，
        // 后者库里没有 active 列 ⇒ 出现 c.active 必然是给产品配置加了启用过滤。
        assertFalse(source.matches("(?s).*\\bc\\s*\\.\\s*active.*"),
                "ProductionTrackingService 不许再用 c.active 过滤产品（产品没有启用概念）");
    }

    /** V400 删列后，列表再 select c.active 会直接报 Unknown column —— 这条挡住「回滚到旧 SQL」。 */
    @Test
    void listDoesNotSelectActiveColumn() {
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        when(jdbc.queryForList(sql.capture())).thenReturn(List.of());

        controller.list();

        assertFalse(sql.getValue().matches("(?s).*\\bc\\s*\\.\\s*active.*"),
                "列表不许再 select active（V400 已删列）："+sql.getValue());
    }

    /**
     * 改名端点整体下线：产品名与可选配置名都是源字典文本。
     * 产品改名 → {@code production_orders.product_name} 全量失配；可选配置改名 → 历史任务
     * {@code optional_configuration} 文本（按需工序判定认「氧化 / 木箱」）与本字典脱钩。
     */
    @Test
    void thereIsNoRenameEndpointForEitherConfigType() throws IOException {
        String source=controllerSource();
        assertFalse(source.contains("@PutMapping(\"/task-configs"),
                "不许再加回改名端点：两类配置名都是氚云下拉字典的镜像");
        assertFalse(source.contains("UPDATE manufacturing_task_config_items"),
                "配置行没有可 UPDATE 的字段（名称只读、active 列已删）：纠错只能删除后重录");
    }

    @Test
    void createWritesOnlyIdentityColumnsWithoutActive() {
        when(jdbc.queryForList(contains("FROM manufacturing_workshops WHERE id=? AND enabled=1"),any(Object[].class)))
                .thenReturn(List.of(row("workshop_name","封口机车间")));
        when(jdbc.queryForObject(contains("SELECT COUNT(*) FROM manufacturing_task_config_items WHERE workshop_id"),
                eq(Integer.class),any(Object[].class))).thenReturn(0);
        when(jdbc.queryForObject(contains("SELECT id FROM manufacturing_task_config_items WHERE external_source='MANUAL'"),
                eq(Long.class),any(Object[].class))).thenReturn(77L);

        controller.create(new ManufacturingTaskConfigController.ConfigCreate("灌装(量杯式)",2L,"OPTIONAL_CONFIG"));

        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        var args=org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(),args.capture());
        assertFalse(sql.getValue().contains("active"),
                "新增不许再写 active（V400 已删列，两类配置都没有启用概念）："+sql.getValue());
        assertEquals(4,args.getValue().length,"只写 externalId + 名称 + 车间 + 类型");
        assertEquals("灌装(量杯式)",args.getValue()[1]);
        assertEquals("OPTIONAL_CONFIG",args.getValue()[3]);
    }

    @Test
    void deleteRejectsProductWithBenchmarkRows() {
        when(jdbc.queryForList(contains("FROM manufacturing_task_config_items WHERE id=? FOR UPDATE"),any(Object[].class)))
                .thenReturn(List.of(row("config_name","气动卷膜","config_type","PRODUCT_NAME","workshop_id",2L,"external_source","MANUAL")));
        when(jdbc.queryForObject(contains("FROM production_tracking_product_benchmarks"),eq(Integer.class),any(Object[].class)))
                .thenReturn(3);

        var ex=assertThrows(ResponseStatusException.class,()->controller.deleteConfig(9L));
        assertEquals(HttpStatus.CONFLICT,ex.getStatusCode());
        assertTrue(String.valueOf(ex.getReason()).contains("参考间隔"),
                "拒绝理由必须说清是参考间隔会导致丢失："+ex.getReason());
        verify(jdbc,never()).update(contains("DELETE FROM manufacturing_task_config_items"),any(Object[].class));
    }

    @Test
    void deleteRejectsProductMatchedByHistoryOrders() {
        when(jdbc.queryForList(contains("FROM manufacturing_task_config_items WHERE id=? FOR UPDATE"),any(Object[].class)))
                .thenReturn(List.of(row("config_name","气动卷膜","config_type","PRODUCT_NAME","workshop_id",2L,"external_source","MANUAL")));
        when(jdbc.queryForObject(contains("FROM production_tracking_product_benchmarks"),eq(Integer.class),any(Object[].class)))
                .thenReturn(0);
        when(jdbc.queryForObject(contains("FROM production_orders p JOIN manufacturing_workshops w"),eq(Integer.class),any(Object[].class)))
                .thenReturn(52);

        var ex=assertThrows(ResponseStatusException.class,()->controller.deleteConfig(9L));
        assertEquals(HttpStatus.CONFLICT,ex.getStatusCode());
        assertTrue(String.valueOf(ex.getReason()).contains("52"),
                "拒绝理由要带上匹配到的历史任务张数："+ex.getReason());
    }

    /** 产品两条使用证据都不占 → 必然是补录错了的行，放行。 */
    @Test
    void deleteRemovesOnlyOrphanProductRow() {
        when(jdbc.queryForList(contains("FROM manufacturing_task_config_items WHERE id=? FOR UPDATE"),any(Object[].class)))
                .thenReturn(List.of(row("config_name","敲错的名字","config_type","PRODUCT_NAME","workshop_id",2L,"external_source","MANUAL")));
        when(jdbc.queryForObject(anyString(),eq(Integer.class),any(Object[].class))).thenReturn(0);

        assertDoesNotThrow(()->controller.deleteConfig(9L));

        verify(jdbc).update(contains("DELETE FROM manufacturing_task_config_items WHERE id=?"),eq(9L));
        verify(changeLog).write(any());
    }

    /** 可选配置：历史上真被用过 → 拒绝（2026-10-21 实测 70 条里 67 条属于这一类）。 */
    @Test
    void deleteRejectsOptionalConfigUsedByHistoryOrders() {
        when(jdbc.queryForList(contains("FROM manufacturing_task_config_items WHERE id=? FOR UPDATE"),any(Object[].class)))
                .thenReturn(List.of(row("config_name","出口木箱","config_type","OPTIONAL_CONFIG","workshop_id",2L,"external_source","MANUAL")));
        when(jdbc.queryForObject(contains("LOCATE"),eq(Integer.class),any(Object[].class))).thenReturn(96);

        var ex=assertThrows(ResponseStatusException.class,()->controller.deleteConfig(9L));
        assertEquals(HttpStatus.CONFLICT,ex.getStatusCode());
        assertTrue(String.valueOf(ex.getReason()).contains("96"),
                "拒绝理由要带上被用过的任务张数："+ex.getReason());
        verify(jdbc,never()).update(contains("DELETE FROM manufacturing_task_config_items"),any(Object[].class));
    }

    @Test
    void deleteAllowsOptionalConfigNeverUsed() {
        when(jdbc.queryForList(contains("FROM manufacturing_task_config_items WHERE id=? FOR UPDATE"),any(Object[].class)))
                .thenReturn(List.of(row("config_name","敲错的选项","config_type","OPTIONAL_CONFIG","workshop_id",2L,"external_source","MANUAL")));
        when(jdbc.queryForObject(contains("LOCATE"),eq(Integer.class),any(Object[].class))).thenReturn(0);

        assertDoesNotThrow(()->controller.deleteConfig(9L));
        verify(jdbc).update(contains("DELETE FROM manufacturing_task_config_items WHERE id=?"),eq(9L));
    }

    /**
    /**
     * 来源闸（2026-10-21 复查 P0）：H3YUN 字典镜像行即使「没有历史任务、没有基准、没被用过」也不许删
     * ——没被用过 ≠ 氚云侧不存在，可能是新增还没人下单的项；氚云移除后随导入同步消失，本地不替氚云做主。
     */
    @Test
    void deleteRejectsH3yunDictionaryRowsEvenWithoutAnyUsage() {
        when(jdbc.queryForList(contains("FROM manufacturing_task_config_items WHERE id=? FOR UPDATE"),any(Object[].class)))
                .thenReturn(List.of(row("config_name","全新产品还没下过单","config_type","PRODUCT_NAME","workshop_id",2L,"external_source","H3YUN")));

        var ex=assertThrows(ResponseStatusException.class,()->controller.deleteConfig(9L));
        assertEquals(HttpStatus.CONFLICT,ex.getStatusCode());
        assertTrue(String.valueOf(ex.getReason()).contains("氚云"),
                "拒绝理由必须点明来源是氚云字典镜像："+ex.getReason());
        verify(jdbc,never()).update(contains("DELETE FROM manufacturing_task_config_items"),any(Object[].class));
        verify(changeLog,never()).write(any());
    }

    @Test
    void deleteRejectsH3yunOptionalConfigRowToo() {
        when(jdbc.queryForList(contains("FROM manufacturing_task_config_items WHERE id=? FOR UPDATE"),any(Object[].class)))
                .thenReturn(List.of(row("config_name","新选项","config_type","OPTIONAL_CONFIG","workshop_id",2L,"external_source","H3YUN")));

        var ex=assertThrows(ResponseStatusException.class,()->controller.deleteConfig(9L));
        assertEquals(HttpStatus.CONFLICT,ex.getStatusCode());
        verify(jdbc,never()).update(contains("DELETE FROM manufacturing_task_config_items"),any(Object[].class));
    }

    /**
     * 可选配置与历史任务的关联是分号分隔的自由文本，只能整项匹配。
     * 用 LIKE 会把配置名里的 {@code _} 当单字符通配（「灌装_量杯式」也会命中），
     * 所以必须用 LOCATE + 两侧补分号；这条防的是「看起来等价、实际更宽」的退化。
     */
    @Test
    void optionalConfigUsageIsMatchedAsWholeToken() {
        when(jdbc.queryForList(contains("FROM manufacturing_task_config_items WHERE id=? FOR UPDATE"),any(Object[].class)))
                .thenReturn(List.of(row("config_name","灌装(量杯式)","config_type","OPTIONAL_CONFIG","workshop_id",2L,"external_source","MANUAL")));
        when(jdbc.queryForObject(anyString(),eq(Integer.class),any(Object[].class))).thenReturn(0);

        controller.deleteConfig(9L);

        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(sql.capture(),eq(Integer.class),any(Object[].class));
        String value=sql.getValue();
        assertTrue(value.contains("LOCATE(")&&value.contains("CONCAT(';',?,';')"),
                "必须整项匹配（两侧补分号），不能退化成 LIKE 子串："+value);
        assertFalse(value.contains("LIKE"),"不许用 LIKE —— 配置名里的 _ 会被当通配符放宽匹配："+value);
    }
}
