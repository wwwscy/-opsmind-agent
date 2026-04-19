package com.aiops.agent.monitor;

import com.aiops.agent.monitor.entity.AlertRecord;
import com.aiops.agent.monitor.entity.AlertRecordRepository;
import com.aiops.agent.monitor.model.AlertRule;
import com.aiops.agent.rag.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

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

    private final Set<String> firingMetrics = ConcurrentHashMap.newKeySet();

    @jakarta.annotation.PostConstruct
    public void init() {
        log.info("[AnomalyDetector] init, rules={}",
                monitoringProperties.getAlertRules() != null ? monitoringProperties.getAlertRules().size() : 0);
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${monitoring.collect-interval-seconds:30}000")
    @Transactional
    public void detect() {
        if (monitoringProperties.getAlertRules() == null || monitoringProperties.getAlertRules().isEmpty()) {
            return;
        }
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
                if (firingMetrics.contains(rule.getMetric())) {
                    return;
                }
                firingMetrics.add(rule.getMetric());
                log.warn("[AnomalyDetector] FIRE alert={} metric={} val={}", rule.getName(), rule.getMetric(), val);
                metricsCollector.recordAlertDuration(rule.getMetric(), false);
                AlertRecord alert = createAlert(rule, val);
                if (monitoringProperties.isAutoDiagnosisEnabled() || monitoringProperties.isFeishuEnabled()) {
                    triggerAsync(alert, rule, val);
                }
            } else {
                metricsCollector.recordAlertDuration(rule.getMetric(), true);
            }
        } else {
            metricsCollector.recordAlertDuration(rule.getMetric(), false);
        }
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
        CompletableFuture.runAsync(() -> {
            String diagnosis = null;
            String recovery = null;
            long start = System.currentTimeMillis();
            try {
                if (monitoringProperties.isAutoDiagnosisEnabled()) {
                    alert.setStatus("diagnosing");
                    alertRecordRepository.save(alert);

                    String input = rule.getName() + " alert, metric=" + rule.getMetric()
                            + ", current=" + val + ", threshold=" + rule.getCondition() + rule.getThreshold()
                            + ", target=" + rule.getTarget() + ". Analyze root cause and suggest recovery.";

                    input += buildMetricContext();

                    AnomalyDetector.DiagnosisResult r = autoDiagnosisService.diagnose(input, null);
                    diagnosis = r.conclusion;
                    recovery = extractRecovery(diagnosis);
                    alert.setDiagnosis(diagnosis);
                    alert.setRecoverySuggestion(recovery);
                    alert.setDiagnosisDurationMs((int) r.durationMs);
                    alert.setStatus("resolved");
                    log.info("[AnomalyDetector] AI done in {}ms", r.durationMs);

                    if (diagnosis != null && !diagnosis.isEmpty()) {
                        String cause = extractRootCause(diagnosis);
                        if (!cause.isEmpty()) {
                            List<String> steps = recovery != null && !recovery.isEmpty()
                                    ? Arrays.asList(recovery.split("\\n")) : Collections.emptyList();
                            knowledgeBaseService.addCase(
                                    rule.getName() + " on " + rule.getTarget(), cause, steps, "AI-auto");
                            log.info("[AnomalyDetector] KB case added: {}", cause);
                        }
                    }
                }

                if (monitoringProperties.isFeishuEnabled()) {
                    boolean ok = feishuNotificationService.send(alert, diagnosis, recovery);
                    alert.setNotified(ok);
                    if (ok) log.info("[AnomalyDetector] Feishu sent");
                }

                alertRecordRepository.save(alert);

            } catch (Exception e) {
                log.error("[AnomalyDetector] error: {}", e.getMessage(), e);
                alert.setStatus("error");
                alert.setDiagnosis("Error: " + e.getMessage());
                alertRecordRepository.save(alert);
            } finally {
                firingMetrics.remove(rule.getMetric());
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
        for (String line : diagnosis.split("\\n")) {
            if (line.contains("recover") || line.contains("suggest") || line.contains("step")
                    || line.contains("rollback") || line.contains("scale") || line.contains("restart")) {
                sb.append(line.trim()).append("\n");
            }
        }
        return sb.length() > 0 ? sb.toString().trim() : null;
    }

    private String extractRootCause(String diagnosis) {
        if (diagnosis == null || diagnosis.isEmpty()) return "";
        for (String line : diagnosis.split("\\n")) {
            if (line.contains("root cause") || line.contains("cause") || line.contains("reason")) {
                String s = line.trim();
                while (s.length() > 0 && (s.charAt(0) == '*' || s.charAt(0) == '-' || s.charAt(0) == '#' || Character.isWhitespace(s.charAt(0)))) {
                    s = s.substring(1);
                }
                s = s.trim();
                if (s.length() > 5) return s;
            }
        }
        return diagnosis.length() > 20 ? diagnosis.substring(0, 80) : diagnosis;
    }

    private String buildFeishuMsg(AlertRecord alert, AlertRule rule, double val, String diagnosis, String recovery) {
        StringBuilder sb = new StringBuilder();
        sb.append(severityEmoji(alert.getSeverity())).append(" [").append(alert.getAlertName()).append("]\n");
        sb.append("metric: ").append(alert.getMetricName()).append("\n");
        sb.append("value: ").append(String.format("%.2f", val));
        sb.append(" | threshold: ").append(rule.getCondition()).append(String.format("%.2f\n", rule.getThreshold()));
        sb.append("time: ").append(alert.getTriggeredAt().toString()).append("\n");
        sb.append("target: ").append(alert.getTarget()).append("\n");
        if (diagnosis != null) {
            sb.append("\nAI diagnosis:\n").append(diagnosis);
        }
        if (recovery != null) {
            sb.append("\n\nRecovery:\n").append(recovery);
        }
        sb.append("\n\nhttp://localhost:8080/api/alerts/").append(alert.getId());
        return sb.toString();
    }

    private String severityEmoji(String sev) {
        return switch (sev) {
            case "critical" -> "CRITICAL";
            case "high" -> "HIGH";
            case "medium" -> "MEDIUM";
            default -> "LOW";
        };
    }

    public static class DiagnosisResult {
        public String conclusion;
        public long durationMs;
        public DiagnosisResult(String c, long d) { this.conclusion = c; this.durationMs = d; }
    }
}
