package com.aiops.agent.monitor;

import com.aiops.agent.monitor.entity.AlertRecord;
import com.aiops.agent.monitor.entity.AlertRecordRepository;
import com.aiops.agent.monitor.model.AlertRule;
import com.aiops.agent.rag.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 异常检测引擎
 *
 * 定时扫描指标，对比告警规则，判断是否触发告警
 * 触发后自动调用 AI 诊断，并推送到飞书
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnomalyDetector {

    private final MetricsCollector metricsCollector;
    private final AutoDiagnosisService autoDiagnosisService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final FeishuNotificationService feishuNotificationService;
    private final AlertRecordRepository alertRecordRepository;
    private final MonitoringProperties monitoringProperties;

    /** 正在处理的告警（metric → 活跃任务数），用计数替代是否存在 */
    private final ConcurrentHashMap<String, AtomicInteger> activeAlerts = new ConcurrentHashMap<>();

    /** 上次诊断时间（metric → 上次触发 AI 诊断的时间戳） */
    private final ConcurrentHashMap<String, Long> lastDiagnosed = new ConcurrentHashMap<>();

    /** 诊断线程池（显式而非 daemon ForkJoinPool） */
    private final ExecutorService diagExecutor = Executors.newFixedThreadPool(2);

    /** 重启时清理历史遗留 diagnosing 记录 */
    @PostConstruct
    public void init() {
        log.info("[AnomalyDetector] init, rules={}",
                monitoringProperties.getAlertRules() != null ? monitoringProperties.getAlertRules().size() : 0);
        try {
            alertRecordRepository.findByStatus("diagnosing").forEach(a -> {
                a.setStatus("error");
                a.setDiagnosis("系统重启，诊断中断");
                alertRecordRepository.save(a);
                log.info("[AnomalyDetector] 清理 stale 记录 id={}", a.getId());
            });
        } catch (Exception e) {
            log.warn("[AnomalyDetector] 清理 stale 失败: {}", e.getMessage());
        }
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${monitoring.collect-interval-seconds:30}000")
    public void detect() {
        if (monitoringProperties.getAlertRules() == null || monitoringProperties.getAlertRules().isEmpty()) return;
        for (MonitoringProperties.AlertRuleConfig cfg : monitoringProperties.getAlertRules()) {
            try {
                checkRule(convert(cfg));
            } catch (Exception e) {
                log.error("[AnomalyDetector] rule={} failed: {}", cfg.getName(), e.getMessage());
            }
        }
    }

    private AlertRule convert(MonitoringProperties.AlertRuleConfig cfg) {
        AlertRule r = new AlertRule();
        r.setName(cfg.getName());
        r.setMetric(cfg.getMetric());
        r.setCondition(cfg.getCondition());
        r.setThreshold(cfg.getThreshold());
        r.setDurationSeconds(cfg.getDurationSeconds());
        r.setSeverity(cfg.getSeverity());
        r.setTarget(cfg.getTarget() != null ? cfg.getTarget() : "app");
        return r;
    }

    private void checkRule(AlertRule rule) {
        Optional<Double> opt = metricsCollector.getMetric(rule.getMetric());
        if (opt.isEmpty()) return;

        double val = opt.get();
        boolean triggered = rule.isTriggered(val);

        if (triggered) {
            int dur = metricsCollector.getAlertDuration(rule.getMetric()) + 30;
            if (dur >= rule.getDurationSeconds()) {
                // 原子递增：只有从 0→1 才触发（防止并发重复 FIRE）
                AtomicInteger cnt = activeAlerts.computeIfAbsent(rule.getMetric(), k -> new AtomicInteger(0));
                if (cnt.getAndIncrement() != 0) {
                    log.debug("[AnomalyDetector] 跳过（已有活跃诊断）metric={}", rule.getMetric());
                    return;
                }
                log.warn("[AnomalyDetector] FIRE alert={} metric={} val={}", rule.getName(), rule.getMetric(), val);
                AlertRecord alert = createAlert(rule, val);
                // FIRE 后清除 duration，防止 detect() 周期内重复触发
                metricsCollector.recordAlertDuration(rule.getMetric(), false);
                // 检查是否在抑制期内（同一指标避免重复 AI 诊断）
                if (isSuppressed(rule.getMetric())) {
                    log.info("[AnomalyDetector] 抑制期内，跳过 AI 诊断 metric={}", rule.getMetric());
                    alert.setStatus("pending");
                    alert.setDiagnosis("抑制期内，跳过 AI 诊断");
                    alertRecordRepository.save(alert);
                    // 递减计数
                    AtomicInteger c = activeAlerts.get(rule.getMetric());
                    if (c != null && c.decrementAndGet() == 0) activeAlerts.remove(rule.getMetric());
                    return;
                }
                triggerAsync(alert, rule, val);
            } else {
                metricsCollector.recordAlertDuration(rule.getMetric(), true);
            }
        } else {
            metricsCollector.recordAlertDuration(rule.getMetric(), false);
        }
    }

    /** 检查是否在诊断抑制期内 */
    private boolean isSuppressed(String metric) {
        int suppressMinutes = monitoringProperties.getDiagnosisSuppressMinutes();
        if (suppressMinutes <= 0) return false;
        Long lastTime = lastDiagnosed.get(metric);
        if (lastTime == null) return false;
        return (System.currentTimeMillis() - lastTime) < (suppressMinutes * 60_000L);
    }

    private AlertRecord createAlert(AlertRule rule, double val) {
        AlertRecord a = new AlertRecord();
        a.setAlertName(rule.getName());
        a.setMetricName(rule.getMetric());
        a.setMetricValue(val);
        a.setThresholdValue(rule.getThreshold());
        a.setCondition(rule.getCondition());
        a.setSeverity(rule.getSeverity());
        a.setStatus("pending");
        a.setTarget(rule.getTarget());
        a.setTriggeredAt(LocalDateTime.now());
        a.setNotified(false);
        return alertRecordRepository.save(a);
    }

    private void triggerAsync(AlertRecord alert, AlertRule rule, double val) {
        diagExecutor.submit(() -> {
            try {
                alert.setStatus("diagnosing");
                alertRecordRepository.save(alert);

                String input = rule.getName() + " alert, metric=" + rule.getMetric()
                        + ", current=" + val + ", threshold=" + rule.getCondition() + rule.getThreshold()
                        + ", target=" + rule.getTarget()
                        + ". Analyze root cause and suggest recovery.";
                input += buildMetricContext();

                long start = System.currentTimeMillis();
                DiagnosisResult r = autoDiagnosisService.diagnose(input, null);
                long duration = System.currentTimeMillis() - start;

                alert.setDiagnosis(r.conclusion);
                alert.setRecoverySuggestion(extractRecovery(r.conclusion));
                alert.setDiagnosisDurationMs((int) duration);
                alert.setStatus("resolved");
                log.info("[AnomalyDetector] AI done metric={} in {}ms", rule.getMetric(), duration);

                // 存知识库
                if (r.conclusion != null && !r.conclusion.isEmpty()) {
                    String cause = extractRootCause(r.conclusion);
                    if (!cause.isEmpty()) {
                        List<String> steps = extractRecovery(r.conclusion) != null
                                ? Arrays.asList(extractRecovery(r.conclusion).split("\n"))
                                : Collections.emptyList();
                        knowledgeBaseService.addCase(
                                rule.getName() + " on " + rule.getTarget(), cause, steps, "AI-auto");
                    }
                }

                // 飞书推送
                if (monitoringProperties.isFeishuEnabled()) {
                    boolean ok = feishuNotificationService.send(alert, r.conclusion, extractRecovery(r.conclusion));
                    alert.setNotified(ok);
                    if (ok) log.info("[AnomalyDetector] Feishu sent metric={}", rule.getMetric());
                }

                alertRecordRepository.save(alert);

            } catch (Exception e) {
                log.error("[AnomalyDetector] metric={} error: {}", rule.getMetric(), e.getMessage(), e);
                alert.setStatus("error");
                alert.setDiagnosis("诊断异常: " + e.getMessage());
                alertRecordRepository.save(alert);
            } finally {
                // 记录诊断完成时间，用于抑制期判断
                lastDiagnosed.put(rule.getMetric(), System.currentTimeMillis());
                // 递减计数，为0时从 Map 中移除
                AtomicInteger cnt = activeAlerts.get(rule.getMetric());
                if (cnt != null && cnt.decrementAndGet() == 0) {
                    activeAlerts.remove(rule.getMetric());
                }
            }
        });
    }

    private String buildMetricContext() {
        StringBuilder sb = new StringBuilder("\n\nJVM metrics snapshot:\n");
        metricsCollector.collectAll().stream()
                .filter(s -> s.getName().contains("jvm.") || s.getName().contains("system.") || s.getName().contains("process."))
                .sorted((a, b) -> a.getName().compareTo(b.getName()))
                .limit(15)
                .forEach(s -> sb.append("  ").append(s.getName()).append(" = ")
                        .append(String.format("%.2f", s.getValue())).append("\n"));
        return sb.toString();
    }

    private String extractRecovery(String diagnosis) {
        if (diagnosis == null) return null;
        StringBuilder sb = new StringBuilder();
        for (String line : diagnosis.split("\n")) {
            if (line.contains("recover") || line.contains("suggest") || line.contains("step")
                    || line.contains("rollback") || line.contains("scale") || line.contains("restart")
                    || line.contains("建议") || line.contains("处理") || line.contains("步骤")) {
                sb.append(line.trim()).append("\n");
            }
        }
        return sb.length() > 0 ? sb.toString().trim() : null;
    }

    /** 从诊断结论中提取根因（跳过 RAG 原文） */
    private String extractRootCause(String diagnosis) {
        if (diagnosis == null || diagnosis.isEmpty()) return "";
        String[] skipMarkers = {"[参考历史案例]", "相似历史案例", "KB-", "alert_features",
                "root_cause", "solution_steps", "案例 ID", "历史案例匹配"};
        List<String> lines = new ArrayList<>();
        for (String line : diagnosis.split("\n")) {
            boolean skip = false;
            for (String m : skipMarkers) {
                if (line.contains(m)) { skip = true; break; }
            }
            if (!skip && line.trim().length() > 5) lines.add(line.trim());
        }
        if (lines.isEmpty()) {
            return diagnosis.length() > 20 ? diagnosis.substring(0, 100) : diagnosis;
        }
        for (String line : lines) {
            String lower = line.toLowerCase();
            if (lower.contains("根因") || lower.contains("最可能") || lower.contains("主要怀疑")
                    || lower.contains("原因") || lower.contains("conclusion")) {
                String s = stripPrefix(line);
                if (s.length() > 5) return s;
            }
        }
        String last = lines.get(lines.size() - 1);
        return stripPrefix(last);
    }

    private String stripPrefix(String s) {
        s = s.trim();
        while (s.length() > 0 && (s.charAt(0) == '*' || s.charAt(0) == '-' || s.charAt(0) == '#')) {
            s = s.substring(1).trim();
        }
        return s;
    }

    public static class DiagnosisResult {
        public final String conclusion;
        public final long durationMs;
        public DiagnosisResult(String c, long d) { this.conclusion = c; this.durationMs = d; }
    }
}
