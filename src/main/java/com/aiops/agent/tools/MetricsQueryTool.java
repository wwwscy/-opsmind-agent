package com.aiops.agent.tools;
import com.fasterxml.jackson.databind.JsonNode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 指标查询工具
 * 查询 Pod / VM / 物理机的 CPU、内存、延迟、错误率等指标
 *
 * 当前为 Mock 实现，接入真实环境时替换 queryMetrics() 里的 HTTP 调用即可
 */
@Slf4j
@Component
public class MetricsQueryTool implements Tool {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String name() {
        return "query_metrics";
    }

    @Override
    public String description() {
        return "查询 Pod/VM/物理机的运行指标，包括 CPU、内存、延迟、错误率等。适用于诊断性能问题和资源瓶颈。";
    }

    @Override
    public Object handle(com.fasterxml.jackson.databind.JsonNode params) throws Exception {
        String target = params.path("target").asText();          // pod | vm | physical
        String targetName = params.path("target_name").asText(); // 具体名称，如 nginx-7d9f8c
        String metric = params.path("metric").asText();          // cpu | memory | latency | error_rate
        int durationMinutes = params.path("duration_minutes").asInt(10);

        // TODO: 接入真实 Prometheus / 华为云 APM API
        // 这里先返回 Mock 数据，验证 ReAct 流程能跑通
        JsonNode result = queryMetrics(target, targetName, metric, durationMinutes);
        return result;
    }

    private JsonNode queryMetrics(String target, String targetName, String metric, int duration) {
        // ─────────────────────────────────────────────────────────
        // 真实实现：调用 Prometheus HTTP API 或华为云 APM 接口
        // 示例 Prometheus API:
        // GET /api/v1/query_range?query={target="$target",name="$targetName"}&start=...&end=...&step=1m
        // 返回格式：{"status":"success","data":{"result":[{"values":[[timestamp, value], ...]}]}}
        // ─────────────────────────────────────────────────────────

        ObjectNode data = mapper.createObjectNode();
        data.put("target", target);
        data.put("target_name", targetName);
        data.put("metric", metric);
        data.put("duration_minutes", duration);

        // Mock 返回值，真实场景替换为 Prometheus/Huawei Cloud APM HTTP 调用结果
        ObjectNode metrics = mapper.createObjectNode();
        metrics.put("current_value", "98%");
        metrics.put("avg_value", "45%");
        metrics.put("max_value", "99%");
        metrics.put("trend", "rising");       // rising | stable | falling
        metrics.put("alert_threshold", "90%");
        metrics.put("duration_over_threshold", "8 minutes");

        ObjectNode root = mapper.createObjectNode();
        root.set("data", data);
        root.set("metrics", metrics);
        root.put("source", "mock_prometheus_api");

        return root;
    }
}
