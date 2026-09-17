package com.liqiang.archive.service.file;

import com.liqiang.archive.dto.FileDtos.FileHandle;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Set;

/**
 * 文件缩略图生成服务。
 *
 * <p>支持类型：
 * <ul>
 *   <li>图片：jpg/jpeg/png/gif/bmp/webp/tiff — 缩放到 240x240 内</li>
 *   <li>PDF：首页渲染为 240x240 jpg（PDFBox）</li>
 * </ul>
 *
 * <p>缓存策略：基于 (file path + mtime + size + size_px) 的 SHA-256 作为 key，
 * 缩略图统一存到 {@code fileRoot/.thumbnails/{key}.jpg}。
 * 文件修改/替换后 key 自动失效，无需主动清理。
 */
@Service
public class ThumbnailService {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailService.class);

    /** 默认缩略图最大边长（px）。短边按比例缩放，长边裁剪到本值附近。 */
    public static final int DEFAULT_THUMBNAIL_SIZE = 240;

    /** 缩略图尺寸上限，防止前端传入过大值导致 OOM 或磁盘占用爆炸。 */
    public static final int MAX_THUMBNAIL_SIZE = 1280;

    /** 兼容旧调用：等同于 {@link #DEFAULT_THUMBNAIL_SIZE}。 */
    public static final int THUMBNAIL_SIZE = DEFAULT_THUMBNAIL_SIZE;

    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".tif", ".tiff"
    );

    private final FileAssetLocationService locationService;
    private final Path thumbnailDir;
    private final Path fileRoot;

    public ThumbnailService(FileAssetLocationService locationService) {
        this.locationService = locationService;
        this.fileRoot = locationService != null
                ? locationService.fileRoot()
                : null;
        this.thumbnailDir = fileRoot != null
                ? fileRoot.resolve(".thumbnails")
                : null;
    }

    /**
     * 返回已生成（或新建）的缩略图文件路径（默认尺寸 {@value #DEFAULT_THUMBNAIL_SIZE}）。
     *
     * @throws UnsupportedOperationException 文件类型不支持生成缩略图
     * @throws IOException 文件读取或写入失败
     */
    public Path getOrCreateThumbnail(FileHandle handle) throws IOException {
        return getOrCreateThumbnail(handle, DEFAULT_THUMBNAIL_SIZE);
    }

    /**
     * 返回已生成（或新建）的指定尺寸缩略图文件路径。
     *
     * <p>{@code size} 为目标最大边长（px），会被 clamp 到 [{@value #DEFAULT_THUMBNAIL_SIZE}, {@value #MAX_THUMBNAIL_SIZE}]。
     * 不同 size 会产生不同缓存文件，互不影响。
     *
     * @throws UnsupportedOperationException 文件类型不支持生成缩略图
     * @throws IOException 文件读取或写入失败
     */
    public Path getOrCreateThumbnail(FileHandle handle, Integer size) throws IOException {
        if (handle == null || handle.fileName() == null || handle.fileName().isBlank()) {
            throw new IllegalArgumentException("fileName is required");
        }
        int targetSize = clampSize(size);
        Path source = locationService.resolveExistingPath(handle);

        String ext = extensionOf(handle.fileName()).toLowerCase(Locale.ROOT);
        if (!isSupported(ext)) {
            throw new UnsupportedOperationException("缩略图暂不支持此文件类型: " + ext);
        }

        Files.createDirectories(thumbnailDir);
        String key = cacheKey(source, ext, targetSize);
        Path target = thumbnailDir.resolve(key + ".jpg");
        if (Files.exists(target) && Files.isRegularFile(target)) {
            return target;
        }

        Path tmp = thumbnailDir.resolve(key + ".tmp");
        try {
            if (ext.equals(".pdf")) {
                renderPdfFirstPage(source, tmp, targetSize);
            } else {
                scaleImage(source, tmp, ext, targetSize);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable t) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { /* best effort */ }
            throw t;
        }
        return target;
    }

    /** 将前端传入的尺寸值规整到合法范围。null/非法值回落到默认尺寸。 */
    private static int clampSize(Integer size) {
        if (size == null || size <= 0) return DEFAULT_THUMBNAIL_SIZE;
        return Math.min(Math.max(size, DEFAULT_THUMBNAIL_SIZE), MAX_THUMBNAIL_SIZE);
    }

    public boolean isSupported(String ext) {
        if (ext == null || ext.isBlank()) return false;
        String normalized = ext.startsWith(".") ? ext.toLowerCase(Locale.ROOT) : "." + ext.toLowerCase(Locale.ROOT);
        return IMAGE_EXTENSIONS.contains(normalized) || ".pdf".equals(normalized);
    }

    private void scaleImage(Path source, Path target, String ext, int size) throws IOException {
        BufferedImage original;
        try (InputStream in = Files.newInputStream(source)) {
            original = ImageIO.read(in);
        }
        if (original == null) {
            throw new IOException("无法读取图片: " + source.getFileName());
        }
        BufferedImage thumb = scaleInto(original, size);
        writeJpeg(thumb, target);
    }

    private void renderPdfFirstPage(Path source, Path target, int size) throws IOException {
        try (PDDocument doc = Loader.loadPDF(Files.readAllBytes(source))) {
            if (doc.getNumberOfPages() == 0) {
                throw new IOException("PDF 没有可渲染的页面: " + source.getFileName());
            }
            PDFRenderer renderer = new PDFRenderer(doc);
            BufferedImage page = renderer.renderImageWithDPI(0, 144, ImageType.RGB);
            try {
                BufferedImage thumb = scaleInto(page, size);
                writeJpeg(thumb, target);
            } finally {
                page.flush();
            }
        }
    }

    private static BufferedImage scaleInto(BufferedImage source, int maxSize) {
        int w = source.getWidth();
        int h = source.getHeight();
        if (w <= 0 || h <= 0) {
            throw new IllegalStateException("图片尺寸不合法: " + w + "x" + h);
        }
        double scale = Math.min((double) maxSize / w, (double) maxSize / h);
        if (scale >= 1.0) {
            return source; // 原图已经够小，不做处理
        }
        int newW = Math.max(1, (int) Math.round(w * scale));
        int newH = Math.max(1, (int) Math.round(h * scale));

        // 多级渐进式下采样：每次最多缩到 1/2，避免单步双线性在大幅缩小时产生摩尔纹/失真。
        BufferedImage current = source;
        int curW = w;
        int curH = h;
        while (curW > newW * 2 || curH > newH * 2) {
            int halfW = Math.max(newW, curW / 2);
            int halfH = Math.max(newH, curH / 2);
            BufferedImage half = new BufferedImage(halfW, halfH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = half.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.drawImage(current, 0, 0, halfW, halfH, null);
            } finally {
                g.dispose();
            }
            current = half;
            curW = halfW;
            curH = halfH;
        }

        BufferedImage out = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(current, 0, 0, newW, newH, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static void writeJpeg(BufferedImage image, Path target) throws IOException {
        // jpg 不支持 alpha 通道，TYPE_INT_RGB 已经是 RGB，这里无需额外处理。
        // 使用显式 quality参数（0.85），避免 ImageIO 默认低质量（约 0.75）造成可感知模糊。
        javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        try (java.io.OutputStream os = Files.newOutputStream(target);
             javax.imageio.stream.ImageOutputStream ios = ImageIO.createImageOutputStream(os)) {
            writer.setOutput(ios);
            javax.imageio.plugins.jpeg.JPEGImageWriteParam param =
                    (javax.imageio.plugins.jpeg.JPEGImageWriteParam) writer.getDefaultWriteParam();
            param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.85f);
            writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private String cacheKey(Path source, String ext, int sizePx) {
        try {
            long size = Files.size(source);
            long mtime = Files.getLastModifiedTime(source).toMillis();
            String raw = source.toAbsolutePath().normalize() + "|" + size + "|" + mtime + "|" + ext + "|" + sizePx;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return toHex(digest).substring(0, 32);
        } catch (IOException | NoSuchAlgorithmException e) {
            // 退化方案：用绝对路径的 hash，避免阻塞请求
            log.warn("无法计算缩略图缓存 key，使用 fallback: {}", e.getMessage());
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] digest = md.digest((source.toAbsolutePath() + "|" + sizePx).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return toHex(digest).substring(0, 32);
            } catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException("SHA-256 不可用", ex);
            }
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return "";
        return fileName.substring(dot);
    }
}
