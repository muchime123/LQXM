package com.liqiang.archive.service;

import java.util.List;

/**
 * 车间类别相关的跟单口径：节点序列、默认跟单员、可见范围、完成判据的 SQL 片段。
 *
 * <p><b>节点序列不再写在这里</b>——唯一源已搬到 {@code production_tracking_stage_defs} 表
 * （经 {@link ProductionTrackingStageCatalog} 快照读取），由「生产参考工期」页的制程管理维护。
 * 本类只保留「车间名 → 车间类别」的判定与各类 SQL 片段的拼装，节点相关的取值一律走快照，
 * 快照未加载时回落到 {@link ProductionTrackingStageCatalog#builtIn()}。
 */
final class ProductionTrackingPolicy {
    /** 跟单起点：九月份开始正式跟单；更早的任务只有被进度表覆盖或已有跟单记录时才可见。 */
    static final String SCOPE_START_DATE="2026-09-01";
    static final String WOODEN_CRATE="WOODEN_CRATE";
    static final String OXIDATION="OXIDATION";
    /** 物料配齐：组装前的齐套确认，2026-10-27 新增；具体顺序与名称以制程表为准。 */
    static final String MATERIAL_READY="MATERIAL_READY";
    static final String SEALER_KIND=ProductionTrackingStageCatalog.SEALER;
    static final String MACHINE_KIND=ProductionTrackingStageCatalog.MACHINE;
    static final String SEALER_DEFAULT_ASSIGNEE="陈苏平";
    static final String MACHINE_DEFAULT_ASSIGNEE="胡健";

    private ProductionTrackingPolicy() {}

    static boolean isSealer(String workshop) {return workshop!=null&&workshop.contains("封口");}
    /** 车间名 → 车间类别；制程按类别配置，不按车间（见 V420 表注释）。 */
    static String kindOf(String workshop) {return isSealer(workshop)?SEALER_KIND:MACHINE_KIND;}

    /** 全部节点（含按需工序），按制程顺序；顺序即节点条展示顺序与链式推算顺序。 */
    static List<String> stageCodes(String workshop) {
        return ProductionTrackingStageCatalog.enabled(kindOf(workshop)).stream()
                .map(ProductionTrackingStageCatalog.StageDef::code).toList();
    }
    /** 必经节点＝全部节点去掉按需工序；「跟单完成」只看必经节点。 */
    static List<String> requiredStages(String workshop) {
        return ProductionTrackingStageCatalog.enabled(kindOf(workshop)).stream()
                .filter(def->!def.optional()).map(ProductionTrackingStageCatalog.StageDef::code).toList();
    }
    /**
     * 是否按需工序（不传车间）：任一类别的启用定义中该节点为按需即算。
     * 已知节点在两类别下的按需属性一致（氧化只挂封口机、木箱两边都是按需）；
     * 有车间上下文时请用 {@link #isOptionalStage(String, String)} 取精确值。
     */
    static boolean isOptionalStage(String code) {
        for(var defs:ProductionTrackingStageCatalog.snapshot().values())
            for(var def:defs)
                if(def.enabled()&&def.code().equals(code))return def.optional();
        return false;
    }
    static boolean isOptionalStage(String code,String workshop) {
        return ProductionTrackingStageCatalog.enabled(kindOf(workshop)).stream()
                .anyMatch(def->def.code().equals(code)&&def.optional());
    }
    /** 可选配置或任务单备注写明需要该按需工序时，即使尚无跟单记录也应出现该节点。 */
    static boolean configuredOptionalStage(String code,String configuration) {
        if(code==null||configuration==null||configuration.isBlank())return false;
        var def=defOfAnyKind(code);
        return def!=null&&def.optional()&&def.indicatedBy(configuration);
    }
    /** 可选配置与任务单备注分别判断，避免一处写明需要、另一处提到不需要时互相覆盖。 */
    static boolean configuredOptionalStage(String code,String configuration,String remark) {
        return configuredOptionalStage(code,configuration)||configuredOptionalStage(code,remark);
    }
    /** 展示名；制程表里查不到时退回编码，避免拼出「null」。 */
    static String stageLabel(String code,boolean sealer) {
        var def=defOf(code,sealer?SEALER_KIND:MACHINE_KIND);
        return def==null?code:def.label();
    }
    /** 表头文字 → 节点码：先去掉空白/换行与括号补充说明（如「（要求时间达不到时填写）」）。不区分车间。 */
    static String stageCodeOfLabel(String label) {
        if(label==null)return null;
        String key=headerKey(label);
        for(var defs:ProductionTrackingStageCatalog.snapshot().values())
            for(var def:defs)
                if(def.enabled()&&(key.equals(headerKey(def.label()))||def.aliases().contains(key)))
                    return def.code();
        return null;
    }
    /** 表头文字 → 节点码（按车间类别精确匹配）；认 label 与别名两种写法。 */
    static String stageCodeOfLabel(String label,String workshop) {
        if(label==null)return null;
        String key=headerKey(label);
        for(var def:ProductionTrackingStageCatalog.enabled(kindOf(workshop)))
            if(key.equals(headerKey(def.label()))||def.aliases().contains(key))return def.code();
        return null;
    }
    private static String headerKey(String label) {
        return label.replaceAll("[\\s\\u3000]","").replaceAll("[（(][^）)]*[）)]","");
    }
    private static ProductionTrackingStageCatalog.StageDef defOf(String code,String kind) {
        return ProductionTrackingStageCatalog.enabled(kind).stream()
                .filter(def->def.code().equals(code)).findFirst().orElse(null);
    }
    private static ProductionTrackingStageCatalog.StageDef defOfAnyKind(String code) {
        for(var defs:ProductionTrackingStageCatalog.snapshot().values())
            for(var def:defs)
                if(def.enabled()&&def.code().equals(code))return def;
        return null;
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
    /** 车间类别的 SQL 表达式：与 {@link #kindOf} 同一个判定，SQL 侧只能用车间名字符串判。 */
    static String kindCase(String productionLineColumn) {
        return "CASE WHEN "+sealerCondition(productionLineColumn)+" THEN '"+SEALER_KIND
                +"' ELSE '"+MACHINE_KIND+"' END";
    }
    /**
     * 完成判据的分母＝本车间类别的**启用且必经**节点数，直接查制程表。
     *
     * <p>原先这里是写死的数字（封口机 4 / 机器 7），加一个节点就要改常量；改成子查询后
     * 管理员在页面上加节点，分母自动跟随。制程表十几行、有索引，标量子查询的开销可忽略。
     */
    static String stageTotalCase(String productionLineColumn) {
        return "(SELECT COUNT(*) FROM production_tracking_stage_defs d WHERE d.enabled=1 AND d.optional=0 "
                +"AND d.workshop_kind="+kindCase(productionLineColumn)+")";
    }
}
