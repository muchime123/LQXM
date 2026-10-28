package com.liqiang.archive.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 制程节点定义的**进程内快照**：节点序列、名称、是否按需工序的唯一运行时来源。
 *
 * <p>节点原先是 {@code ProductionTrackingPolicy} 里的 {@code static final} 常量，加一个节点要改常量 +
 * 写迁移 + 改测试 + 发版。落表（{@code production_tracking_stage_defs}）之后由「生产参考工期」页维护，
 * 这里只负责持有已加载的快照，供全进程读取。
 *
 * <p>【为什么是快照而不是每次查库】节点集合被拼进列表 / 导出 / 筛选 / 统计卡的 SQL 与日期推算，
 * 一次列表请求会读上百次；每次查库会让最热的一条查询多出一轮往返。表小且变更极低频，
 * 故采用「启动时加载 + 变更后整体替换」的不可变快照，读路径无锁。
 *
 * <p>【兜底】{@link #builtIn()} 是内置默认值，只在表还没加载（应用刚启动、查询失败）时生效，
 * 必须与迁移里的播种值一致——否则会出现「改了配置但重启前是另一套口径」的错觉。
 */
final class ProductionTrackingStageCatalog {
    static final String SEALER="SEALER";
    static final String MACHINE="MACHINE";

    /**
     * 单个节点定义。
     *
     * @param matchKeywords   按需工序的命中正则（可选配置 / 生产要求任一命中即本单适用）
     * @param excludeKeywords 按需工序的否定正则（命中则不适用），可为空
     * @param aliases         进度表表头别名（除 {@code label} 之外还要认的写法）
     */
    record StageDef(String kind,String code,String label,int sortNo,boolean optional,
                    String matchKeywords,String excludeKeywords,List<String> aliases,boolean enabled,
                    Pattern match,Pattern exclude) {
        StageDef(String kind,String code,String label,int sortNo,boolean optional,
                 String matchKeywords,String excludeKeywords,List<String> aliases,boolean enabled) {
            this(kind,code,label,sortNo,optional,matchKeywords,excludeKeywords,
                    aliases==null||aliases.isEmpty()?List.of():List.copyOf(aliases),enabled,
                    compile(matchKeywords),compile(excludeKeywords));
        }
        private static Pattern compile(String regex) {
            return regex==null||regex.isBlank()?null:Pattern.compile(regex);
        }
        /** 文本是否表明本单要走这道按需工序：命中肯定词且未命中否定词。两个文本分开判，避免互相覆盖。 */
        boolean indicatedBy(String text) {
            if(match==null||text==null||text.isBlank())return false;
            String value=text.replaceAll("[\\s\\u3000]","");
            if(!match.matcher(value).find())return false;
            return exclude==null||!exclude.matcher(value).find();
        }
    }

    private static volatile Map<String,List<StageDef>> BY_KIND=builtIn();

    private ProductionTrackingStageCatalog() {}

    static Map<String,List<StageDef>> snapshot() {return BY_KIND;}
    static List<StageDef> enabled(String kind) {
        return BY_KIND.getOrDefault(kind,List.of()).stream().filter(StageDef::enabled).toList();
    }
    /** 含已停用的全部定义（管理页要能看见并重新启用）。 */
    static List<StageDef> all(String kind) {return BY_KIND.getOrDefault(kind,List.of());}

    /** 整体替换快照；按 sort_no 排序，保证「顺序即制程顺序」。 */
    static void replace(List<StageDef> defs) {
        var grouped=new LinkedHashMap<String,List<StageDef>>();
        for(StageDef def:defs)grouped.computeIfAbsent(def.kind(),k->new ArrayList<>()).add(def);
        for(var list:grouped.values())list.sort(Comparator.comparingInt(StageDef::sortNo)
                .thenComparing(StageDef::code));
        // 保留插入顺序：跨类别查找表头别名时结果才稳定（Map.copyOf 会打乱迭代顺序）。
        BY_KIND=java.util.Collections.unmodifiableMap(grouped);
    }

    static Map<String,List<StageDef>> builtIn() {
        return Map.of(
                SEALER,List.of(
                        new StageDef(SEALER,"MATERIAL","备料",1,false,null,null,null,true),
                        new StageDef(SEALER,"MOLD","模具生产",2,false,null,null,List.of("模具"),true),
                        new StageDef(SEALER,"OXIDATION","氧化/喷特氟龙",3,true,"氧化|特氟龙|特富龙",
                                "(不含|无需|不用|不需要|无)(氧化|特氟龙|特富龙)",null,true),
                        new StageDef(SEALER,"MATERIAL_READY","物料配齐",4,false,null,null,List.of("物料齐套"),true),
                        new StageDef(SEALER,"ASSEMBLY","组装",5,false,null,null,null,true),
                        new StageDef(SEALER,"WAREHOUSE","入库",6,false,null,null,null,true),
                        new StageDef(SEALER,"WOODEN_CRATE","木箱",7,true,"木箱",
                                "(不含|无需|不用|不需要|无)木箱",List.of("出口木箱"),true)),
                MACHINE,List.of(
                        new StageDef(MACHINE,"DRAWING","画图",1,false,null,null,null,true),
                        new StageDef(MACHINE,"MATERIAL","备料",2,false,null,null,null,true),
                        new StageDef(MACHINE,"SHEET_METAL","钣金",3,false,null,null,null,true),
                        new StageDef(MACHINE,"MOLD","模具",4,false,null,null,List.of("模具生产"),true),
                        new StageDef(MACHINE,"ASSEMBLY","组装",5,false,null,null,null,true),
                        new StageDef(MACHINE,"TESTING","调试",6,false,null,null,null,true),
                        new StageDef(MACHINE,"WAREHOUSE","入库",7,false,null,null,null,true),
                        new StageDef(MACHINE,"WOODEN_CRATE","木箱",8,true,"木箱",
                                "(不含|无需|不用|不需要|无)木箱",List.of("出口木箱"),true)));
    }
}
