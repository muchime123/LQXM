package com.liqiang.archive.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductVariantTagPolicyTest {

    @Test
    void normalizesMixedSeparatorsWithoutSplittingWordsOnSpaces() {
        assertEquals("一出一,方模具", ProductVariantTagPolicy.storageTags(" 一出一 ， 方模具 "));
        assertEquals("一出一，方模具", ProductVariantTagPolicy.variantLabel("一出一, 方模具"));
        assertEquals("一出一，方模具", ProductVariantTagPolicy.variantLabel("白底图,一出一,方模具,豆腐封口"));
        assertEquals("一出一", ProductVariantTagPolicy.outputCount("方模具,一出一"));
        assertEquals("方模具", ProductVariantTagPolicy.moldShape("方模具,一出一"));
        assertEquals("异形 模具", ProductVariantTagPolicy.variantLabel("异形  模具"));
    }

    @Test
    void matchesAExistingSpecificationRegardlessOfTagOrder() {
        assertTrue(ProductVariantTagPolicy.equivalent("一出一，方模具", "方模具,一出一"));
        assertFalse(ProductVariantTagPolicy.equivalent("一出一，方模具", "一出二，方模具"));
    }

    @Test
    void renamingSpecificationPreservesUnrelatedAssetTags() {
        assertEquals(
                "白底图,一出二,圆模具",
                ProductVariantTagPolicy.replaceSpecificationTags(
                        "一出一,方模具,白底图", "一出一，方模具", "一出二，圆模具")
        );
    }
}
