package com.liqiang.archive.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.liqiang.archive.entity.CompanyAccount;
import com.liqiang.archive.entity.CompanyAsset;
import com.liqiang.archive.entity.CompanyCertification;
import com.liqiang.archive.entity.CompanyTeamMember;
import com.liqiang.archive.entity.Certificate;
import com.liqiang.archive.entity.PackagingAsset;
import com.liqiang.archive.entity.ProductAsset;

import java.time.LocalDateTime;
import java.util.Set;

public class FileDtos {

    public enum OwnerType {
        PRODUCT_MODEL("product_model"),
        PRODUCT_VARIANT("product_variant"),
        PACKAGING_MODEL("packaging_model"),
        PACKAGING_VARIANT("packaging_variant"),
        COMPANY("company"),
        CERTIFICATE("certificate"),
        /** 仅为历史文件定位兼容保留，不再注册独立业务适配器。 */
        DOCUMENT("document");

        private final String value;

        OwnerType(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        @JsonValue
        public String jsonValue() {
            return value;
        }

        @JsonCreator
        public static OwnerType from(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("ownerType is required");
            }
            String normalized = value.trim();
            for (OwnerType ownerType : values()) {
                if (ownerType.value.equalsIgnoreCase(normalized)
                        || ownerType.name().equalsIgnoreCase(normalized)) {
                    return ownerType;
                }
            }
            throw new IllegalArgumentException("Unsupported ownerType: " + value);
        }
    }

    public enum Capability {
        PREVIEW,
        DOWNLOAD,
        DELETE,
        RESTORE,
        PERMANENT_DELETE,
        MOVE_OWNER,
        MOVE_CATEGORY,
        UPDATE_TAGS,
        SET_DEFAULT,
        REORDER,
        OPEN_FOLDER
    }

    public record FileHandle(
            Long assetId,
            OwnerType ownerType,
            Long ownerId,
            String category,
            String storageCategory,
            Long storageOwnerId,
            String fileName,
            String originalFileName,
            String mimeType,
            Long fileSize,
            String tags,
            Boolean defaultFile,
            Integer sortOrder,
            Boolean deleted,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            String coverFileName,
            Set<Capability> capabilities
    ) {
        public static FileHandle from(ProductAsset asset) {
            // 产品素材的物理存储和文件行为始终归属于产品。
            // variant/machineTier 只是业务关联，不能改变缩略图、预览和下载所使用的 owner。
            OwnerType ownerType = OwnerType.PRODUCT_MODEL;
            Long ownerId = asset.getModel().getId();
            return new FileHandle(
                    asset.getId(),
                    ownerType,
                    ownerId,
                    asset.getCategory(),
                    asset.getStorageCategory() == null ? asset.getCategory() : asset.getStorageCategory(),
                    asset.getStorageCategory() == null ? asset.getModel().getId() : asset.getStorageOwnerId(),
                    asset.getFileName(),
                    asset.getOriginalFileName(),
                    asset.getMimeType(),
                    asset.getFileSize(),
                    asset.getTags(),
                    asset.getDefaultAsset(),
                    asset.getSortOrder(),
                    asset.getDeletedAt() != null,
                    asset.getCreatedAt(),
                    null,
                    asset.getCoverFileName(),
                    FileAssetCapabilities.PRODUCT_ASSET
            );
        }

        public static FileHandle from(PackagingAsset asset) {
            OwnerType ownerType = asset.getVariant() == null ? OwnerType.PACKAGING_MODEL : OwnerType.PACKAGING_VARIANT;
            Long ownerId = asset.getVariant() == null ? asset.getModel().getId() : asset.getVariant().getId();
            return new FileHandle(
                    asset.getId(),
                    ownerType,
                    ownerId,
                    asset.getCategory(),
                    asset.getCategory(),
                    asset.getModel().getId(),
                    asset.getFileName(),
                    asset.getFileName(),
                    null,
                    null,
                    asset.getTags(),
                    asset.getDefaultAsset(),
                    asset.getSortOrder(),
                    asset.getDeletedAt() != null,
                    asset.getCreatedAt(),
                    null,
                    asset.getCoverFileName(),
                    FileAssetCapabilities.PACKAGING_ASSET
            );
        }

        public static FileHandle from(CompanyAsset asset) {
            return new FileHandle(
                    asset.getId(),
                    OwnerType.COMPANY,
                    null,
                    asset.getCategory(),
                    "company",
                    null,
                    asset.getFileName(),
                    asset.getFileName(),
                    null,
                    null,
                    asset.getTags(),
                    asset.getDefaultAsset(),
                    asset.getSortOrder(),
                    asset.getDeletedAt() != null,
                    asset.getCreatedAt(),
                    asset.getUpdatedAt(),
                    asset.getCoverFileName(),
                    FileAssetCapabilities.COMPANY_ASSET
            );
        }

        public static FileHandle certificateFile(Certificate certificate, String field, String fileName) {
            Long syntheticId = certificate.getId() * 10 + ("certFile".equals(field) ? 2 : 1);
            String label = "certFile".equals(field) ? "cert_file" : "spec_file";
            return new FileHandle(
                    syntheticId,
                    OwnerType.CERTIFICATE,
                    certificate.getId(),
                    label,
                    "certificate",
                    null,
                    fileName,
                    fileName,
                    null,
                    null,
                    null,
                    false,
                    null,
                    false,
                    certificate.getCreatedAt(),
                    certificate.getUpdatedAt(),
                    null,
                    FileAssetCapabilities.CERTIFICATE
            );
        }

        public static FileHandle companyAccountQrFile(CompanyAccount account, Long syntheticId) {
            return new FileHandle(
                    syntheticId,
                    OwnerType.COMPANY,
                    account.getId(),
                    "company_finance",
                    "company_finance",
                    null,
                    account.getQrCodeFile(),
                    account.getQrCodeFile(),
                    null,
                    null,
                    null,
                    false,
                    account.getSortOrder(),
                    false,
                    account.getCreatedAt(),
                    account.getUpdatedAt(),
                    null,
                    FileAssetCapabilities.COMPANY_FIELD_FILE
            );
        }

        public static FileHandle companyTeamPhotoFile(CompanyTeamMember member, Long syntheticId) {
            return new FileHandle(
                    syntheticId,
                    OwnerType.COMPANY,
                    member.getId(),
                    "company_team",
                    "company_team",
                    null,
                    member.getPhotoFileName(),
                    member.getPhotoFileName(),
                    null,
                    null,
                    null,
                    false,
                    member.getSortOrder(),
                    false,
                    member.getCreatedAt(),
                    member.getUpdatedAt(),
                    null,
                    FileAssetCapabilities.COMPANY_FIELD_FILE
            );
        }

        public static FileHandle companyCertificationFile(CompanyCertification certification, Long syntheticId, String fileName) {
            return new FileHandle(
                    syntheticId,
                    OwnerType.COMPANY,
                    certification.getId(),
                    "company",
                    "company",
                    null,
                    fileName,
                    fileName,
                    null,
                    null,
                    null,
                    false,
                    0,
                    false,
                    certification.getCreatedAt(),
                    certification.getUpdatedAt(),
                    null,
                    FileAssetCapabilities.COMPANY_FIELD_FILE
            );
        }
    }

}
