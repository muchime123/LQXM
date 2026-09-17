package com.liqiang.archive.service;

import com.liqiang.archive.dto.OfficeAssetDtos.AssetActionRequest;
import com.liqiang.archive.dto.OfficeAssetDtos.AssetStats;
import com.liqiang.archive.entity.OfficeAsset;
import com.liqiang.archive.entity.OfficeAssetRecord;
import com.liqiang.archive.repository.OfficeAssetRecordRepository;
import com.liqiang.archive.repository.OfficeAssetRepository;
import com.liqiang.archive.util.StringUtils;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class OfficeAssetService {
    private static final Set<String> ACTIONS = Set.of("入库", "领用", "归还", "调拨", "盘点", "送修", "维修完成", "报废");
    private static final Set<String> STATUSES = Set.of("在库", "领用中", "维修中", "已报废");

    /** 通用批量更新允许修改的字段白名单（不含 assetCode：编号唯一且自动生成） */
    private static final Set<String> BATCH_EDITABLE_FIELDS = Set.of(
            "name", "category", "brand", "model", "serialNumber", "department", "custodian",
            "location", "status", "purchaseDate", "purchasePrice", "supplier", "warrantyUntil", "notes"
    );

    private final OfficeAssetRepository repository;
    private final OfficeAssetRecordRepository recordRepository;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public Page<OfficeAsset> search(String keyword, String category, String status, String department, Pageable pageable) {
        return repository.search(blankToNull(keyword), blankToNull(category), blankToNull(status), blankToNull(department), pageable);
    }

    @Transactional(readOnly = true)
    public OfficeAsset scan(String code) {
        String normalized = normalizeScannedCode(code);
        return repository.findByAssetCodeIgnoreCase(normalized)
                .orElseThrow(() -> new EntityNotFoundException("未找到资产编号: " + normalized));
    }

    @Transactional
    public OfficeAsset create(OfficeAsset asset) {
        asset.setId(null);
        asset.setAssetCode(uniqueCode(asset.getAssetCode()));
        if (blankToNull(asset.getStatus()) == null) asset.setStatus("在库");
        OfficeAsset saved = repository.save(asset);
        saveRecord(saved, new AssetActionRequest("入库", null, saved.getDepartment(), saved.getCustodian(), saved.getLocation(), "创建资产档案"));
        auditLogService.log("OfficeAsset", saved.getId(), saved.getAssetCode(),
                "OFFICE_ASSET_CREATE", "创建物品资产 " + saved.getAssetCode() + " / " + saved.getName());
        return saved;
    }

    @Transactional
    public OfficeAsset update(Long id, OfficeAsset payload) {
        OfficeAsset asset = require(id);
        copy(payload, asset);
        OfficeAsset saved = repository.save(asset);
        auditLogService.log("OfficeAsset", saved.getId(), saved.getAssetCode(),
                "OFFICE_ASSET_UPDATE", "更新物品资产 " + saved.getAssetCode());
        return saved;
    }

    @Transactional
    public OfficeAsset action(Long id, AssetActionRequest request) {
        if (!ACTIONS.contains(request.action())) throw new IllegalArgumentException("不支持的资产操作: " + request.action());
        OfficeAsset asset = require(id);
        if (request.department() != null) asset.setDepartment(request.department().trim());
        if (request.custodian() != null) asset.setCustodian(request.custodian().trim());
        if (request.location() != null) asset.setLocation(request.location().trim());
        switch (request.action()) {
            case "入库", "归还" -> { asset.setStatus("在库"); asset.setCustodian(null); }
            case "领用", "调拨" -> asset.setStatus("领用中");
            case "盘点" -> asset.setLastInventoryAt(LocalDateTime.now());
            case "送修" -> asset.setStatus("维修中");
            case "维修完成" -> asset.setStatus(blankToNull(asset.getCustodian()) == null ? "在库" : "领用中");
            case "报废" -> asset.setStatus("已报废");
            default -> { }
        }
        OfficeAsset saved = repository.save(asset);
        saveRecord(saved, request);
        auditLogService.log("OfficeAsset", saved.getId(), saved.getAssetCode(),
                "OFFICE_ASSET_ACTION", "资产操作：" + request.action() + " / " + saved.getAssetCode());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<OfficeAssetRecord> records(Long id) { require(id); return recordRepository.findByAssetIdOrderByCreatedAtDesc(id); }

    @Transactional(readOnly = true)
    public AssetStats stats() {
        return new AssetStats(repository.count(), repository.countByStatus("在库"), repository.countByStatus("领用中"),
                repository.countByStatus("维修中"), repository.countByStatus("已报废"));
    }

    @Transactional
    public void delete(Long id) {
        OfficeAsset asset = require(id);
        asset.setDeletedAt(LocalDateTime.now());
        repository.save(asset);
        auditLogService.log("OfficeAsset", id, asset.getAssetCode(),
                "OFFICE_ASSET_DELETE", "移入回收站：物品资产 " + asset.getAssetCode());
    }

    /**
     * 通用批量更新：按字段白名单 + 类型转换，将选中资产的指定元数据字段统一修改。
     * 仅接受 BATCH_EDITABLE_FIELDS 内的字段；未在 fields 中的字段不会被修改。
     *
     * @param ids    目标资产 ID 列表
     * @param fields 字段名 -> 新值（String / LocalDate / BigDecimal 等）
     * @return 实际更新条数
     */
    @Transactional
    public int batchUpdateFields(List<Long> ids, Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("未指定要修改的字段");
        }
        for (String key : fields.keySet()) {
            if (!BATCH_EDITABLE_FIELDS.contains(key)) {
                throw new IllegalArgumentException("不支持的批量修改字段: " + key);
            }
        }
        // 校验必填字段非空
        if (fields.containsKey("name") && StringUtils.blankToNull((String) fields.get("name")) == null) {
            throw new IllegalArgumentException("物品名称不能为空");
        }
        if (fields.containsKey("category") && StringUtils.blankToNull((String) fields.get("category")) == null) {
            throw new IllegalArgumentException("物品分类不能为空");
        }
        // 校验状态取值
        if (fields.containsKey("status")) {
            String status = fields.get("status") == null ? "" : fields.get("status").toString();
            if (!STATUSES.contains(status)) {
                throw new IllegalArgumentException("状态不合法: " + status);
            }
        }
        List<OfficeAsset> assets = repository.findAllById(ids);
        for (OfficeAsset a : assets) {
            applyFieldUpdate(a, fields);
        }
        if (!assets.isEmpty()) {
            repository.saveAll(assets);
        }
        int count = assets.size();
        auditLogService.log("OfficeAsset", null, "批量",
                "OFFICE_ASSET_BATCH_UPDATE", "批量修改 " + count + " 个资产字段：" + String.join("、", fields.keySet()));
        return count;
    }

    /** 将 fields 中的白名单字段逐个应用到实体（只覆盖传入的字段）。 */
    private void applyFieldUpdate(OfficeAsset a, Map<String, Object> fields) {
        fields.forEach((key, value) -> {
            String v = value == null ? null : value.toString();
            switch (key) {
                case "name" -> a.setName(StringUtils.blankToNull(v));
                case "category" -> a.setCategory(StringUtils.blankToNull(v));
                case "brand" -> a.setBrand(StringUtils.blankToNull(v));
                case "model" -> a.setModel(StringUtils.blankToNull(v));
                case "serialNumber" -> a.setSerialNumber(StringUtils.blankToNull(v));
                case "department" -> a.setDepartment(StringUtils.blankToNull(v));
                case "custodian" -> a.setCustodian(StringUtils.blankToNull(v));
                case "location" -> a.setLocation(StringUtils.blankToNull(v));
                case "status" -> a.setStatus(StringUtils.blankToNull(v));
                case "supplier" -> a.setSupplier(StringUtils.blankToNull(v));
                case "notes" -> a.setNotes(v);
                case "purchaseDate" -> a.setPurchaseDate(value == null || v.isBlank() ? null : LocalDate.parse(v));
                case "warrantyUntil" -> a.setWarrantyUntil(value == null || v.isBlank() ? null : LocalDate.parse(v));
                case "purchasePrice" -> a.setPurchasePrice(value == null || v.isBlank() ? null : new BigDecimal(v));
                default -> { }
            }
        });
    }

    private OfficeAsset require(Long id) { return repository.findById(id).orElseThrow(() -> new EntityNotFoundException("物品资产不存在: " + id)); }
    private String uniqueCode(String requested) {
        String base = blankToNull(requested);
        if (base != null) {
            base = normalizeScannedCode(base).toUpperCase();
            if (repository.existsByAssetCodeIgnoreCase(base)) throw new IllegalArgumentException("资产编号已存在: " + base);
            return base;
        }
        String prefix = "LQ-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        for (int i = 1; i <= 9999; i++) {
            String candidate = prefix + String.format("%04d", i);
            if (!repository.existsByAssetCodeIgnoreCase(candidate)) return candidate;
        }
        throw new IllegalStateException("今日资产编号已用尽");
    }
    private String normalizeScannedCode(String value) {
        String code = value == null ? "" : value.trim();
        if (code.startsWith("ASSET:")) code = code.substring(6);
        if (code.isBlank() || code.length() > 40) throw new IllegalArgumentException("无效的资产编号");
        return code;
    }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private void saveRecord(OfficeAsset asset, AssetActionRequest request) {
        OfficeAssetRecord record = new OfficeAssetRecord();
        record.setAssetId(asset.getId()); record.setAction(request.action()); record.setOperatorName(blankToNull(request.operatorName()));
        record.setDepartment(asset.getDepartment()); record.setCustodian(asset.getCustodian()); record.setLocation(asset.getLocation());
        record.setNotes(blankToNull(request.notes())); recordRepository.save(record);
    }
    private void copy(OfficeAsset from, OfficeAsset to) {
        to.setName(from.getName()); to.setCategory(from.getCategory()); to.setBrand(from.getBrand()); to.setModel(from.getModel());
        to.setSerialNumber(from.getSerialNumber()); to.setDepartment(from.getDepartment()); to.setCustodian(from.getCustodian());
        to.setLocation(from.getLocation()); to.setStatus(from.getStatus()); to.setPurchaseDate(from.getPurchaseDate());
        to.setPurchasePrice(from.getPurchasePrice()); to.setSupplier(from.getSupplier()); to.setWarrantyUntil(from.getWarrantyUntil());
        to.setNotes(from.getNotes());
    }
}
