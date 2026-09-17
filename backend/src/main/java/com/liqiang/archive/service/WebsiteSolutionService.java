package com.liqiang.archive.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liqiang.archive.dto.SolutionDtos.SolutionPayload;
import com.liqiang.archive.dto.SolutionDtos.SolutionView;
import com.liqiang.archive.dto.SolutionDtos.SolutionSectionGroup;
import com.liqiang.archive.dto.SolutionDtos.ContentSection;
import com.liqiang.archive.entity.WebsiteSolution;
import com.liqiang.archive.entity.WebsiteSolution.SolutionSection;
import com.liqiang.archive.repository.WebsiteSolutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebsiteSolutionService {

    private final WebsiteSolutionRepository solutionRepo;
    private final ObjectMapper objectMapper;

    public List<SolutionView> listAll() {
        return solutionRepo.findAllByOrderBySectionAscSortOrderAsc().stream()
                .map(this::toView)
                .toList();
    }

    public List<SolutionSectionGroup> listBySection() {
        Map<SolutionSection, List<SolutionView>> grouped = solutionRepo.findAllByOrderBySectionAscSortOrderAsc().stream()
                .map(this::toView)
                .collect(Collectors.groupingBy(SolutionView::section));
        List<SolutionSectionGroup> result = new ArrayList<>();
        for (SolutionSection section : SolutionSection.values()) {
            List<SolutionView> items = grouped.getOrDefault(section, List.of());
            if (!items.isEmpty()) {
                result.add(new SolutionSectionGroup(section, items));
            }
        }
        return result;
    }

    public List<SolutionSectionGroup> listWebsiteVisible() {
        List<WebsiteSolution> visible = new ArrayList<>();
        for (SolutionSection section : SolutionSection.values()) {
            visible.addAll(solutionRepo.findBySectionAndWebsiteEnabledTrueOrderBySortOrderAsc(section));
        }
        Map<SolutionSection, List<SolutionView>> grouped = visible.stream()
                .map(this::toView)
                .collect(Collectors.groupingBy(SolutionView::section));
        List<SolutionSectionGroup> result = new ArrayList<>();
        for (SolutionSection section : SolutionSection.values()) {
            List<SolutionView> items = grouped.getOrDefault(section, List.of());
            if (!items.isEmpty()) {
                result.add(new SolutionSectionGroup(section, items));
            }
        }
        return result;
    }

    public SolutionView getById(Long id) {
        return solutionRepo.findById(id)
                .map(this::toView)
                .orElseThrow(() -> new IllegalArgumentException("Solution not found: " + id));
    }

    public SolutionView getPublishedArticle(String slug) {
        return solutionRepo.findBySlugAndWebsiteEnabledTrue(slug)
                .filter(item -> item.getSection() == SolutionSection.ARTICLE)
                .map(this::toView)
                .orElseThrow(() -> new IllegalArgumentException("Published solution article not found: " + slug));
    }

    public List<SolutionView> listPublishedArticles() {
        return solutionRepo.findBySectionAndWebsiteEnabledTrueOrderBySortOrderAsc(SolutionSection.ARTICLE)
                .stream().map(this::toView).toList();
    }

    @Transactional
    public SolutionView create(SolutionPayload payload) {
        WebsiteSolution entity = new WebsiteSolution();
        applyPayload(entity, payload);
        WebsiteSolution saved = solutionRepo.save(entity);
        log.info("Created website solution: section={}, key={}, title={}", payload.section(), payload.itemKey(), payload.title());
        return toView(saved);
    }

    @Transactional
    public SolutionView update(Long id, SolutionPayload payload) {
        WebsiteSolution entity = solutionRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Solution not found: " + id));
        applyPayload(entity, payload);
        WebsiteSolution saved = solutionRepo.save(entity);
        log.info("Updated website solution: id={}, title={}", id, payload.title());
        return toView(saved);
    }

    @Transactional
    public void delete(Long id) {
        if (!solutionRepo.existsById(id)) {
            throw new IllegalArgumentException("Solution not found: " + id);
        }
        solutionRepo.deleteById(id);
        log.info("Deleted website solution: id={}", id);
    }

    @Transactional
    public List<SolutionView> batchUpdateWebsiteEnabled(List<Long> ids, boolean enabled) {
        List<WebsiteSolution> entities = solutionRepo.findAllById(ids);
        for (WebsiteSolution e : entities) {
            e.setWebsiteEnabled(enabled);
        }
        List<WebsiteSolution> saved = solutionRepo.saveAll(entities);
        log.info("Batch updated websiteEnabled to {} for {} solutions", enabled, saved.size());
        return saved.stream().map(this::toView).toList();
    }

    private void applyPayload(WebsiteSolution entity, SolutionPayload payload) {
        entity.setSection(payload.section());
        entity.setItemKey(payload.itemKey());
        entity.setSlug(normalizeSlug(payload.slug()));
        entity.setTitle(payload.title());
        entity.setCategory(payload.category());
        entity.setSummary(payload.summary());
        entity.setCoverImageUrl(payload.coverImageUrl());
        entity.setAudience(payload.audience());
        entity.setTakeaway(payload.takeaway());
        entity.setIntro(payload.intro());
        entity.setIcon(payload.icon());
        entity.setPoints(toJson(payload.points()));
        entity.setContentSections(toJsonValue(payload.contentSections()));
        entity.setContentHtml(payload.contentHtml());
        entity.setRelatedSceneKeys(toJson(payload.relatedSceneKeys()));
        entity.setSeoTitle(payload.seoTitle());
        entity.setSeoDescription(payload.seoDescription());
        entity.setSortOrder(payload.sortOrder() != null ? payload.sortOrder() : 0);
        entity.setWebsiteEnabled(payload.websiteEnabled() != null ? payload.websiteEnabled() : true);
        entity.setWebsiteFeatured(payload.websiteFeatured() != null ? payload.websiteFeatured() : false);
    }

    private SolutionView toView(WebsiteSolution entity) {
        return new SolutionView(
                entity.getId(),
                entity.getSection(),
                entity.getItemKey(),
                entity.getSlug(),
                entity.getTitle(),
                entity.getCategory(),
                entity.getSummary(),
                entity.getCoverImageUrl(),
                entity.getAudience(),
                entity.getTakeaway(),
                entity.getIntro(),
                entity.getIcon(),
                fromJson(entity.getPoints()),
                fromJsonValue(entity.getContentSections(), new TypeReference<List<ContentSection>>() {}),
                entity.getContentHtml(),
                fromJson(entity.getRelatedSceneKeys()),
                entity.getSeoTitle(),
                entity.getSeoDescription(),
                entity.getSortOrder(),
                entity.getWebsiteEnabled(),
                entity.getWebsiteFeatured(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private String toJson(List<String> points) {
        if (points == null || points.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(points);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize points: {}", e.getMessage());
            return null;
        }
    }

    private String toJsonValue(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid solution content", e);
        }
    }

    private <T> T fromJsonValue(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            @SuppressWarnings("unchecked")
            T empty = (T) List.of();
            return empty;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse solution JSON: {}", e.getMessage());
            @SuppressWarnings("unchecked")
            T empty = (T) List.of();
            return empty;
        }
    }

    private String normalizeSlug(String slug) {
        if (slug == null || slug.isBlank()) return null;
        String normalized = slug.trim().toLowerCase()
                .replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("^-+|-+$", "");
        return normalized.isBlank() ? null : normalized;
    }

    private List<String> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse points JSON: {}", e.getMessage());
            return List.of();
        }
    }
}
