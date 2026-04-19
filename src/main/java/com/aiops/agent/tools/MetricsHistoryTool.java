package com.aiops.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Random;

/**
 * 指标历史查询工具
 * 查询 Pod/VM/服务 在指定时间范围内的历史指标数据
 * 用于分析指标趋势、找到异常起始点、进行环比分析
 *
 * 开发阶段返回 Mock 数据，接入真实环境时替换 queryHistory() 里的 Prometheus / VictoriaMetrics 调用
 */
@Slf4j
@Component
public class MetricsHistoryTool implements Tool {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Random random = new Random();

    @Override
    public String name() {
        return "query_metrics_history";
    }

    @Override
    public String description() {
        return "查询指标历史数据。返回指定时间范围内指标的逐分钟/逐秒数据点，用于分析趋势、定位异常起始时间点。适用于判断告警是突发还是渐变、定位故障窗口。";
    }

    @Override
    public Object handle(JsonNode params) throws Exception {
        String target = params.path("target").asText("pod");
        String targetName = params.path("target_name").asText("unknown");
        String metric = params.path("metric").asText("cpu");
        int durationMinutes = params.path("duration_minutes").asInt(30);

        log.info("[Tool] query_metrics_history target={} name={} metric={} duration={}m",
                target, targetName, metric, durationMinutes);

        return queryHistory(target, targetName, metric, durationMinutes);
    }

    private JsonNode queryHistory(String target, String targetName, String metric, int duration) {
        // ─────────────────────────────────────────────────────────
        // 真实实现：Prometheus range query
        //   GET /api/v1/query_range
        //   ?query=<metric_name>{target="$target",name="$targetName"}
        //   &start=<timestamp>
        //   &end=<now>
        //   &step=1m
        // ─────────────────────────────────────────────────────────

        ObjectNode result = mapper.createObjectNode();
        result.put("target", target);
        result.put("target_name", targetName);
        result.put("metric", metric);
        result.put("duration_minutes", duration);
        result.put("source", "mock_prometheus_api");

        ArrayNode dataPoints = mapper.createArrayNode();
        long now = System.currentTimeMillis() / 1000; // seconds
        int stepSeconds = 60; // 1 minute intervals

        // 生成 Mock 时序数据点
        double baseValue = getBaseValue(target, metric);
        double anomalyStart = duration * 0.6; // 异常从 60% 处开始

        for (int i = 0; i < duration; i++) {
            ObjectNode point = mapper.createObjectNode();
            long ts = now - (duration - i) * stepSeconds;

            double value;
            if (i > anomalyStart) {
                // 模拟异常：指标飙升
                double anomaly = (i - anomalyStart) / (duration - anomalyStart);
                value = baseValue + (100 - baseValue) * Math.pow(anomaly, 1.5) * 0.95;
            } else {
                // 正常值：围绕 baseValue 波动
                value = baseValue + (random.nextDouble() - 0.5) * baseValue * 0.3;
            }

            ArrayNode pair = mapper.createArrayNode();
            pair.add(ts);
            pair.add(String.format("%.2f", value));

            ObjectNode sample = mapper.createObjectNode();
            sample.set("timestamp", pair.get(0));
            sample.put("value", Double.parseDouble(pair.get(1).asText()));
            dataPoints.add(sample);
        }

        result.set("data_points", dataPoints);

        // 统计摘要
        ObjectNode summary = mapper.createObjectNode();
        summary.put("min", String.format("%.2f", baseValue * 0.6));
        summary.put("max", "98.73");
        summary.put("avg", String.format("%.2f", baseValue + 20));
        summary.put("anomaly_start_minute", String.format("%.0f", anomalyStart));
        result.set("summary", summary);

        return result;
    }

    private double getBaseValue(String target, String metric) {
        if (metric.contains("cpu") || metric.contains("Cpu")) return 35.0;
        if (metric.contains("memory") || metric.contains("mem")) return 55.0;
        if (metric.contains("latency") || metric.contains("delay")) return 45.0;
        if (metric.contains("error") || metric.contains("fail")) return 2.0;
        if (metric.contains("request") || metric.contains("qps")) return 120.0;
        return 50.0;
    }
}
