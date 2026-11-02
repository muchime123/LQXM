package com.liqiang.archive.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 节点「适用性 + 完成来源」三列的<b>入库与审计契约</b>（P1-a 阶段：先立地基，暂不开写入通道）。
 *
 * <p>【为什么要有这类跨文件的字符串契约】生产跟单里「节点到底做没做完」和「有没有可信完成日期」
 * 长期被 {@code completed_date} 一个字段表达，于是「做完了但没人登记日期」只能表现为「未完成」。
 * 修这个问题靠三件事同时成立，缺一件就会退化成本来的样子：
 * <ol>
 *   <li>人工确认的适用性必须<b>持久化</b>，否则氚云重新导入就按源单关键词重算掉；</li>
 *   <li>写节点的 UPSERT <b>不得</b>覆盖这三列，否则人工确认同样会被导入冲掉；</li>
 *   <li>凡是进 {@code before_json / after_json} 的里程碑快照都要带上新列，
 *       否则「谁在什么时候把木箱确认成不适用」审计里查不到。</li>
 * </ol>
 * 这三件事分散在迁移脚本与两个服务里，单看任一处都发现不了缺失，故在此统一钉死。
 *
 * <p>DONE 无真实日期只能经人工明确标记 NO_DATE 并写依据；节点写入与该列同批发布。
 */
class ProductionTrackingNodeApplicabilitySchemaTest {

    private static final String TRACKING_SERVICE = "com/liqiang/archive/service/ProductionTrackingService.java";
    private static final String REVIEW_SERVICE = "com/liqiang/archive/service/ProductionTrackingWorkbookReviewService.java";
    private static final String MIGRATION = "db/migration/V429__tracking_node_applicability.sql";

    @Test
    void migrationAddsApplicabilityAndCompletionSourceColumns() {
        // 注释里会提到「为什么不用 manual_reference_date」，断言必须只看 DDL 正文，否则会被自己的说明文字打脸。
        String sql = stripComments(read(MIGRATION));
        assertTrue(sql.contains("ADD COLUMN applicability"), "必须持久化「人工确认的适用性」：" + MIGRATION);
        assertTrue(sql.contains("ADD COLUMN applicability_note"), "适用性变更必须能写明原因与依据：" + MIGRATION);
        assertTrue(sql.contains("ADD COLUMN completion_source"), "必须能区分「已完成无日期」与「无法确认」：" + MIGRATION);
        assertTrue(sql.contains("COMMENT '确认或变更适用性的原因与依据'"), "新列必须自带注释，避免后人按猜测使用：" + MIGRATION);
        // 当初评估过「顺手复用 manual_reference_date」，它语义是前瞻预计且参与交期压力派生，方向相反。
        assertFalse(sql.contains("manual_reference_date"), "不得借用 manual_reference_date 存结论日期：" + sql);
        assertFalse(sql.contains("reference_date"), "推断日期本阶段不落库，界面实时计算即可：" + sql);
    }

    @Test
    void nodeUpsertNeverOverwritesApplicability() {
        String source = read(TRACKING_SERVICE);
        int at = source.indexOf("ON DUPLICATE KEY UPDATE stage_status=VALUES(stage_status)");
        assertTrue(at > 0, "找不到节点写入的 UPSERT — 契约失效，请同步更新本用例");
        // 只看到这条 UPDATE 语句的结尾为止，别把后面的无关代码算进来。
        int end = source.indexOf(';', at);
        String tail = source.substring(at, end > 0 ? Math.min(end, at + 600) : Math.min(at + 600, source.length()));
        assertFalse(tail.contains("applicability"),
                "节点 UPSERT 一旦带上适用性列，氚云重新导入就会按源单关键词重算、冲掉人工确认结果：" + tail);
        assertTrue(tail.contains("completion_source=VALUES(completion_source)"),
                "显式跟单保存必须持久化日期未知标记，不能只在页面上显示：" + tail);
    }

    @Test
    void bothWriteEntryPointsShareOnePermissionGate() {
        // 「节点适用性 / 完成来源」现在有两个入口：跟进抽屉（随时改单个节点）与结案弹窗（一次确认整单）。
        // 两个入口写的是同一个事实，就必须共用同一把权限钥匙 —— 否则只要抽屉那条不校验，
        // 结案弹窗收得再严也形同虚设：绕开它比遵守它更省事。
        String source = read(TRACKING_SERVICE);
        assertTrue(source.contains("private static final String PERMISSION_ABNORMAL_CLOSURE"),
                "异常结案权限必须收敛成一个常量，避免两个入口各写一份字符串、日后悄悄漂移：" + TRACKING_SERVICE);
        long gates = Pattern.compile("users\\.can\\(PERMISSION_ABNORMAL_CLOSURE\\)")
                .matcher(source).results().count();
        assertTrue(gates >= 2,
                "跟进抽屉与结案弹窗两个入口都要引用同一把权限钥匙，当前只有 " + gates + " 处：" + TRACKING_SERVICE);
    }

    @Test
    void everyMilestoneSnapshotCarriesNewColumns() {
        List<String> offenders = new ArrayList<>();
        int snapshots = 0;
        for (String file : List.of(TRACKING_SERVICE, REVIEW_SERVICE)) {
            for (String statement : read(file).split(";")) {
                if (!statement.contains("ORDER BY stage_code") || !statement.contains("production_tracking_milestones")) continue;
                snapshots++;
                if (!statement.contains("completion_source")) offenders.add(file + " → " + brief(statement));
            }
        }
        assertTrue(snapshots >= 4,
                "健全性断言：必须真的抓到多条里程碑快照，否则本用例会「一个都没匹配 = 通过」变成假绿灯。当前抓到 " + snapshots + " 条");
        assertTrue(offenders.isEmpty(),
                "审计快照里的里程碑必须带适用性三列，否则结案/复核改了什么查不到：" + String.join(" | ", offenders));
    }

    @Test
    void durationSamplesStillRequireRealDates() {
        String source = read(TRACKING_SERVICE);
        assertTrue(source.contains("if(r.getString(5)!=null&&completed!=null)"),
                "P50/P75 样本必须继续排除「DONE 但无完成日期」的行 —— 这是允许已完成无日期的前提，丢了就会污染工期：" + source);
    }

    @Test
    void doneWithoutDateRequiresExplicitSource() {
        String source = read(TRACKING_SERVICE);
        assertTrue(source.contains("\"NO_DATE\".equals(milestone.completionSource())"),
                "DONE 无日期必须有 NO_DATE 来源标记：" + source);
    }

    private static String brief(String statement) {
        String one = statement.replaceAll("\\s+", " ").trim();
        return one.length() > 200 ? one.substring(0, 200) + "…" : one;
    }

    /** 去掉整行的 SQL 注释，让断言只看 DDL 正文。 */
    private static String stripComments(String sql) {
        return Pattern.compile("(?m)^\\s*--.*$").matcher(sql).replaceAll("");
    }

    private static String read(String relative) {
        for (Path base : List.of(Path.of("src/main/resources"), Path.of("backend/src/main/resources"))) {
            Path candidate = base.resolve(relative);
            if (Files.isRegularFile(candidate)) return readPath(candidate);
        }
        for (Path base : List.of(Path.of("src/main/java"), Path.of("backend/src/main/java"))) {
            Path candidate = base.resolve(relative);
            if (Files.isRegularFile(candidate)) return readPath(candidate);
        }
        throw new IllegalStateException("找不到源文件，工作目录可能已变：" + relative);
    }

    private static String readPath(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取失败：" + file, e);
        }
    }
}
