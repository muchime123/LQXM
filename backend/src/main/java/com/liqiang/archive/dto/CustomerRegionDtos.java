package com.liqiang.archive.dto;

import java.time.LocalDateTime;

/** 地区写法别名（客户档案 · 地区缺口治理）。 */
public final class CustomerRegionDtos {

    private CustomerRegionDtos() {
    }

    /** 别名条目。 */
    public record RegionAliasItem(Long id, String kind, String alias, String province, String target,
                                  String remark, LocalDateTime createdAt) {
    }

    /** 新建别名请求。kind ∈ PROVINCE/CITY/DISTRICT。 */
    public record RegionAliasCreateRequest(String kind, String alias, String province, String target, String remark) {
    }
}
