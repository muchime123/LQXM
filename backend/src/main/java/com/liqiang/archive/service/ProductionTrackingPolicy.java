package com.liqiang.archive.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Single source for workshop-specific follow-up stages, default assignees and tracking scope. */
final class ProductionTrackingPolicy {
    /** 跟单起点：九月份开始正式跟单；更早的任务只有被进度表覆盖或已有跟单记录时才可见。 */
    static final String SCOPE_START_DATE="2026-09-01";
    static final String WOODEN_CRATE="WOODEN_CRATE";
    static final List<String> MACHINE_STAGES=List.of("DRAWING","MATERIAL","SHEET_METAL","MOLD","ASSEMBLY","TESTING","WAREHOUSE",WOODEN_CRATE);
    /** 氧化在模具与组装之间；木箱在入库之后、发货之前，两者按工单配置或已有节点记录决定是否适用。 */
    static final String OXIDATION="OXIDATION";
    static final List<String> SEALER_STAGES=List.of("MATERIAL","MOLD",OXIDATION,"ASSEMBLY","WAREHOUSE",WOODEN_CRATE);
    /**
     * 按需工序：工单可选配置写明或已有跟单记录才适用；未选择的不计入进度分母。
     */
    static final Set<String> OPTIONAL_STAGES=Set.of(OXIDATION,WOODEN_CRATE);
    static final String SEALER_DEFAULT_ASSIGNEE="陈苏平";
    static final String MACHINE_DEFAULT_ASSIGNEE="胡健";
    private static final Map<String,String> STAGE_LABELS=Map.of(
            "DRAWING","画图","MATERIAL","备料","SHEET_METAL","钣金","MOLD","模具生产",
            OXIDATION,"氧化/喷特氟龙","ASSEMBLY","组装","TESTING","调试",WOODEN_CRATE,"木箱","WAREHOUSE","入库");
    /**
     * 车间进度表「阶段列」的表头写法 → 节点码。导入时按表头定位列，模板增删/移动阶段列也不会静默读错；
     * 注意机器车间写「模具」、封口机写「模具生产」，两种都收。
     */
    private static final Map<String,String> LABEL_STAGES=Map.ofEntries(
            Map.entry("画图","DRAWING"),Map.entry("备料","MATERIAL"),Map.entry("钣金","SHEET_METAL"),
            Map.entry("模具","MOLD"),Map.entry("模具生产","MOLD"),Map.entry("氧化/喷特氟龙",OXIDATION),
            Map.entry("组装","ASSEMBLY"),Map.entry("调试","TESTING"),Map.entry("木箱",WOODEN_CRATE),
            Map.entry("出口木箱",WOODEN_CRATE),Map.entry("入库","WAREHOUSE"));

    private ProductionTrackingPolicy() {}

    static boolean isSealer(String workshop) {return workshop!=null&&workshop.contains("封口");}
    /** 全部节点（含按需工序），用于节点条的展示与顺序。 */
    static List<String> stageCodes(String workshop) {return isSealer(workshop)?SEALER_STAGES:MACHINE_STAGES;}
    /** 必经节点＝全部节点去掉按需工序；「跟单完成」只看必经节点。 */
    static List<String> requiredStages(String workshop) {
        return stageCodes(workshop).stream().filter(code->!OPTIONAL_STAGES.contains(code)).toList();
    }
    static boolean isOptionalStage(String code) {return OPTIONAL_STAGES.contains(code);}
    /** 可选配置写明需要加工时，即使尚无跟单记录也应出现该节点；明确“不含/无需”不算。 */
    static boolean configuredOptionalStage(String code,String configuration) {
        if(configuration==null||configuration.isBlank())return false;
        String value=configuration.replaceAll("[\\s\\u3000]","");
        if(WOODEN_CRATE.equals(code))return value.contains("木箱")
                && !value.matches(".*(?:不含|无需|不用|不需要|无)木箱.*");
        if(OXIDATION.equals(code))return (value.contains("氧化")||value.contains("特氟龙")||value.contains("特富龙"))
                && !value.matches(".*(?:不含|无需|不用|不需要|无)(?:氧化|特氟龙|特富龙).*");
        return false;
    }
    /** 可选配置与任务单备注分别判断，避免一处写明需要、另一处提到不需要时互相覆盖。 */
    static boolean configuredOptionalStage(String code,String configuration,String remark) {
        return configuredOptionalStage(code,configuration)||configuredOptionalStage(code,remark);
    }
    static String stageLabel(String code,boolean sealer) {
        if("MOLD".equals(code)&&!sealer)return "模具";
        return STAGE_LABELS.get(code);
    }
    /** 表头文字 → 节点码：先去掉空白/换行与括号补充说明（如「（要求时间达不到时填写）」）。 */
    static String stageCodeOfLabel(String label) {
        if(label==null)return null;
        String key=label.replaceAll("[\\s\\u3000]","").replaceAll("[（(][^）)]*[）)]","");
        return LABEL_STAGES.get(key);
    }
    /**
     * 表格产品名/车间名的归一化键：去空白与标点、统一小写、去掉「车间」后缀。
     * 进度表匹配与「归档规则」都按这个键比对，两处必须是同一实现——
     * 否则会出现「手工归档了却挡不住下次导入」这类静默失效。
     */
    static String normalizeKey(String value) {
        return value==null?"":value.toLowerCase().replaceAll("[\\s\\-_/（）()]","").replace("车间","");
    }
    static String defaultAssigneeName(String workshop) {
        return isSealer(workshop)?SEALER_DEFAULT_ASSIGNEE:MACHINE_DEFAULT_ASSIGNEE;
    }
    static String sealerCondition(String productionLineColumn) {return productionLineColumn+" LIKE '%封口%'";}
    /** 完成判据的分母＝必经节点数（按需工序不计入），必须与 Service 的 done 计数口径一致。 */
    static String stageTotalCase(String productionLineColumn) {
        return "CASE WHEN "+sealerCondition(productionLineColumn)+" THEN "+requiredStages("封口机").size()
                +" ELSE "+requiredStages("全自动气调机").size()+" END";
    }
}
