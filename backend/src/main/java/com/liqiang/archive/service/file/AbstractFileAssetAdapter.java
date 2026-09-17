package com.liqiang.archive.service.file;

import com.liqiang.archive.dto.FileDtos.FileHandle;
import com.liqiang.archive.service.FileHandleMapper;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.function.Function;

public abstract class AbstractFileAssetAdapter<E, R extends JpaRepository<E, Long>> implements FileAssetAdapter {

    protected final R repository;
    protected final FileHandleMapper fileHandleMapper;

    protected AbstractFileAssetAdapter(R repository, FileHandleMapper fileHandleMapper) {
        this.repository = repository;
        this.fileHandleMapper = fileHandleMapper;
    }

    protected E findEntity(Long id, String entityName) {
        return repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(entityName + "不存在: " + id));
    }

    protected E findEntity(Long id) {
        return findEntity(id, "实体");
    }

    protected List<FileHandle> listAllRecent(Function<E, FileHandle> mapper) {
        return repository.findAll(Sort.by(Sort.Direction.DESC, "updatedAt", "id"))
                .stream()
                .map(mapper)
                .toList();
    }

    protected String mergeTags(String existingTags, String incomingTags, boolean replace) {
        if (replace || isBlank(existingTags)) {
            return incomingTags == null ? "" : incomingTags;
        }
        if (isBlank(incomingTags)) {
            return existingTags;
        }
        return existingTags + "," + incomingTags;
    }

    protected boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    public abstract FileHandle get(Long assetId);
}
