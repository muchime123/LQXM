package com.liqiang.archive.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 询盘防刷服务：内存级验证码 + IP 频率限制。
 * 无 Redis 依赖，适用于单实例部署。进程重启后状态清空（可接受）。
 */
@Service
public class InquiryAntiSpamService {

    /** 验证码有效期 5 分钟 */
    private static final long CAPTCHA_TTL_SECONDS = 300;
    /** 单 IP 每小时最多提交次数 */
    private static final int RATE_LIMIT_PER_HOUR = 5;
    /** 限流窗口 1 小时 */
    private static final long RATE_LIMIT_WINDOW_SECONDS = 3600;

    /** captchaId → 验证码条目 */
    private final ConcurrentHashMap<String, CaptchaEntry> captchaStore = new ConcurrentHashMap<>();
    /** IP → 提交时间戳队列 */
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>> rateLimitStore = new ConcurrentHashMap<>();

    // ===== 验证码 =====

    /**
     * 生成数学验证码。
     * @return CaptchaResult(captchaId, question) 例: "3 + 7 = ?"
     */
    public CaptchaResult generateCaptcha() {
        cleanupExpiredCaptchas();
        int a = (int) (Math.random() * 9) + 1;
        int b = (int) (Math.random() * 9) + 1;
        String[] ops = {"+", "-", "×"};
        String op = ops[(int) (Math.random() * ops.length)];
        int answer;
        if ("+".equals(op)) answer = a + b;
        else if ("-".equals(op)) {
            if (a < b) { int t = a; a = b; b = t; }
            answer = a - b;
        } else answer = a * b;

        String captchaId = UUID.randomUUID().toString().replace("-", "");
        String question = a + " " + op + " " + b + " = ?";
        captchaStore.put(captchaId, new CaptchaEntry(answer, Instant.now().plusSeconds(CAPTCHA_TTL_SECONDS)));

        return new CaptchaResult(captchaId, question);
    }

    /**
     * 校验验证码。校验后立即删除（一次性使用）。
     * @return true 通过
     */
    public boolean verifyCaptcha(String captchaId, String answer) {
        if (captchaId == null || answer == null) return false;
        CaptchaEntry entry = captchaStore.remove(captchaId);
        if (entry == null) return false;
        if (Instant.now().isAfter(entry.expiry)) return false;
        try {
            return Integer.parseInt(answer.trim()) == entry.answer;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void cleanupExpiredCaptchas() {
        Instant now = Instant.now();
        captchaStore.entrySet().removeIf(e -> now.isAfter(e.getValue().expiry));
    }

    // ===== IP 限流 =====

    /**
     * 检查 IP 是否超过提交频率限制。
     * @return true 允许提交
     */
    public boolean checkRateLimit(String ip) {
        if (ip == null || ip.isBlank()) return true; // 无法识别 IP 时放行
        Instant now = Instant.now();
        long nowEpoch = now.getEpochSecond();
        long windowStart = nowEpoch - RATE_LIMIT_WINDOW_SECONDS;

        ConcurrentLinkedDeque<Long> timestamps = rateLimitStore.computeIfAbsent(ip, k -> new ConcurrentLinkedDeque<>());
        synchronized (timestamps) {
            // 移除窗口外的时间戳
            while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= RATE_LIMIT_PER_HOUR) {
                return false;
            }
            timestamps.addLast(nowEpoch);
            return true;
        }
    }

    // ===== 内部结构 =====

    private record CaptchaEntry(int answer, Instant expiry) {}

    public record CaptchaResult(String captchaId, String question) {}
}
