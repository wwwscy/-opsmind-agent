package com.aiops.agent.monitor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 飞书通知服务
 */
@Slf4j
@Service
public class FeishuNotificationService {

    @Value("${monitoring.feishu-webhook}")
    private String feishuWebhook;

    @Value("${monitoring.feishu-enabled:true}")
    private boolean enabled;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * 发送文本消息到飞书群
     */
    public boolean send(String content) {
        if (!enabled) return true;

        try {
            // 清理内容
            String clean = cleanContent(content);
            if (clean.length() > 3500) {
                clean = clean.substring(0, 3500) + "\n... [truncated]";
            }

            // 手动拼接 JSON（绕过 Jackson 序列化问题）
            String escaped = escapeJsonString(clean);
            String jsonBody = "{\"msg_type\":\"text\",\"content\":{\"text\":\"" + escaped + "\"}}";

            log.info("[Feishu] Sending {} bytes, preview: {}", jsonBody.length(), clean.substring(0, Math.min(100, clean.length())));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(feishuWebhook))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            String respBody = response.body();
            log.info("[Feishu] HTTP {} resp: {}", response.statusCode(), respBody);

            boolean ok = respBody != null && respBody.contains("\"code\":0");
            log.info("[Feishu] Result: {}", ok ? "SUCCESS" : "FAILED");
            return ok;

        } catch (Exception e) {
            log.error("[Feishu] Exception: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 清理内容，去除可能导致飞书解析失败的字符
     */
    private String cleanContent(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c == '\n') {
                sb.append('\n');
            } else if (c == '\t') {
                sb.append(' ');
            } else if (c < 32 || c == 127) {
                // 控制字符跳过
            } else if (c == '\u2018' || c == '\u2019') {
                sb.append('\'');
            } else if (c == '\u201c' || c == '\u201d') {
                sb.append('"');
            } else if (c == '\u3000') {
                sb.append(' ');
            } else if (c == '\ufeff') {
                // BOM 跳过
            } else {
                sb.append(c);
            }
        }
        return sb.toString()
                .replaceAll("\\n{3,}", "\n\n")
                .replaceAll(" +", " ")
                .trim();
    }

    /**
     * 将字符串中的特殊字符转义为 JSON 安全格式
     */
    private String escapeJsonString(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '"') {
                sb.append("\\\"");
            } else if (c == '\\') {
                sb.append("\\\\");
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c == '\b') {
                sb.append("\\b");
            } else if (c == '\f') {
                sb.append("\\f");
            } else if (c < 32) {
                // 控制字符，跳过
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
