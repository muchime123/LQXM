package com.liqiang.archive.service;

import com.liqiang.archive.entity.WebsiteMediaAsset;
import com.liqiang.archive.repository.WebsiteMediaAssetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** Uploads only new website-owned files. Internal archive assets are never referenced. */
@Service
@RequiredArgsConstructor
public class WebsiteMediaService {
    private static final long MAX_UPLOAD_BYTES = 512L * 1024 * 1024;
    private static final Map<String, Kind> KINDS = Map.of(
            "image/jpeg", new Kind("images", ".jpg", "IMAGE"),
            "image/png", new Kind("images", ".png", "IMAGE"),
            "image/gif", new Kind("images", ".gif", "IMAGE"),
            "image/webp", new Kind("images", ".webp", "IMAGE"),
            "application/pdf", new Kind("documents", ".pdf", "DOCUMENT"),
            "video/mp4", new Kind("videos", ".mp4", "VIDEO"),
            "video/webm", new Kind("videos", ".webm", "VIDEO")
    );

    private final WebsiteMediaAssetRepository repository;

    @Value("${app.files.root}")
    private String fileRoot;

    public WebsiteMediaAsset upload(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty() || file.getSize() > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("官网媒体文件为空或超过 512 MB");
        }
        String original = cleanFilename(file.getOriginalFilename());
        Path appRoot = Path.of(fileRoot).toAbsolutePath().normalize();
        Files.createDirectories(appRoot);
        Path websiteRoot = appRoot.resolve("website");
        Files.createDirectories(websiteRoot);
        if (!websiteRoot.toRealPath().startsWith(appRoot.toRealPath())) {
            throw new IOException("官网媒体目录超出文件存储根目录");
        }
        Path temporary = Files.createTempFile(websiteRoot, ".upload-", ".tmp");
        try {
            MessageDigest digest = sha256();
            try (InputStream source = file.getInputStream();
                 DigestInputStream hashed = new DigestInputStream(source, digest);
                 var target = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[1024 * 1024];
                long count = 0;
                for (int read; (read = hashed.read(buffer)) != -1; ) {
                    count += read;
                    if (count > MAX_UPLOAD_BYTES) {
                        throw new IllegalArgumentException("官网媒体文件超过 512 MB");
                    }
                    target.write(buffer, 0, read);
                }
            }
            String mime = detectMime(temporary);
            Kind kind = KINDS.get(mime);
            String hash = HexFormat.of().formatHex(digest.digest());
            String storageKey = "website/" + kind.directory() + "/" + hash + kind.extension();
            Path destination = websiteRoot.resolve(kind.directory()).resolve(hash + kind.extension());
            Files.createDirectories(destination.getParent());
            if (!destination.getParent().toRealPath().startsWith(websiteRoot.toRealPath())
                    || Files.isSymbolicLink(destination)) {
                throw new IOException("官网媒体目标路径超出专用目录");
            }
            try {
                Files.copy(temporary, destination);
            } catch (FileAlreadyExistsException ignored) {
                // Another upload already stored the same content-addressed file.
            }
            if (!hash.equals(hashFile(destination))) {
                throw new IOException("官网媒体目标文件校验失败");
            }
            WebsiteMediaAsset existing = repository.findByStorageKeyAndDeletedAtIsNull(storageKey).orElse(null);
            if (existing != null) {
                return existing;
            }
            WebsiteMediaAsset asset = new WebsiteMediaAsset();
            asset.setStorageKey(storageKey);
            asset.setOriginalFilename(original);
            asset.setDisplayName(original);
            asset.setMediaType(kind.mediaType());
            asset.setMimeType(mime);
            asset.setSizeBytes(Files.size(destination));
            asset.setSha256(hash);
            asset.setStatus("DRAFT");
            return repository.save(asset);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String cleanFilename(String name) {
        if (name == null || name.isBlank()) {
            return "upload";
        }
        String normalized = name.replace('\\', '/');
        String basename = normalized.substring(normalized.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_");
        return basename.isBlank() ? "upload" : basename.substring(0, Math.min(basename.length(), 500));
    }

    private static String detectMime(Path file) throws IOException {
        byte[] bytes;
        try (InputStream input = Files.newInputStream(file)) {
            bytes = input.readNBytes(16);
        }
        if (bytes.length >= 8 && starts(bytes, new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})) return "image/png";
        if (bytes.length >= 3 && starts(bytes, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff})) return "image/jpeg";
        if (bytes.length >= 6 && new String(bytes, 0, 3, java.nio.charset.StandardCharsets.US_ASCII).equals("GIF")) return "image/gif";
        if (bytes.length >= 12 && new String(bytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")
                && new String(bytes, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP")) return "image/webp";
        if (bytes.length >= 5 && starts(bytes, new byte[]{0x25, 0x50, 0x44, 0x46, 0x2d})) return "application/pdf";
        if (bytes.length >= 8 && new String(bytes, 4, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("ftyp")) return "video/mp4";
        if (bytes.length >= 4 && starts(bytes, new byte[]{0x1a, 0x45, (byte) 0xdf, (byte) 0xa3})) return "video/webm";
        throw new IllegalArgumentException("不支持的官网媒体文件格式");
    }

    private static boolean starts(byte[] bytes, byte[] prefix) {
        for (int i = 0; i < prefix.length; i++) if (bytes[i] != prefix[i]) return false;
        return true;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hashFile(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (DigestInputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private record Kind(String directory, String extension, String mediaType) {}
}
