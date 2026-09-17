package com.liqiang.archive.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class OfficeAssetDtos {
    private OfficeAssetDtos() {}

    public record AssetActionRequest(
            @NotBlank @Size(max = 30) String action,
            @Size(max = 100) String operatorName,
            @Size(max = 100) String department,
            @Size(max = 100) String custodian,
            @Size(max = 160) String location,
            @Size(max = 500) String notes) {}

    public record AssetStats(long total, long inStock, long inUse, long repairing, long retired) {}
}
