package com.liqiang.archive.service;

import com.liqiang.archive.dto.FileDtos.FileHandle;
import com.liqiang.archive.entity.CompanyAsset;
import com.liqiang.archive.entity.PackagingAsset;
import com.liqiang.archive.entity.ProductAsset;
import org.springframework.stereotype.Component;

@Component
public class FileHandleMapper {

    public FileHandle from(ProductAsset asset) {
        return FileHandle.from(asset);
    }

    public FileHandle from(PackagingAsset asset) {
        return FileHandle.from(asset);
    }

    public FileHandle from(CompanyAsset asset) {
        return FileHandle.from(asset);
    }

}
