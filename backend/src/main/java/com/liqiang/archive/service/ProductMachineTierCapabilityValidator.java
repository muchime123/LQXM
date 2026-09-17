package com.liqiang.archive.service;

import com.liqiang.archive.entity.ProductMachineTier;
import com.liqiang.archive.entity.ProductVariant;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 统一校验机型能力边界；未配置某种形状表示“不支持”，而不是无限制放行。 */
@Component
public class ProductMachineTierCapabilityValidator {

    public void validate(ProductMachineTier tier, PackagingApertureResolver.Aperture aperture) {
        String tierLabel = label(tier);
        if ("ROUND".equals(aperture.type())) {
            if (tier.getRoundDiameterMin() == null && tier.getRoundDiameterMax() == null) {
                throw new IllegalArgumentException(tierLabel + " 未配置圆形口径范围，不能建立圆形规格");
            }
            validateRange("圆形口径", decimal(aperture.diameterMm()), tier.getRoundDiameterMin(),
                    tier.getRoundDiameterMax(), "Ø" + aperture.diameterMm() + "mm", tierLabel);
            return;
        }
        if (tier.getRectLengthMin() == null && tier.getRectLengthMax() == null
                && tier.getRectWidthMin() == null && tier.getRectWidthMax() == null) {
            throw new IllegalArgumentException(tierLabel + " 未配置方形口径范围，不能建立方形规格");
        }
        String apertureLabel = aperture.lengthMm() + "×" + aperture.widthMm() + "mm";
        validateRange("方形长边", decimal(aperture.lengthMm()), tier.getRectLengthMin(),
                tier.getRectLengthMax(), apertureLabel, tierLabel);
        validateRange("方形短边", decimal(aperture.widthMm()), tier.getRectWidthMin(),
                tier.getRectWidthMax(), apertureLabel, tierLabel);
    }

    public void validate(ProductMachineTier tier, ProductVariant variant) {
        PackagingApertureResolver.Aperture aperture = new PackagingApertureResolver.Aperture(
                variant.getMoldSizeType(), null, null, variant.getMoldSizeLabel(),
                variant.getMoldLengthMm(), variant.getMoldWidthMm(), variant.getMoldDiameterMm());
        validate(tier, aperture);
    }

    private void validateRange(String name, BigDecimal value, BigDecimal min, BigDecimal max,
                               String apertureLabel, String tierLabel) {
        if (value == null) throw new IllegalArgumentException(apertureLabel + " 缺少可校验的" + name);
        if (min != null && value.compareTo(min) < 0) {
            throw new IllegalArgumentException(apertureLabel + " 超出 " + tierLabel + " 的" + name
                    + "范围（下限 " + display(min) + "mm）");
        }
        if (max != null && value.compareTo(max) > 0) {
            throw new IllegalArgumentException(apertureLabel + " 超出 " + tierLabel + " 的" + name
                    + "范围（上限 " + display(max) + "mm）");
        }
    }

    private BigDecimal decimal(String value) {
        return value == null || value.isBlank() ? null : new BigDecimal(value.trim());
    }

    private String display(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private String label(ProductMachineTier tier) {
        return tier.getTierLabel() == null || tier.getTierLabel().isBlank() ? tier.getTierCode() : tier.getTierLabel();
    }
}
