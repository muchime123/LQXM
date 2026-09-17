package com.liqiang.archive.controller;

import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.liqiang.archive.dto.CustomerDtos.ContactPayload;
import com.liqiang.archive.dto.CustomerDtos.ContactSummary;
import com.liqiang.archive.dto.CustomerDtos.CustomerDetail;
import com.liqiang.archive.dto.CustomerDtos.CustomerDistribution;
import com.liqiang.archive.dto.CustomerDtos.CustomerOverview;
import com.liqiang.archive.dto.CustomerDtos.OutboundReference;
import com.liqiang.archive.util.ChinaAddressParser;
import com.liqiang.archive.dto.CustomerDtos.CustomerPayload;
import com.liqiang.archive.dto.CustomerDtos.CustomerSummary;
import com.liqiang.archive.dto.CustomerDtos.FilterOptions;
import com.liqiang.archive.dto.CustomerDtos.SalespersonPayload;
import com.liqiang.archive.dto.CustomerDtos.SalespersonSummary;
import com.liqiang.archive.dto.CustomerDtos.AddressPayload;
import com.liqiang.archive.dto.CustomerDtos.AddressSummary;
import com.liqiang.archive.dto.CustomerDtos.MergeHistorySummary;
import com.liqiang.archive.dto.CustomerDtos.SourceIdentitySummary;
import com.liqiang.archive.security.LoginOnly;
import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.CustomerService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    /** 分页搜索列表 */
    @GetMapping
    public Page<CustomerSummary> list(
            @RequestParam(required = false) String customerType,
            @RequestParam(required = false) String industry,
            @RequestParam(required = false) String province,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String district,
            @RequestParam(required = false) String relationStatus,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String salesperson,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean noRegion,
            @RequestParam(required = false) Boolean regionPartial,
            @RequestParam(required = false) String contact,
            @RequestParam(required = false) String address,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "customerCode") String sort,
            @RequestParam(defaultValue = "asc") String direction) {
        Sort.Direction dir = "desc".equalsIgnoreCase(direction) ? Sort.Direction.DESC : Sort.Direction.ASC;
        return customerService.search(customerType, industry, province, city, district, relationStatus, status,
                salesperson, keyword, noRegion, regionPartial, contact, address,
                PageRequest.of(page, size, Sort.by(dir, sort)));
    }

    /** 获取单条详情 */
    @GetMapping("/{id}")
    public CustomerDetail get(@PathVariable Long id) {
        return customerService.get(id);
    }

    /** 新增客户 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("customer.manage")
    public CustomerSummary create(@RequestBody CustomerPayload payload) {
        return customerService.create(payload);
    }

    /** 更新客户 */
    @PutMapping("/{id}")
    @RequirePermission("customer.manage")
    public CustomerSummary update(@PathVariable Long id, @RequestBody CustomerPayload payload) {
        return customerService.update(id, payload);
    }

    /** 删除客户（软删除） */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission("customer.manage")
    public void delete(@PathVariable Long id) {
        customerService.delete(id);
    }

    /**
     * 通用批量修改字段：按白名单一次性更新选中客户的元数据字段。
     * body: { "ids": [...], "fields": { "customerType": "企业", "relationStatus": "重点关注", ... } }
     */
    @PutMapping("/batch")
    @RequirePermission("customer.manage")
    public Map<String, Integer> batchUpdateFields(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Long> ids = ((List<Number>) body.get("ids")).stream()
                .map(Number::longValue).toList();
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) body.get("fields");
        int count = customerService.batchUpdateFields(ids, fields);
        return Map.of("affected", count);
    }

    /** 客户档案所在地分布（省 → 市 → 区县逐级统计，含地区完整度与缺口）。 */
    @GetMapping("/distribution")
    public CustomerDistribution distribution() {
        return customerService.distribution();
    }

    /**
     * 通用「地址 → 省市区县」纯文本解析（无副作用，供客户表单联动、整理工具预览等复用）。
     * body: { address, region? }；address 优先，为空时用 region 文本兜底；无法识别层级的字段为 null。
     */
    @LoginOnly
    @PostMapping("/address/parse")
    public ChinaAddressParser.ParsedAddress parseAddress(@RequestBody(required = false) Map<String, String> payload) {
        String text = payload == null ? null : payload.get("address");
        if (text == null || text.isBlank()) text = payload == null ? null : payload.get("region");
        if (text == null || text.isBlank()) return new ChinaAddressParser.ParsedAddress(null, null, null);
        return ChinaAddressParser.parse(text);
    }

    /** 筛选下拉选项 */
    @GetMapping("/filters")
    public FilterOptions filters() {
        return customerService.filterOptions();
    }

    /** 详情页概览统计（合同聚合 P2 接入） */
    @GetMapping("/{id}/overview")
    public CustomerOverview overview(@PathVariable Long id) {
        return customerService.getOverview(id);
    }

    /** 编辑弹窗「销售出库参考」：出库单上的地区标注/收货地址/联系电话/经销商等，供人工带入档案。 */
    @GetMapping("/{id}/outbound-reference")
    public OutboundReference outboundReference(@PathVariable Long id) {
        return customerService.getOutboundReference(id);
    }

    // ==================== 联系人 ====================

    @GetMapping("/{customerId}/contacts")
    public List<ContactSummary> listContacts(@PathVariable Long customerId) {
        return customerService.getContacts(customerId);
    }

    @PostMapping("/{customerId}/contacts")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("customer.manage")
    public ContactSummary addContact(@PathVariable Long customerId, @RequestBody ContactPayload payload) {
        return customerService.addContact(customerId, payload);
    }

    @PutMapping("/{customerId}/contacts/{contactId}")
    @RequirePermission("customer.manage")
    public ContactSummary updateContact(@PathVariable Long customerId,
                                        @PathVariable Long contactId,
                                        @RequestBody ContactPayload payload) {
        return customerService.updateContact(customerId, contactId, payload);
    }

    @DeleteMapping("/{customerId}/contacts/{contactId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission("customer.manage")
    public void deleteContact(@PathVariable Long customerId, @PathVariable Long contactId) {
        customerService.deleteContact(customerId, contactId);
    }

    @GetMapping("/{customerId}/addresses")
    public List<AddressSummary> listAddresses(@PathVariable Long customerId) { return customerService.getAddresses(customerId); }

    @PostMapping("/{customerId}/addresses")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("customer.manage")
    public AddressSummary addAddress(@PathVariable Long customerId, @RequestBody AddressPayload payload) {
        return customerService.addAddress(customerId, payload);
    }

    @PutMapping("/{customerId}/addresses/{addressId}")
    @RequirePermission("customer.manage")
    public AddressSummary updateAddress(@PathVariable Long customerId, @PathVariable Long addressId, @RequestBody AddressPayload payload) {
        return customerService.updateAddress(customerId, addressId, payload);
    }

    @DeleteMapping("/{customerId}/addresses/{addressId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission("customer.manage")
    public void deleteAddress(@PathVariable Long customerId, @PathVariable Long addressId) { customerService.deleteAddress(customerId, addressId); }

    @GetMapping("/{customerId}/merge-history")
    public List<MergeHistorySummary> mergeHistory(@PathVariable Long customerId) { return customerService.getMergeHistory(customerId); }

    /** 当前正式客户承接的氚云原始名称/标识。 */
    @GetMapping("/{customerId}/source-identities")
    public List<SourceIdentitySummary> sourceIdentities(@PathVariable Long customerId) {
        return customerService.getSourceIdentities(customerId);
    }

    // ==================== 业务员关系 ====================

    @GetMapping("/{customerId}/salespersons")
    public List<SalespersonSummary> listSalespersons(@PathVariable Long customerId) {
        return customerService.getSalespersons(customerId);
    }

    @PostMapping("/{customerId}/salespersons")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("customer.manage")
    public SalespersonSummary addSalesperson(@PathVariable Long customerId,
                                             @RequestBody SalespersonPayload payload) {
        return customerService.addSalesperson(customerId, payload);
    }

    @PutMapping("/{customerId}/salespersons/{relId}")
    @RequirePermission("customer.manage")
    public SalespersonSummary updateSalesperson(@PathVariable Long customerId,
                                                @PathVariable Long relId,
                                                @RequestBody SalespersonPayload payload) {
        return customerService.updateSalesperson(customerId, relId, payload);
    }

    @DeleteMapping("/{customerId}/salespersons/{relId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission("customer.manage")
    public void deleteSalesperson(@PathVariable Long customerId, @PathVariable Long relId) {
        customerService.deleteSalesperson(customerId, relId);
    }
}
