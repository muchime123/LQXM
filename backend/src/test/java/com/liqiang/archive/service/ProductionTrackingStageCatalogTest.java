package com.liqiang.archive.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 制程节点落表后的口径契约：节点序列、名称、按需判定<b>全部来自快照</b>，不再来自常量。
 *
 * <p>这里断言的是「改了制程表，全站口径就跟着变」——如果哪天有人把某处改回写死常量，
 * 下面这些用例会先红。
 */
class ProductionTrackingStageCatalogTest {
    private static final ProductionTrackingStageCatalog.StageDef SEALER_MATERIAL=
            new ProductionTrackingStageCatalog.StageDef("SEALER","MATERIAL","备料",1,false,null,null,null,true);

    /** 内置默认必须与 V420 的播种值一致，否则「表还没加载时」和「加载后」是两套口径。 */
    @Test void builtInSealerSequenceMatchesTheSeedMigration() {
        assertEquals(List.of("MATERIAL","MOLD","OXIDATION","MATERIAL_READY","ASSEMBLY","WAREHOUSE","WOODEN_CRATE"),
                ProductionTrackingStageCatalog.builtIn().get("SEALER").stream()
                        .map(ProductionTrackingStageCatalog.StageDef::code).toList());
        assertEquals(List.of("DRAWING","MATERIAL","SHEET_METAL","MOLD","ASSEMBLY","TESTING","WAREHOUSE","WOODEN_CRATE"),
                ProductionTrackingStageCatalog.builtIn().get("MACHINE").stream()
                        .map(ProductionTrackingStageCatalog.StageDef::code).toList());
    }

    @Test void sequenceAndLabelsComeFromTheCatalogNotFromAConstant() {
        var before=flatten(ProductionTrackingStageCatalog.snapshot());
        try{
            ProductionTrackingStageCatalog.replace(List.of(SEALER_MATERIAL,
                    new ProductionTrackingStageCatalog.StageDef("SEALER","PACK","备料齐套",2,false,
                            null,null,List.of("物料齐套"),true),
                    new ProductionTrackingStageCatalog.StageDef("SEALER","ASSEMBLY","组装",3,false,null,null,null,true)));
            assertEquals(List.of("MATERIAL","PACK","ASSEMBLY"),ProductionTrackingPolicy.stageCodes("封口机"),
                    "制程顺序即节点条顺序");
            assertEquals(List.of("MATERIAL","PACK","ASSEMBLY"),ProductionTrackingPolicy.requiredStages("封口机"));
            assertEquals("备料齐套",ProductionTrackingPolicy.stageLabel("PACK",true));
            // 新增节点无需改任何代码就能被进度表表头认出来（别名也要生效）。
            assertEquals("PACK",ProductionTrackingPolicy.stageCodeOfLabel("备料齐套","封口机"));
            assertEquals("PACK",ProductionTrackingPolicy.stageCodeOfLabel("物料齐套","封口机"));
            assertEquals("PACK",ProductionTrackingPolicy.stageCodeOfLabel("物料齐套"));
            assertFalse(ProductionTrackingPolicy.isOptionalStage("PACK","封口机"));
        }finally{
            ProductionTrackingStageCatalog.replace(before);
        }
    }

    /** 停用：不进序列、不进分母，但名字还在（历史记录靠编码关联，不能消失）。 */
    @Test void disabledStageDropsOutOfTheSequenceAndTheDenominator() {
        var before=flatten(ProductionTrackingStageCatalog.snapshot());
        try{
            ProductionTrackingStageCatalog.replace(List.of(SEALER_MATERIAL,
                    new ProductionTrackingStageCatalog.StageDef("SEALER","OXIDATION","氧化/喷特氟龙",2,true,
                            "氧化","不含氧化",null,false)));
            assertEquals(List.of("MATERIAL"),ProductionTrackingPolicy.stageCodes("封口机"));
            assertEquals(List.of("MATERIAL"),ProductionTrackingPolicy.requiredStages("封口机"),
                    "停用的节点不该再算进完成判据的分母");
        }finally{
            ProductionTrackingStageCatalog.replace(before);
        }
    }

    /** 按需工序的关键词取自制程表：改了关键词，判定即刻跟着变（不再写死「氧化|特氟龙|木箱」）。 */
    @Test void optionalKeywordsComeFromTheCatalog() {
        var before=flatten(ProductionTrackingStageCatalog.snapshot());
        try{
            ProductionTrackingStageCatalog.replace(List.of(
                    new ProductionTrackingStageCatalog.StageDef("SEALER","MOLD","模具生产",1,false,null,null,null,true),
                    new ProductionTrackingStageCatalog.StageDef("SEALER","OXIDATION","氧化/喷特氟龙",2,true,
                            "氧化|特氟龙|特富龙","(不含|无需|不用|不需要|无)(氧化|特氟龙|特富龙)",null,true)));
            assertTrue(ProductionTrackingPolicy.isOptionalStage("OXIDATION","封口机"));
            assertTrue(ProductionTrackingPolicy.configuredOptionalStage("OXIDATION","需要喷特氟龙"));
            assertFalse(ProductionTrackingPolicy.configuredOptionalStage("OXIDATION","不需要氧化"));
            assertFalse(ProductionTrackingPolicy.configuredOptionalStage("OXIDATION","常规包装"));
            // 可选配置与生产要求分别判断后取并集：一处写明需要、另一处提到不需要时互不覆盖。
            assertTrue(ProductionTrackingPolicy.configuredOptionalStage("OXIDATION","氧化处理","不含氧化"));

            // 关键词换成「木箱」后，同一套判定立刻改口——这就是配置化的意义。
            ProductionTrackingStageCatalog.replace(List.of(
                    new ProductionTrackingStageCatalog.StageDef("SEALER","OXIDATION","氧化/喷特氟龙",2,true,
                            "木箱","(不含|无需)木箱",null,true)));
            assertTrue(ProductionTrackingPolicy.configuredOptionalStage("OXIDATION","出口木箱"));
            assertFalse(ProductionTrackingPolicy.configuredOptionalStage("OXIDATION","需要喷特氟龙"));
        }finally{
            ProductionTrackingStageCatalog.replace(before);
        }
    }

    /** 完成判据的分母查制程表，不再是写死的数字——加一个节点不用再改常量。 */
    @Test void stageTotalIsCountedFromTheStageTable() {
        String total=ProductionTrackingPolicy.stageTotalCase("p.production_line");
        assertTrue(total.contains("production_tracking_stage_defs"),"分母必须查制程表："+total);
        assertTrue(total.contains("d.enabled=1")&&total.contains("d.optional=0"),
                "停用与按需节点都不该进分母："+total);
        assertFalse(total.matches(".*THEN \\d+.*"),"分母不能再是写死的数字："+total);
    }

    @Test void unknownHeaderIsNotMistakenForAStage() {
        assertNull(ProductionTrackingPolicy.stageCodeOfLabel("生产进度"));
        assertNull(ProductionTrackingPolicy.stageCodeOfLabel("生产进度","封口机"));
    }

    private static List<ProductionTrackingStageCatalog.StageDef> flatten(
            Map<String,List<ProductionTrackingStageCatalog.StageDef>> snapshot) {
        return snapshot.values().stream().flatMap(List::stream).toList();
    }
}
