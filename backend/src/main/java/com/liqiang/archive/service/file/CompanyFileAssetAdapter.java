package com.liqiang.archive.service.file;

import com.liqiang.archive.dto.FileAssetCapabilities;
import com.liqiang.archive.dto.FileDtos.Capability;
import com.liqiang.archive.dto.FileDtos.FileHandle;
import com.liqiang.archive.dto.FileDtos.OwnerType;
import com.liqiang.archive.entity.CompanyAccount;
import com.liqiang.archive.entity.CompanyAsset;
import com.liqiang.archive.entity.CompanyCertification;
import com.liqiang.archive.entity.CompanyTeamMember;
import com.liqiang.archive.repository.CompanyAccountRepository;
import com.liqiang.archive.repository.CompanyAssetRepository;
import com.liqiang.archive.repository.CompanyCertificationRepository;
import com.liqiang.archive.repository.CompanyTeamMemberRepository;
import com.liqiang.archive.service.CompanyInfoService;
import com.liqiang.archive.service.FileHandleMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class CompanyFileAssetAdapter extends AbstractFileAssetAdapter<CompanyAsset, CompanyAssetRepository> {

    private static final Set<Capability> CAPABILITIES = FileAssetCapabilities.COMPANY_ASSET;
    private static final long ACCOUNT_QR_BASE = -1_000_000_000_000L;
    private static final long TEAM_PHOTO_BASE = -2_000_000_000_000L;
    private static final long CERT_FILE_BASE = -3_000_000_000_000L;
    private static final long CERT_FILE_FLOOR = -4_000_000_000_000L;
    private static final int FILES_PER_CERT = 100;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final CompanyInfoService companyInfoService;
    private final CompanyAccountRepository accountRepository;
    private final CompanyTeamMemberRepository teamMemberRepository;
    private final CompanyCertificationRepository certificationRepository;

    public CompanyFileAssetAdapter(CompanyAssetRepository assetRepository,
                                   FileHandleMapper fileHandleMapper,
                                   CompanyInfoService companyInfoService,
                                   CompanyAccountRepository accountRepository,
                                   CompanyTeamMemberRepository teamMemberRepository,
                                   CompanyCertificationRepository certificationRepository) {
        super(assetRepository, fileHandleMapper);
        this.companyInfoService = companyInfoService;
        this.accountRepository = accountRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.certificationRepository = certificationRepository;
    }

    @Override
    public OwnerType ownerType() {
        return OwnerType.COMPANY;
    }

    @Override
    public Set<Capability> capabilities() {
        return CAPABILITIES;
    }

    @Override
    public FileHandle get(Long assetId) {
        if (isSyntheticAccountQrId(assetId)) {
            CompanyAccount account = findAccount(decodeAccountQrId(assetId));
            if (isBlank(account.getQrCodeFile())) {
                throw new EntityNotFoundException("Company account QR file does not exist: " + assetId);
            }
            return FileHandle.companyAccountQrFile(account, assetId);
        }
        if (isSyntheticTeamPhotoId(assetId)) {
            CompanyTeamMember member = findTeamMember(decodeTeamPhotoId(assetId));
            if (isBlank(member.getPhotoFileName())) {
                throw new EntityNotFoundException("Company team photo does not exist: " + assetId);
            }
            return FileHandle.companyTeamPhotoFile(member, assetId);
        }
        if (isSyntheticCertFileId(assetId)) {
            long certId = decodeCertId(assetId);
            int index = decodeCertIndex(assetId);
            CompanyCertification cert = findCertification(certId);
            List<String> names = parseFileNames(cert.getFileName());
            if (index < 0 || index >= names.size()) {
                throw new EntityNotFoundException("Company certification file index out of range: " + assetId);
            }
            return FileHandle.companyCertificationFile(cert, assetId, names.get(index));
        }
        return fileHandleMapper.from(findEntity(assetId, "公司素材"));
    }

    @Override
    public List<FileHandle> list(Long ownerId) {
        List<FileHandle> handles = new ArrayList<>(companyInfoService.getAssets(null)
                .stream()
                .map(fileHandleMapper::from)
                .toList());
        accountRepository.findAllByOrderBySortOrderAsc().stream()
                .filter(account -> !isBlank(account.getQrCodeFile()))
                .map(account -> FileHandle.companyAccountQrFile(account, encodeAccountQrId(account.getId())))
                .forEach(handles::add);
        teamMemberRepository.findAllByOrderBySortOrderAsc().stream()
                .filter(member -> !isBlank(member.getPhotoFileName()))
                .map(member -> FileHandle.companyTeamPhotoFile(member, encodeTeamPhotoId(member.getId())))
                .forEach(handles::add);
        certificationRepository.findAll().stream()
                .forEach(cert -> {
                    List<String> names = parseFileNames(cert.getFileName());
                    for (int i = 0; i < names.size(); i++) {
                        handles.add(FileHandle.companyCertificationFile(cert, encodeCertFileId(cert.getId(), i), names.get(i)));
                    }
                });
        return handles;
    }

    @Override
    public FileHandle updateTags(Long assetId, String tags, boolean replace) {
        CompanyAsset asset = findEntity(assetId, "公司素材");
        String nextTags = mergeTags(asset.getTags(), tags, replace);
        return fileHandleMapper.from(companyInfoService.updateAssetTags(assetId, nextTags));
    }

    @Override
    public FileHandle setDefault(Long assetId) {
        return fileHandleMapper.from(companyInfoService.setDefaultAsset(assetId));
    }

    @Override
    public void reorder(List<FileAssetAdapter.ReorderItem> items) {
        List<Map<String, Object>> payload = items.stream()
                .map(item -> Map.<String, Object>of("id", item.id(), "sortOrder", item.sortOrder()))
                .toList();
        companyInfoService.reorderAssets(payload);
    }

    @Override
    public void moveCategory(Long assetId, String targetCategory) {
        companyInfoService.batchMoveCategory(List.of(assetId), targetCategory);
    }

    @Override
    public void softDelete(Long assetId) {
        if (isSyntheticAccountQrId(assetId)) {
            CompanyAccount account = findAccount(decodeAccountQrId(assetId));
            account.setQrCodeFile(null);
            accountRepository.save(account);
            return;
        }
        if (isSyntheticTeamPhotoId(assetId)) {
            CompanyTeamMember member = findTeamMember(decodeTeamPhotoId(assetId));
            member.setPhotoFileName(null);
            teamMemberRepository.save(member);
            return;
        }
        if (isSyntheticCertFileId(assetId)) {
            long certId = decodeCertId(assetId);
            int index = decodeCertIndex(assetId);
            CompanyCertification cert = findCertification(certId);
            List<String> names = new ArrayList<>(parseFileNames(cert.getFileName()));
            if (index >= 0 && index < names.size()) {
                names.remove(index);
                cert.setFileName(writeFileNames(names));
                certificationRepository.save(cert);
            }
            return;
        }
        companyInfoService.softDeleteAsset(assetId);
    }

    @Override
    public void restore(Long assetId) {
        companyInfoService.restoreAsset(assetId);
    }

    @Override
    public void permanentDelete(Long assetId, boolean deletePhysical) {
        companyInfoService.permanentDeleteAsset(assetId, deletePhysical);
    }

    @Override
    public FileHandle setCover(Long assetId, String coverFileName) {
        // synthetic IDs（account QR、team photo、cert file）不支持封面
        if (isSyntheticAccountQrId(assetId) || isSyntheticTeamPhotoId(assetId) || isSyntheticCertFileId(assetId)) {
            throw new UnsupportedOperationException("该类型文件不支持自定义封面");
        }
        CompanyAsset asset = findEntity(assetId, "公司素材");
        asset.setCoverFileName(coverFileName);
        return fileHandleMapper.from(companyInfoService.updateAsset(assetId, asset));
    }

    private CompanyAccount findAccount(Long id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Company account does not exist: " + id));
    }

    private CompanyTeamMember findTeamMember(Long id) {
        return teamMemberRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Company team member does not exist: " + id));
    }

    private CompanyCertification findCertification(Long id) {
        return certificationRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Company certification does not exist: " + id));
    }

    private static long encodeAccountQrId(Long accountId) {
        return ACCOUNT_QR_BASE - accountId;
    }

    private static long encodeTeamPhotoId(Long memberId) {
        return TEAM_PHOTO_BASE - memberId;
    }

    private static long encodeCertFileId(Long certId, int index) {
        return CERT_FILE_BASE - certId * FILES_PER_CERT - index;
    }

    private static long decodeAccountQrId(Long assetId) {
        return ACCOUNT_QR_BASE - assetId;
    }

    private static long decodeTeamPhotoId(Long assetId) {
        return TEAM_PHOTO_BASE - assetId;
    }

    private static long decodeCertId(Long assetId) {
        long offset = CERT_FILE_BASE - assetId;
        return offset / FILES_PER_CERT;
    }

    private static int decodeCertIndex(Long assetId) {
        long offset = CERT_FILE_BASE - assetId;
        return (int)(offset % FILES_PER_CERT);
    }

    private static boolean isSyntheticAccountQrId(Long assetId) {
        return assetId != null && assetId < ACCOUNT_QR_BASE && assetId > TEAM_PHOTO_BASE;
    }

    private static boolean isSyntheticTeamPhotoId(Long assetId) {
        return assetId != null && assetId < TEAM_PHOTO_BASE && assetId > CERT_FILE_BASE;
    }

    private static boolean isSyntheticCertFileId(Long assetId) {
        return assetId != null && assetId < CERT_FILE_BASE && assetId > CERT_FILE_FLOOR;
    }

    /** 把 cert.fileName 解析为文件名列表（兼容旧版单文件字符串和 JSON 数组格式）。 */
    private static List<String> parseFileNames(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) return List.of();
        String trimmed = fileName.trim();
        if (trimmed.startsWith("[")) {
            try {
                List<String> parsed = JSON.readValue(trimmed, new TypeReference<List<String>>() {});
                return parsed == null ? List.of() : parsed;
            } catch (Exception e) {
                return List.of(fileName);
            }
        }
        return List.of(fileName);
    }

    /** 把文件名列表序列化为 cert.fileName 字段值。 */
    private static String writeFileNames(List<String> names) {
        if (names == null || names.isEmpty()) return null;
        if (names.size() == 1) return names.get(0);
        try {
            return JSON.writeValueAsString(names);
        } catch (Exception e) {
            return names.get(0);
        }
    }

}
