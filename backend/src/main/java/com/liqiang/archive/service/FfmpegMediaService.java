package com.liqiang.archive.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * FFmpeg/ffprobe 进程封装：视频媒体信息提取（时长/分辨率）与封面抽帧。
 * 可执行文件路径可在 application.yml 中配置；未就绪时调用方应给出明确提示。
 */
@Service
public class FfmpegMediaService {

    private static final Logger log = LoggerFactory.getLogger(FfmpegMediaService.class);

    /** ffprobe 解析出的媒体信息。 */
    public record MediaInfo(Integer durationSeconds, Integer width, Integer height) {
    }

    private final String ffmpegPath;
    private final String ffprobePath;
    private final int timeoutSeconds;
    private final ObjectMapper objectMapper;

    public FfmpegMediaService(@Value("${app.ffmpeg.path:ffmpeg}") String ffmpegPath,
                              @Value("${app.ffmpeg.probe-path:ffprobe}") String ffprobePath,
                              @Value("${app.ffmpeg.timeout-seconds:60}") int timeoutSeconds,
                              ObjectMapper objectMapper) {
        this.ffmpegPath = ffmpegPath;
        this.ffprobePath = ffprobePath;
        this.timeoutSeconds = timeoutSeconds;
        this.objectMapper = objectMapper;
    }

    /** ffmpeg/ffprobe 是否可执行（存在且可运行）。 */
    public boolean available() {
        try {
            Process p = new ProcessBuilder(ffprobePath, "-version").start();
            boolean finished = p.waitFor(10, TimeUnit.SECONDS);
            if (!finished) p.destroyForcibly();
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            log.warn("ffprobe 不可用 ({}): {}", ffprobePath, e.getMessage());
            return false;
        }
    }

    /**
     * 提取时长（四舍五入到秒）与首个视频流的宽高。
     * @throws IOException 进程执行失败或输出无法解析
     */
    public MediaInfo probe(Path videoFile) throws IOException {
        String json = execForStdout(new ProcessBuilder(
                ffprobePath,
                "-v", "error",
                "-print_format", "json",
                "-show_format", "-show_streams",
                videoFile.toString()));
        JsonNode root = objectMapper.readTree(json);
        Integer duration = null;
        JsonNode durationNode = root.path("format").path("duration");
        if (durationNode.isTextual() || durationNode.isNumber()) {
            duration = (int) Math.round(durationNode.asDouble());
        }
        Integer width = null;
        Integer height = null;
        for (JsonNode stream : root.path("streams")) {
            if ("video".equals(stream.path("codec_type").asText())) {
                if (stream.hasNonNull("width") && stream.hasNonNull("height")
                        && stream.path("width").intValue() > 0
                        && stream.path("height").intValue() > 0) {
                    width = stream.path("width").intValue();
                    height = stream.path("height").intValue();
                    break;
                }
            }
        }
        if (duration == null && width == null) {
            throw new IOException("ffprobe 未返回有效媒体信息");
        }
        return new MediaInfo(duration, width, height);
    }

    /**
     * 从视频抽取一帧作为封面 JPEG。
     * @param atSeconds 抽帧时间点（秒）；自动收敛到 [0, 视频长度内]
     */
    public void extractCoverFrame(Path videoFile, Path outputJpg, double atSeconds) throws IOException {
        double safeAt = Math.max(0, atSeconds);
        Path parent = outputJpg.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        // -ss 放在 -i 前做关键帧快速定位；-q:v 3 兼顾清晰度与体积；-frames:v 1 只取一帧
        execAndCheck(new ProcessBuilder(
                ffmpegPath,
                "-y", "-v", "error",
                "-ss", String.valueOf(safeAt),
                "-i", videoFile.toString(),
                "-frames:v", "1",
                "-q:v", "3",
                outputJpg.toString()));
        if (!Files.exists(outputJpg) || Files.size(outputJpg) == 0) {
            throw new IOException("抽帧未生成文件");
        }
    }

    private String execForStdout(ProcessBuilder pb) throws IOException {
        pb.redirectErrorStream(false);
        Process p = pb.start();
        String stdout;
        String stderr;
        try {
            stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                throw new IOException("ffprobe 执行超时（>" + timeoutSeconds + "s）");
            }
            stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.exitValue() != 0) {
                throw new IOException("ffprobe 失败: " + abbreviate(stderr));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("ffprobe 被中断", e);
        }
        return stdout;
    }

    private void execAndCheck(ProcessBuilder pb) throws IOException {
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try {
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                throw new IOException("ffmpeg 抽帧超时（>" + timeoutSeconds + "s）");
            }
            if (p.exitValue() != 0) {
                throw new IOException("ffmpeg 失败: " + abbreviate(output));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("ffmpeg 被中断", e);
        }
    }

    private String abbreviate(String text) {
        if (text == null) return "";
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() > 300 ? compact.substring(0, 300) + "…" : compact;
    }
}
