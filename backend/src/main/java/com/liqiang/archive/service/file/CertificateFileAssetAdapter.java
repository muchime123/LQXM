package com.liqiang.archive.service.file;

import com.liqiang.archive.dto.FileAssetCapabilities;
import com.liqiang.archive.dto.FileDtos.Capability;
import com.liqiang.archive.dto.FileDtos.FileHandle;
import com.liqiang.archive.dto.FileDtos.OwnerType;
import com.liqiang.archive.entity.Certificate;
import com.liqiang.archive.repository.CertificateRepository;
import com.liqiang.archive.service.FileHandleMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class CertificateFileAssetAdapter extends AbstractFileAssetAdapter<Certificate, CertificateRepository> {

    private static final Set<Capability> CAPABILITIES = FileAssetCapabilities.CERTIFICATE;

    public CertificateFileAssetAdapter(CertificateRepository certificateRepository,
                                       FileHandleMapper fileHandleMapper) {
        super(certificateRepository, fileHandleMapper);
    }

    @Override
    public OwnerType ownerType() {
        return OwnerType.CERTIFICATE;
    }

    @Override
    public Set<Capability> capabilities() {
        return CAPABILITIES;
    }

    @Override
    public List<FileHandle> list(Long ownerId) {
        List<Certificate> certificates = ownerId == null || ownerId <= 0
                ? repository.findAll()
                : List.of(findEntity(ownerId, "Certificate"));
        return certificates.stream()
                .flatMap(certificate -> handles(certificate).stream())
                .toList();
    }

    @Override
    public FileHandle get(Long assetId) {
        CertificateField field = decode(assetId);
        Certificate certificate = findEntity(field.certificateId(), "Certificate");
        return switch (field.fieldName()) {
            case "specFile" -> handle(certificate, "specFile", certificate.getSpecFile());
            case "certFile" -> handle(certificate, "certFile", certificate.getCertFile());
            default -> throw new IllegalArgumentException("Unsupported certificate file field: " + field.fieldName());
        };
    }

    @Override
    @Transactional
    public void softDelete(Long assetId) {
        CertificateField field = decode(assetId);
        Certificate certificate = findEntity(field.certificateId(), "Certificate");
        if ("specFile".equals(field.fieldName())) {
            certificate.setSpecFile(null);
        } else if ("certFile".equals(field.fieldName())) {
            certificate.setCertFile(null);
        } else {
            throw new IllegalArgumentException("Unsupported certificate file field: " + field.fieldName());
        }
        repository.save(certificate);
    }

    private List<FileHandle> handles(Certificate certificate) {
        List<FileHandle> handles = new ArrayList<>();
        FileHandle spec = handle(certificate, "specFile", certificate.getSpecFile());
        if (spec != null) handles.add(spec);
        FileHandle cert = handle(certificate, "certFile", certificate.getCertFile());
        if (cert != null) handles.add(cert);
        return handles;
    }

    private FileHandle handle(Certificate certificate, String field, String fileName) {
        if (isBlank(fileName)) {
            return null;
        }
        return FileHandle.certificateFile(certificate, field, fileName);
    }

    private CertificateField decode(Long syntheticAssetId) {
        if (syntheticAssetId == null || syntheticAssetId < 10) {
            throw new IllegalArgumentException("Invalid certificate file asset id: " + syntheticAssetId);
        }
        long suffix = syntheticAssetId % 10;
        long certificateId = syntheticAssetId / 10;
        if (suffix == 1) {
            return new CertificateField(certificateId, "specFile");
        }
        if (suffix == 2) {
            return new CertificateField(certificateId, "certFile");
        }
        throw new IllegalArgumentException("Invalid certificate file asset id: " + syntheticAssetId);
    }

    private record CertificateField(Long certificateId, String fieldName) {
    }
}
