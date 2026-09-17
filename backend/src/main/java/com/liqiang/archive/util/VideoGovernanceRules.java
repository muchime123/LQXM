package com.liqiang.archive.util;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 设备视频治理规则（单一事实源）：
 * 1. 内容状态受控词表与写入校验；
 * 2. 按内容类型分组的动态完整度要求——不同类型不强制同一组字段，
 *    例如运行演示需要用途+展示参数，而操作教学只需要操作环节。
 * 前端在 utils/catalog/videoCompleteness.ts 中镜像同一套规则，修改时必须两边同步。
 */
public final class VideoGovernanceRules {

    public static final Set<String> ALLOWED_STATUSES =
            Set.of("待处理", "待确认", "可用", "精选", "停用", "异常");

    /** 运行演示类：需要用途 + 展示参数 */
    public static final List<String> DEMO_TYPES = List.of("设备运行", "客户现场", "封口效果");
    /** 操作教学类：需要内容标签（操作环节/部位） */
    public static final List<String> HOWTO_TYPES = List.of("操作教学", "安装调试", "参数调整", "换模调机", "部件更换");
    /** 维护维修类：需要内容标签（故障/维护对象） */
    public static final List<String> MAINTENANCE_TYPES = List.of("清洁维护", "故障处理");
    /** 宣传成片类：需要使用渠道 */
    public static final List<String> PROMO_TYPES = List.of("宣传成片");
    /** 原始素材：仅要求基础项（类型+范围确认），不做额外字段要求 */
    public static final String RAW_TYPE = "原始素材";

    private VideoGovernanceRules() {
    }

    /** 校验并归一化内容状态；视频状态不允许为空。 */
    public static String validateContentStatus(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("视频内容状态不能为空");
        }
        String trimmed = value.trim();
        if (!ALLOWED_STATUSES.contains(trimmed)) {
            throw new IllegalArgumentException("不支持的视频内容状态: " + trimmed
                    + "，允许值：" + String.join("/", ALLOWED_STATUSES));
        }
        return trimmed;
    }

    /** 指定机型本身即表示范围明确；无机型时由人工确认标记表示产品通用。 */
    public static boolean effectiveScopeConfirmed(boolean scopeConfirmed, boolean hasMachineTiers) {
        return scopeConfirmed || hasMachineTiers;
    }

    /** 完整度缺失项（中文标签，用于展示）；规则与前端 videoCompleteness.ts 保持一致。 */
    public static List<String> missingCompletenessItems(String contentType, boolean scopeConfirmed,
                                                        String applicationTags, String contentTags,
                                                        String demonstrationTags, String usageChannels) {
        java.util.List<String> missing = new java.util.ArrayList<>();
        if (contentType == null || contentType.isBlank()) {
            missing.add("视频类型");
            return missing;
        }
        if (!scopeConfirmed) missing.add("适用范围确认");
        if (DEMO_TYPES.contains(contentType)) {
            if (isBlank(applicationTags)) missing.add("用途标签");
            if (isBlank(demonstrationTags)) missing.add("展示参数");
        } else if (HOWTO_TYPES.contains(contentType) || MAINTENANCE_TYPES.contains(contentType)) {
            if (isBlank(contentTags)) missing.add("内容标签");
        } else if (PROMO_TYPES.contains(contentType)) {
            if (isBlank(usageChannels)) missing.add("使用渠道");
        }
        // RAW 与未分组自定义类型：仅基础项
        return missing;
    }

    /** 是否完整（无缺失项）。 */
    public static boolean isComplete(String contentType, boolean scopeConfirmed,
                                     String applicationTags, String contentTags,
                                     String demonstrationTags, String usageChannels) {
        return missingCompletenessItems(contentType, scopeConfirmed, applicationTags, contentTags,
                demonstrationTags, usageChannels).isEmpty();
    }

    /** 返回当前记录允许保存到的状态；同状态保存始终允许。 */
    public static List<String> allowedNextStatuses(String currentStatus, boolean complete) {
        String current = currentStatus == null || currentStatus.isBlank() ? "待处理" : currentStatus;
        java.util.LinkedHashSet<String> allowed = new java.util.LinkedHashSet<>();
        allowed.add(current);
        allowed.add("异常");
        allowed.add("停用");
        switch (current) {
            case "待处理" -> allowed.add("待确认");
            case "待确认" -> { if (complete) allowed.add("可用"); }
            case "可用" -> { if (complete) allowed.add("精选"); }
            case "精选" -> allowed.add("可用");
            case "停用", "异常" -> allowed.add("待确认");
            default -> { }
        }
        return List.copyOf(allowed);
    }

    public static void validateTransition(String currentStatus, String nextStatus, boolean complete) {
        String next = validateContentStatus(nextStatus);
        if (("可用".equals(next) || "精选".equals(next)) && !complete) {
            throw new IllegalArgumentException("视频信息不完整，不能保持或设置为“" + next + "”");
        }
        if (!allowedNextStatuses(currentStatus, complete).contains(next)) {
            throw new IllegalArgumentException("不允许从“" + currentStatus + "”直接变更为“" + next + "”");
        }
    }

    /** 各状态语义说明（供接口文档/前端提示）。 */
    public static Map<String, String> statusDescriptions() {
        return Map.of(
                "待处理", "刚上传或待剪辑，尚未完成识别",
                "待确认", "系统已给出识别建议，等待人工确认",
                "可用", "人工确认完成，可供销售检索使用",
                "精选", "质量高、优先推荐",
                "停用", "保留文件但不再推荐",
                "异常", "文件缺失、画质差或无法播放"
        );
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
