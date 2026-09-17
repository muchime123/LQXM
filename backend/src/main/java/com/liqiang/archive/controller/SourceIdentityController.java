package com.liqiang.archive.controller;

import com.liqiang.archive.security.RequirePermission;
import com.liqiang.archive.service.SourceIdentityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/h3-imports/identities")
@RequiredArgsConstructor
public class SourceIdentityController {
    private final SourceIdentityService service;

    @GetMapping
    @RequirePermission({"integration.h3", "product_inventory.read"})
    public Map<String, Object> page(@RequestParam(required = false) String entityType,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "50") int size) {
        return service.page(entityType, status, keyword, page, size);
    }

    @GetMapping("/targets")
    @RequirePermission({"integration.h3", "product_inventory.read"})
    public Map<String, Object> targets(@RequestParam String entityType,
                                       @RequestParam(required = false) String keyword) {
        return service.targets(entityType, keyword);
    }

    @GetMapping("/audit")
    @RequirePermission({"integration.h3", "product_inventory.read"})
    public Map<String, Object> audit() {
        return service.auditSummary();
    }

    @GetMapping("/safe-batch-preview")
    @RequirePermission({"integration.h3", "product_inventory.read"})
    public Map<String, Object> safeBatchPreview() {
        return service.safeBatchPreview();
    }

    @GetMapping("/linked-review-preview")
    @RequirePermission({"integration.h3", "product_inventory.read"})
    public Map<String, Object> linkedReviewPreview() {
        return service.linkedReviewPreview();
    }

    @GetMapping("/new-customer-preview")
    @RequirePermission({"integration.h3", "product_inventory.read"})
    public Map<String, Object> newCustomerPreview() {
        return service.newCustomerPreview();
    }

    @GetMapping("/customer-address-merge-preview")
    @RequirePermission({"integration.h3", "customer.manage"})
    public Map<String, Object> customerAddressMergePreview() {
        return service.customerAddressMergePreview();
    }

    @GetMapping("/address-audit")
    @RequirePermission({"integration.h3", "customer.manage"})
    public Map<String, Object> addressAudit() {
        return service.customerAddressAudit();
    }

    @GetMapping("/address-import-preview")
    @RequirePermission({"integration.h3", "customer.manage"})
    public Map<String, Object> addressImportPreview() {
        return service.customerAddressImportPreview();
    }

    @PostMapping("/safe-batch-confirm")
    @RequirePermission("integration.h3.identity.resolve")
    public Map<String, Object> safeBatchConfirm() {
        return service.confirmSafeBatch();
    }

    @PostMapping("/linked-review-confirm")
    @RequirePermission("integration.h3.identity.resolve")
    public Map<String, Object> linkedReviewConfirm() {
        return service.confirmLinkedReviews();
    }

    @PostMapping("/new-customer-confirm")
    @RequirePermission("integration.h3.identity.resolve")
    public Map<String, Object> newCustomerConfirm() {
        return service.confirmNewCustomers();
    }

    @PostMapping("/customer-address-merge-confirm")
    @RequirePermission("customer.manage")
    public Map<String, Object> customerAddressMergeConfirm() {
        return service.confirmCustomerAddressMerges();
    }

    @PostMapping("/address-import-confirm")
    @RequirePermission("customer.manage")
    public Map<String, Object> addressImportConfirm() {
        return service.confirmCustomerAddressImport();
    }

    @GetMapping("/customer-cleanup-preview")
    @RequirePermission({"integration.h3", "product_inventory.read"})
    public Map<String, Object> customerCleanupPreview() {
        return service.customerCleanupBatchPreview();
    }

    @PostMapping("/customer-cleanup-confirm")
    @RequirePermission("integration.h3.identity.resolve")
    public Map<String, Object> customerCleanupConfirm() {
        return service.confirmCustomerCleanupBatch();
    }

    @PutMapping("/{id}/resolve")
    @RequirePermission("integration.h3.identity.resolve")
    public Map<String, Object> resolve(@PathVariable long id, @RequestBody Map<String, Object> body) {
        Object raw = body.get("targetId");
        if (!(raw instanceof Number n)) throw new IllegalArgumentException("请选择目标档案");
        return service.resolve(id, n.longValue(), text(body.get("note")));
    }

    @GetMapping("/{id}/reassign-preview")
    @RequirePermission("integration.h3.identity.resolve")
    public Map<String, Object> reassignPreview(@PathVariable long id, @RequestParam long targetId) {
        return service.reassignPreview(id, targetId);
    }

    @PutMapping("/{id}/reassign")
    @RequirePermission("integration.h3.identity.resolve")
    public Map<String, Object> reassign(@PathVariable long id, @RequestBody Map<String, Object> body) {
        Object raw = body.get("targetId");
        if (!(raw instanceof Number n)) throw new IllegalArgumentException("请选择新的目标档案");
        return service.reassign(id, n.longValue(), text(body.get("note")));
    }

    @PutMapping("/{id}/cleanup-customer")
    @RequirePermission("integration.h3.identity.resolve")
    public Map<String, Object> cleanupCustomer(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return service.cleanupCustomer(id, text(body.get("customerName")), text(body.get("phone")),
                text(body.get("note")));
    }

    @PutMapping("/{id}/reopen")
    @RequirePermission("integration.h3.identity.resolve")
    public Map<String, Object> reopen(@PathVariable long id) {
        return service.reopenCustomerIdentity(id);
    }

    @PostMapping("/{id}/create-customer")
    @RequirePermission("customer.manage")
    public Map<String, Object> createCustomer(@PathVariable long id,
                                               @RequestBody(required = false) Map<String, Object> body) {
        return service.createCustomerFromIdentity(id, body == null ? null : text(body.get("customerName")));
    }

    @PutMapping("/{id}/ignore")
    @RequirePermission("integration.h3.identity.resolve")
    public void ignore(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        service.ignore(id, body == null ? null : text(body.get("note")));
    }

    private static String text(Object value) { return value == null ? null : String.valueOf(value).trim(); }
}
