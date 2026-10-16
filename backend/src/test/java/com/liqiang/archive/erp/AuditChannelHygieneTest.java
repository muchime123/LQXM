package com.liqiang.archive.erp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审计通道门禁（2026-09-21 确立）。
 *
 * <p><b>为什么需要它</b>：审计实体名撞名此前反复出现，根因不是「名字起得不好」，
 * 而是**同一个业务动作写了两份审计**——
 * <ul>
 *   <li>强身份：{@code erp.row_change_log}，身份 = {@code target_table}（schema 限定表名，物理唯一）</li>
 *   <li>弱身份：{@code audit_logs}，身份 = {@code entity_type}（自由字符串，一个字段同时扛
 *       「哪张表」和「什么业务对象」）</li>
 * </ul>
 * 两个模块对同名概念用同一个 {@code entity_type} 就撞，而且**撞了不报错**：
 * 实测 {@code audit_logs} 97,846 行 / 48 个 {@code entity_type} 里，
 * {@code SalesOutbound} 这个名下同时躺着氚云台账行归类动作（660 行，id 量级 10 万+）
 * 与 ERP 自建出库单（id 从 1 起）—— 两套 id 空间，审计时无法区分。
 *
 * <p><b>方案 A</b>：ERP 自建业务**不再写 {@code audit_logs}**，审计统一走
 * {@code row_change_log}；前端「操作日志」页并列两个数据源。
 * 详见 {@code docs/审计实体身份治理与撞名防治-20260921.md}。
 *
 * <p>本测试把这条纪律钉死，防止新模块「照抄旧写法」把它带回来。
 */
class AuditChannelHygieneTest {

    /** 源码根：Maven surefire 的工作目录是模块目录（backend/）。 */
    private static final Path MAIN_JAVA = Paths.get("src", "main", "java");

    /**
     * 已完成「审计单通道」改造的 ERP 活跃类。
     *
     * <p>新增 ERP 业务类后应加入本清单 —— 否则它可能在无人察觉的情况下写回 {@code audit_logs}。
     */
    private static final List<String> ERP_SINGLE_CHANNEL_CLASSES = List.of(
            "com/liqiang/archive/service/ManufacturingExecutionService.java",
            "com/liqiang/archive/controller/ManufacturingTaskConfigController.java"
    );

    private static final List<String> ZOMBIE_EXEMPT_CLASSES = List.of();

    /** {@code audit_logs.entity_type} 是 varchar(30)，且库为 STRICT 模式 → 超长直接报错。 */
    private static final int AUDIT_ENTITY_TYPE_MAX = 30;

    /** {@code row_change_log.action} 是 varchar(20)。 */
    private static final int CHANGE_LOG_ACTION_MAX = 20;

    // ==================== 1. ERP 活跃业务不得写 audit_logs ====================

    @Test
    void erpBusinessClassesDoNotWriteAuditLogs() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String relative : ERP_SINGLE_CHANNEL_CLASSES) {
            String source = read(relative);
            if (source.contains("AuditLogService")) {
                violations.add(relative + " → 仍依赖 AuditLogService（ERP 审计应只走 ErpChangeLogWriter）");
            }
            if (source.contains("audit.log(")) {
                violations.add(relative + " → 仍调用 audit.log(...)（应改为 changeLog.write(...)）");
            }
        }
        assertTrue(violations.isEmpty(),
                "ERP 自建业务必须审计单通道（方案 A），以下类违规：\n  " + String.join("\n  ", violations));
    }

    /**
     * 豁免清单不得腐化：僵尸域一旦被清理（不再依赖 {@code AuditLogService}），
     * 本条会失败，提示把对应类从豁免清单移走。
     */
    @Test
    void zombieExemptionsStillNeededOrShouldBeDropped() throws IOException {
        List<String> stale = new ArrayList<>();
        for (String relative : ZOMBIE_EXEMPT_CLASSES) {
            Path path = MAIN_JAVA.resolve(relative);
            if (!Files.exists(path)) {
                stale.add(relative + " → 文件已不存在，请从豁免清单移除");
                continue;
            }
            if (!read(relative).contains("AuditLogService")) {
                stale.add(relative + " → 已不再依赖 AuditLogService，请从豁免清单移除");
            }
        }
        assertTrue(stale.isEmpty(),
                "僵尸域豁免清单已过期，请更新 AuditChannelHygieneTest：\n  " + String.join("\n  ", stale));
    }

    // ==================== 2. target_table 必须带 schema 限定名 ====================

    /**
     * {@code target_table} 统一带 schema 限定名（决策 #20②）。
     * 两侧表可能重名，裸表名会让「改了哪张表」产生歧义 —— 而歧义正是本次撞名问题的同一病根。
     */
    @Test
    void everyChangeLogTargetTableIsSchemaQualified() throws IOException {
        Pattern call = Pattern.compile("ErpChangeLogWriter\\.of\\(\\s*[^,()]+,\\s*\"([^\"]+)\"");
        List<String> violations = new ArrayList<>();
        int checked = 0;

        for (Path file : javaSources()) {
            String source = stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher m = call.matcher(source);
            while (m.find()) {
                checked++;
                String targetTable = m.group(1);
                if (!targetTable.matches("[A-Za-z0-9_]+\\.[A-Za-z0-9_]+")) {
                    violations.add(file.getFileName() + " → target_table=\"" + targetTable
                            + "\"（缺少 schema 限定名或含非法字符）");
                }
                if (targetTable.length() > 64) {
                    violations.add(file.getFileName() + " → target_table 超过 64 字符：" + targetTable);
                }
            }
        }

        assertTrue(checked > 0, "未扫描到任何 ErpChangeLogWriter.of(...) 调用 —— 正则或路径已失效，本测试变成空转");
        assertTrue(violations.isEmpty(),
                "row_change_log.target_table 必须为 schema 限定表名：\n  " + String.join("\n  ", violations));
    }

    // ==================== 3. 长度硬上限 ====================

    /**
     * {@code audit_logs.entity_type} = varchar(30) 且库为 STRICT 模式 → 超长**直接报错**（非静默截断）。
     * 这条上限正是「靠加长前缀避免撞名」不可行的原因
     * （反例：{@code ErpManufacturingMaterialRequirement} = 35 字符，会插入失败）。
     *
     * <p>ERP 已不再写该表，本断言守的是氚云治理侧仍在用的那些实体名 —— 防止将来有人
     * 顺手起一个超长名字，直到运行时才炸。
     */
    @Test
    void auditEntityTypeLiteralsRespectColumnWidth() throws IOException {
        Pattern literal = Pattern.compile("audit\\.log\\(\\s*\"([^\"]+)\"");
        List<String> violations = new ArrayList<>();
        int checked = 0;

        for (Path file : javaSources()) {
            if (isZombieExempt(file)) continue;
            String source = stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher m = literal.matcher(source);
            while (m.find()) {
                checked++;
                String entityType = m.group(1);
                if (entityType.length() > AUDIT_ENTITY_TYPE_MAX) {
                    violations.add(file.getFileName() + " → entity_type=\"" + entityType
                            + "\" 长度 " + entityType.length() + " > " + AUDIT_ENTITY_TYPE_MAX);
                }
            }
        }

        assertTrue(checked > 0, "未扫描到任何 audit.log(\"...\") 字面量 —— 正则或路径已失效，本测试变成空转");
        assertTrue(violations.isEmpty(),
                "audit_logs.entity_type 为 varchar(30) 且 STRICT，超长会直接报错：\n  "
                        + String.join("\n  ", violations));
    }

    /** 文件是否落在僵尸域豁免清单内（材料 / 领料段）。 */
    private static boolean isZombieExempt(Path file) {
        String normalized = file.toString().replace('\\', '/');
        return ZOMBIE_EXEMPT_CLASSES.stream().anyMatch(normalized::endsWith);
    }

    /** 同理守 {@code row_change_log.action} 的 varchar(20) —— 只检查纯字面量，表达式跳过。 */
    @Test
    void changeLogActionLiteralsRespectColumnWidth() throws IOException {
        Pattern call = Pattern.compile(
                "ErpChangeLogWriter\\.of\\([^;]{0,600}?,\\s*\"([A-Za-z_]+)\"\\s*,");
        List<String> violations = new ArrayList<>();
        int checked = 0;

        for (Path file : javaSources()) {
            String source = stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher m = call.matcher(source);
            while (m.find()) {
                checked++;
                String action = m.group(1);
                if (action.length() > CHANGE_LOG_ACTION_MAX) {
                    violations.add(file.getFileName() + " → action=\"" + action
                            + "\" 长度 " + action.length() + " > " + CHANGE_LOG_ACTION_MAX);
                }
            }
        }

        assertTrue(checked > 0, "未扫描到纯字面量 action —— 若全部改为三元表达式，本测试可移除");
        assertTrue(violations.isEmpty(),
                "row_change_log.action 为 varchar(20)：\n  " + String.join("\n  ", violations));
    }

    // ==================== 工具 ====================

    private static String read(String relative) throws IOException {
        Path path = MAIN_JAVA.resolve(relative);
        assertTrue(Files.exists(path), "找不到源文件，测试路径假设已失效：" + path.toAbsolutePath());
        return stripComments(Files.readString(path, StandardCharsets.UTF_8));
    }

    /**
     * 去掉注释后再判断「是否引用了某个符号」。
     *
     * <p><b>不加这一步会自伤</b>：单通道改造会在被改造的类里留下说明性注释
     * （如 {@code 勿加回 AuditLogService}），纯子串匹配会把注释当成真实依赖 →
     * 门禁对自己的改造结果报假失败（2026-09-21 实证）。
     *
     * <p>已知局限：字符串字面量里的 {@code //}（如 URL）会被当成注释起点截断。
     * 本类只用于查依赖形态、表名与列宽，不受影响。
     */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
    }

    private static List<Path> javaSources() throws IOException {
        assertTrue(Files.exists(MAIN_JAVA), "找不到源码根目录：" + MAIN_JAVA.toAbsolutePath());
        try (Stream<Path> walk = Files.walk(MAIN_JAVA)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }
}
