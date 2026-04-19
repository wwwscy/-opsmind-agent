package com.aiops.agent.monitor;

import com.aiops.agent.monitor.entity.AlertRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 飞书通知服务
 * - 富文本卡片格式（颜色标签/字段/分割线）
 * - 告警收敛：同一 metric 在 suppress 分钟内不重复推送
 */
@Slf4j
@Service
public class FeishuNotificationService {

    @Value("${monitoring.feishu-webhook}")
    private String feishuWebhook;

    @Value("${monitoring.feishu-enabled:true}")
    private boolean enabled;

    @Value("${monitoring.feishu-suppress-minutes:10}")
    private int suppressMinutes;

    private final ConcurrentHashMap<String, Long> lastNotified = new ConcurrentHashMap<>();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private static final DateTimeFormatter DFT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 富文本卡片推送（新版） */
    public boolean send(AlertRecord alert, String diagnosis, String recovery) {
        if (!enabled) return true;
        String key = alert.getMetricName() != null ? alert.getMetricName() : "unknown";
        long now = System.currentTimeMillis();
        Long last = lastNotified.get(key);
        if (last != null && (now - last) < suppressMinutes * 60_000L) {
            log.info("[Feishu] 收敛抑制 metric={}", key);
            return true;
        }
        try {
            String json = buildCard(alert, diagnosis, recovery);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(feishuWebhook))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            boolean ok = resp.body() != null && resp.body().contains("\"code\":0");
            if (ok) lastNotified.put(key, now);
            log.info("[Feishu] metric={} result={}", key, ok ? "OK" : "FAIL");
            return ok;
        } catch (Exception e) {
            log.error("[Feishu] metric={} error={}", key, e.getMessage());
            return false;
        }
    }

    /** 旧文本接口（兼容） */
    public boolean send(String text) {
        if (!enabled) return true;
        try {
            String json = "{\"msg_type\":\"text\",\"content\":{\"text\":\""
                    + escapeJson(cleanText(text)) + "\"}}";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(feishuWebhook))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.body() != null && resp.body().contains("\"code\":0");
        } catch (Exception e) {
            log.error("[Feishu] send(text) failed: {}", e.getMessage());
            return false;
        }
    }

    // ── 构建飞书卡片 ──────────────────────────────────────

    private String buildCard(AlertRecord a, String diagnosis, String recovery) throws Exception {
        String sev = a.getSeverity() != null ? a.getSeverity() : "medium";
        String color = switch (sev) { case "critical" -> "red"; case "high" -> "orange"; case "medium" -> "yellow"; default -> "blue"; };
        String emoji = switch (sev) { case "critical" -> "CRITICAL"; case "high" -> "HIGH"; case "medium" -> "MEDIUM"; default -> "LOW"; };

        ObjectNode root = mapper.createObjectNode();
        root.put("msg_type", "interactive");
        ObjectNode card = mapper.createObjectNode();

        ObjectNode header = mapper.createObjectNode();
        header.putObject("title").put("tag", "plain_text").put("content", emoji + " " + a.getAlertName() + " 告警通知");
        header.put("template", color);
        card.set("header", header);

        ArrayNode elems = mapper.createArrayNode();
        elems.add(md(emoji + " **告警级别** " + sev.toUpperCase()));
        elems.add(md("**指标** " + nf(a.getMetricName())));
        elems.add(md("**当前值** " + fv(a.getMetricValue())));
        elems.add(md("**阈值** " + nf(a.getCondition()) + fv(a.getThresholdValue())));
        elems.add(md("**时间** " + (a.getTriggeredAt() != null ? a.getTriggeredAt().format(DFT) : "-")));
        elems.add(md("**目标** " + nf(a.getTarget())));
        elems.add(hr());
        if (diagnosis != null && !diagnosis.isEmpty()) {
            String d = diagnosis.length() > 800 ? diagnosis.substring(0, 800) + "\n...[截断]" : diagnosis;
            elems.add(md("**AI 诊断结论**\n" + d));
        }
        if (recovery != null && !recovery.isEmpty()) {
            String r = recovery.length() > 400 ? recovery.substring(0, 400) + "\n..." : recovery;
            elems.add(md("**处理建议**\n" + r));
        }
        if (a.getDiagnosisDurationMs() != null && a.getDiagnosisDurationMs() > 0) {
            elems.add(md("**诊断耗时** " + (a.getDiagnosisDurationMs() / 1000.0) + "s"));
        }
        elems.add(hr());
        elems.add(md("[查看详情](http://localhost:8080/) | [AI 对话](http://localhost:8080/#chat)"));
        card.set("elements", elems);
        root.set("card", card);
        return mapper.writeValueAsString(root);
    }

    private ObjectNode md(String text) {
        return mapper.createObjectNode().put("tag", "markdown").put("content", text);
    }

    private ObjectNode hr() {
        return mapper.createObjectNode().put("tag", "hr");
    }

    private String nf(String s) { return s != null ? s : "-"; }
    private String fv(Double v) { return v != null ? (v > 100 ? String.format("%.0f", v) : String.format("%.2f", v)) : "-"; }

    private String cleanText(String t) {
        if (t == null) return "";
        return t.replaceAll("[\\x00-\\x1F\\x7F]", "").replaceAll("\\{3,}", "\n\n").trim();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
