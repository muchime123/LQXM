package com.liqiang.archive.dto;

import java.time.LocalDateTime;

/**
 * Office 预览异步转换状态响应。
 *
 * <p>状态机：{@code none}(无状态文件) → {@code pending}(转换中) → {@code done}|{@code error}。
 * 前端轮询此状态：{@code pending} 继续轮询，{@code done} 加载 iframe，{@code error} 走下载兜底。
 *
 * @param status       pending | done | error | none
 * @param documentId   缓存目录使用的稳定文件标识（FileController 根据文件生成 hash）
 * @param startedAt    转换开始时间（用于 stale 判定）
 * @param updatedAt    状态最后更新时间
 * @param errorMessage error 时的失败原因
 * @param pdfReady     done 且缓存文件就绪可被 iframe 加载
 */
public record OfficePreviewStatus(
        String status,
        Long documentId,
        LocalDateTime startedAt,
        LocalDateTime updatedAt,
        String errorMessage,
        boolean pdfReady
) {
}
