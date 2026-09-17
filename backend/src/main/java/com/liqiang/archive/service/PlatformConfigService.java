package com.liqiang.archive.service;

import com.liqiang.archive.entity.PlatformConfig;
import com.liqiang.archive.repository.PlatformConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class PlatformConfigService {

    private final PlatformConfigRepository repository;
    private final AuditLogService auditLogService;

    public PlatformConfigService(PlatformConfigRepository repository, AuditLogService auditLogService) {
        this.repository = repository;
        this.auditLogService = auditLogService;
    }

    /** 获取所有平台（含禁用） */
    @Transactional(readOnly = true)
    public List<PlatformConfig> getAll() {
        return repository.findAllByOrderBySortOrderAsc();
    }

    /** 获取启用的平台（前端组件使用） */
    @Transactional(readOnly = true)
    public List<PlatformConfig> getActive() {
        return repository.findAllActive();
    }

    @Transactional
    public PlatformConfig create(PlatformConfig config) {
        if (repository.existsByName(config.getName())) {
            throw new IllegalArgumentException("平台名称 '" + config.getName() + "' 已存在");
        }
        config.setId(null);
        if (config.getSortOrder() == null) config.setSortOrder(0);
        if (config.getIsActive() == null) config.setIsActive(true);
        if (config.getIcon() == null || config.getIcon().isBlank()) config.setIcon("📱");
        if (config.getColor() == null || config.getColor().isBlank()) config.setColor("#64748b");
        PlatformConfig saved = repository.save(config);
        auditLogService.log("PlatformConfig", saved.getId(), saved.getName(),
                "PLATFORM_CREATE", "创建平台配置 " + saved.getName());
        return saved;
    }

    @Transactional
    public PlatformConfig update(Long id, PlatformConfig config) {
        PlatformConfig existing = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("平台配置不存在: " + id));

        if (repository.existsByNameAndIdNot(config.getName(), id)) {
            throw new IllegalArgumentException("平台名称 '" + config.getName() + "' 已存在");
        }

        existing.setName(config.getName());
        existing.setIcon(config.getIcon() != null ? config.getIcon() : "📱");
        existing.setColor(config.getColor() != null ? config.getColor() : "#64748b");
        if (config.getSortOrder() != null) existing.setSortOrder(config.getSortOrder());
        if (config.getIsActive() != null) existing.setIsActive(config.getIsActive());

        PlatformConfig saved = repository.save(existing);
        auditLogService.log("PlatformConfig", saved.getId(), saved.getName(),
                "PLATFORM_UPDATE", "更新平台配置 " + saved.getName());
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        PlatformConfig existing = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("平台配置不存在: " + id));
        repository.deleteById(id);
        auditLogService.log("PlatformConfig", id, existing.getName(),
                "PLATFORM_DELETE", "删除平台配置 " + existing.getName());
    }
}
