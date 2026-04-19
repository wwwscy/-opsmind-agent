package com.aiops.agent.monitor;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 指标采集器
 *
 * 定时从 Micrometer 采集应用指标（JVM / 自定义），
 * 与告警规则匹配，判断是否触发告警
 *
 * 同时暴露 Prometheus 格式端点：GET /actuator/prometheus
 */
@Slf4j
@Service
public class MetricsCollector {

    private final MeterRegistry meterRegistry;

    /** 记录每个指标的持续超限时间（秒） */
    private final Map<String, Integer> alertDurations = new ConcurrentHashMap<>();

    public MetricsCollector(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 定时采集指标并检查告警（每 30 秒执行一次）
     */
    @Scheduled(fixedDelayString = "${monitoring.collect-interval-seconds:30}000")
    public void collectAndCheck() {
        try {
            List<MetricSnapshot> snapshots = collectAll();
            log.debug("[Metrics] 采集到 {} 个指标，当前时间: {}", snapshots.size(), new java.util.Date());

            // 输出 CPU 和内存（方便调试）
            for (MetricSnapshot s : snapshots) {
                if (s.name.contains("cpu") || s.name.contains("memory") || s.name.contains("heap")) {
                    log.info("[Metrics] {} = {}", s.name, s.value);
                }
            }
        } catch (Exception e) {
            log.error("[Metrics] 采集失败: {}", e.getMessage());
        }
    }

    /**
     * 采集所有可用指标
     */
    public List<MetricSnapshot> collectAll() {
        List<MetricSnapshot> result = new ArrayList<>();
        for (var meter : meterRegistry.getMeters()) {
            String name = meter.getId().getName();
            Double value = tryGetValue(meter);
            if (value != null && !value.isNaN() && !value.isInfinite()) {
                result.add(new MetricSnapshot(name, value, Tags.of(meter.getId().getTags())));
            }
        }
        return result;
    }

    /**
     * 按指标名查找最新值
     */
    public Optional<Double> getMetric(String metricName) {
        List<Double> values = new ArrayList<>();
        for (var meter : meterRegistry.getMeters()) {
            if (meter.getId().getName().equals(metricName)) {
                Double v = tryGetValue(meter);
                if (v != null) values.add(v);
            }
        }
        if (values.isEmpty()) return Optional.empty();
        // 返回平均值（多实例时）
        return Optional.of(values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN));
    }

    private Double tryGetValue(io.micrometer.core.instrument.Meter meter) {
        if (meter instanceof io.micrometer.core.instrument.Gauge gauge) {
            return gauge.value();
        }
        if (meter instanceof io.micrometer.core.instrument.Counter counter) {
            return counter.count();
        }
        if (meter instanceof io.micrometer.core.instrument.Timer timer) {
            return (double) timer.count();
        }
        if (meter instanceof io.micrometer.core.instrument.FunctionCounter fc) {
            return fc.count();
        }
        if (meter instanceof io.micrometer.core.instrument.FunctionTimer ft) {
            return ft.count();
        }
        return null;
    }

    /**
     * 记录指标超限持续时间
     */
    public void recordAlertDuration(String metricName, boolean isAbove) {
        if (isAbove) {
            alertDurations.merge(metricName, 1, Integer::sum);
        } else {
            alertDurations.remove(metricName);
        }
    }

    public int getAlertDuration(String metricName) {
        return alertDurations.getOrDefault(metricName, 0);
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class MetricSnapshot {
        private String name;
        private Double value;
        private io.micrometer.core.instrument.Tags tags;
    }
}
