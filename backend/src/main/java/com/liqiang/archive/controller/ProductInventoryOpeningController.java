package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.ProductInventoryMergeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 氚云库存 → ERP 期初「开账预置」端点（决策 {@code #38}）。
 *
 * <p>单独成类而不并入 {@code ProductInventoryController}，是因为后者正在被并行会话改动，
 * 而在共享文件里追加方法会被整文件重写冲掉（本项目已两次实证的静默丢改动）。
 *
 * <p>本端点<b>只读</b>：不建产品主轴、不写余额、不开流水。它的作用是回答
 * 「切换日 T 开账会发生什么」——进账多少行、涉及多少产品、金额多少、哪些行会被拦下、
 * 哪些行按 #38 口径该进账但当前 {@code confirm} 会跳过（口径缺口）。
 */
@RestController
@RequestMapping("/api/product-inventory")
@RequirePermission("product_inventory.read")
@RequiredArgsConstructor
public class ProductInventoryOpeningController {

    private final ProductInventoryMergeService mergeService;

    /**
     * 开账干跑：产出「T 日开账」对账清单。只读，不写任何表。
     *
     * <p>用 POST 而非 GET：结果依赖时点台账快照，且响应体较大，不应被缓存复用。
     */
    @PostMapping("/opening/preflight")
    public Map<String, Object> openingPreflight() {
        return mergeService.openingPreflight();
    }
}
