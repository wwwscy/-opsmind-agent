package com.aiops.agent.monitor;

import com.aiops.agent.monitor.entity.AlertRecord;
import com.aiops.agent.monitor.entity.AlertRecordRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 告警管理接口
 */
@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AlertController {

    private final AlertRecordRepository alertRecordRepository;
    private static final DateTimeFormatter DFT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 查询告警列表（支持分页+过滤） */
    @GetMapping
    public Map<String, Object> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String metricName,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime
    ) {
        // 限制 pageSize 上限
        pageSize = Math.min(pageSize, 100);
        if (page < 1) page = 1;

        Specification<AlertRecord> spec = buildSpec(severity, status, metricName, startTime, endTime);

        Page<AlertRecord> pageResult = alertRecordRepository.findAll(
                spec,
                PageRequest.of(page - 1, pageSize, Sort.by(Sort.Direction.DESC, "triggeredAt"))
        );

        List<AlertRecord> alerts = pageResult.getContent();

        // 额外统计
        Map<String, Long> severityCounts = Map.of(
                "critical", alertRecordRepository.countBySeverity("critical"),
                "high", alertRecordRepository.countBySeverity("high"),
                "medium", alertRecordRepository.countBySeverity("medium"),
                "low", alertRecordRepository.countBySeverity("low")
        );

        Map<String, Long> statusCounts = Map.of(
                "pending", alertRecordRepository.countByStatus("pending"),
                "diagnosing", alertRecordRepository.countByStatus("diagnosing"),
                "resolved", alertRecordRepository.countByStatus("resolved"),
                "error", alertRecordRepository.countByStatus("error")
        );

        return Map.of(
                "total", pageResult.getTotalElements(),
                "page", page,
                "pageSize", pageSize,
                "totalPages", pageResult.getTotalPages(),
                "alerts", alerts.stream().map(this::toSummary).collect(Collectors.toList()),
                "severityCounts", severityCounts,
                "statusCounts", statusCounts
        );
    }

    /** 查询单个告警详情 */
    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        AlertRecord a = alertRecordRepository.findById(id).orElse(null);
        if (a == null) return Map.of("error", "告警不存在");
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("alertName", a.getAlertName() != null ? a.getAlertName() : "");
        m.put("metricName", a.getMetricName() != null ? a.getMetricName() : "");
        m.put("metricValue", a.getMetricValue() != null ? a.getMetricValue() : 0);
        m.put("thresholdValue", a.getThresholdValue() != null ? a.getThresholdValue() : 0);
        m.put("condition", a.getCondition() != null ? a.getCondition() : "");
        m.put("severity", a.getSeverity() != null ? a.getSeverity() : "low");
        m.put("status", a.getStatus() != null ? a.getStatus() : "pending");
        m.put("target", a.getTarget() != null ? a.getTarget() : "");
        m.put("triggeredAt", a.getTriggeredAt() != null ? a.getTriggeredAt().toString() : "");
        m.put("diagnosis", a.getDiagnosis() != null ? a.getDiagnosis() : "");
        m.put("recoverySuggestion", a.getRecoverySuggestion() != null ? a.getRecoverySuggestion() : "");
        m.put("diagnosisDurationMs", a.getDiagnosisDurationMs() != null ? a.getDiagnosisDurationMs() : 0);
        m.put("notified", a.getNotified() != null ? a.getNotified() : false);
        return m;
    }

    /** 手动触发诊断 */
    @PostMapping("/{id}/diagnose")
    public Map<String, Object> diagnose(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        AlertRecord alert = alertRecordRepository.findById(id).orElse(null);
        if (alert == null) return Map.of("error", "告警不存在");
        return Map.of(
                "alertId", alert.getId(),
                "alertName", alert.getAlertName(),
                "status", alert.getStatus(),
                "diagnosis", alert.getDiagnosis() != null ? alert.getDiagnosis() : "尚未诊断"
        );
    }

    /** 统计摘要 */
    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return Map.of(
                "critical", alertRecordRepository.countBySeverity("critical"),
                "high", alertRecordRepository.countBySeverity("high"),
                "medium", alertRecordRepository.countBySeverity("medium"),
                "low", alertRecordRepository.countBySeverity("low"),
                "pending", alertRecordRepository.countByStatus("pending"),
                "diagnosing", alertRecordRepository.countByStatus("diagnosing"),
                "resolved", alertRecordRepository.countByStatus("resolved"),
                "error", alertRecordRepository.countByStatus("error"),
                "total", alertRecordRepository.count()
        );
    }

    /** 导出 CSV */
    @GetMapping("/export")
    public ResponseEntity<String> export(
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime
    ) {
        Specification<AlertRecord> spec = buildSpec(severity, status, null, startTime, endTime);
        List<AlertRecord> alerts = alertRecordRepository.findAll(
                spec, Sort.by(Sort.Direction.DESC, "triggeredAt")
        );

        StringBuilder csv = new StringBuilder();
        csv.append("ID,告警名称,指标,当前值,阈值,条件,级别,状态,目标,触发时间,诊断耗时,飞书推送,诊断结论\n");
        for (AlertRecord a : alerts) {
            csv.append(esc(String.valueOf(a.getId()))).append(",");
            csv.append(esc(nullSafe(a.getAlertName()))).append(",");
            csv.append(esc(nullSafe(a.getMetricName()))).append(",");
            csv.append(a.getMetricValue() != null ? a.getMetricValue() : "").append(",");
            csv.append(a.getThresholdValue() != null ? a.getThresholdValue() : "").append(",");
            csv.append(esc(nullSafe(a.getCondition()))).append(",");
            csv.append(esc(nullSafe(a.getSeverity()))).append(",");
            csv.append(esc(nullSafe(a.getStatus()))).append(",");
            csv.append(esc(nullSafe(a.getTarget()))).append(",");
            csv.append(a.getTriggeredAt() != null ? a.getTriggeredAt().format(DFT) : "").append(",");
            csv.append(a.getDiagnosisDurationMs() != null ? a.getDiagnosisDurationMs() : "").append(",");
            csv.append(a.getNotified() != null && a.getNotified() ? "是" : "否").append(",");
            csv.append(esc(nullSafe(a.getDiagnosis()))).append("\n");
        }

        String filename = "alerts_" + System.currentTimeMillis() + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv.toString());
    }

    /** 清空所有告警记录（测试用） */
    @DeleteMapping
    public ResponseEntity<?> clearAll() {
        alertRecordRepository.deleteAll();
        return ResponseEntity.ok(Map.of("cleared", true, "total", 0));
    }

    // ── Internal ──────────────────────────────────────────

    private Specification<AlertRecord> buildSpec(String severity, String status, String metricName,
                                                 String startTime, String endTime) {
        return (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            if (severity != null && !severity.isEmpty() && !"all".equalsIgnoreCase(severity)) {
                preds.add(cb.equal(root.get("severity"), severity));
            }
            if (status != null && !status.isEmpty() && !"all".equalsIgnoreCase(status)) {
                preds.add(cb.equal(root.get("status"), status));
            }
            if (metricName != null && !metricName.isEmpty()) {
                preds.add(cb.like(root.get("metricName"), "%" + metricName + "%"));
            }
            if (startTime != null && !startTime.isEmpty()) {
                LocalDateTime start = LocalDateTime.parse(startTime, DFT);
                preds.add(cb.greaterThanOrEqualTo(root.get("triggeredAt"), start));
            }
            if (endTime != null && !endTime.isEmpty()) {
                LocalDateTime end = LocalDateTime.parse(endTime, DFT);
                preds.add(cb.lessThanOrEqualTo(root.get("triggeredAt"), end));
            }
            return cb.and(preds.toArray(new Predicate[0]));
        };
    }

    private Map<String, Object> toSummary(AlertRecord a) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("alertName", a.getAlertName() != null ? a.getAlertName() : "");
        m.put("metricName", a.getMetricName() != null ? a.getMetricName() : "");
        m.put("metricValue", a.getMetricValue() != null ? a.getMetricValue() : 0);
        m.put("thresholdValue", a.getThresholdValue() != null ? a.getThresholdValue() : 0);
        m.put("condition", a.getCondition() != null ? a.getCondition() : "");
        m.put("severity", a.getSeverity() != null ? a.getSeverity() : "low");
        m.put("status", a.getStatus() != null ? a.getStatus() : "pending");
        m.put("target", a.getTarget() != null ? a.getTarget() : "");
        m.put("triggeredAt", a.getTriggeredAt() != null ? a.getTriggeredAt().toString() : "");
        m.put("diagnosis", a.getDiagnosis() != null ? a.getDiagnosis() : "");
        m.put("recoverySuggestion", a.getRecoverySuggestion() != null ? a.getRecoverySuggestion() : "");
        m.put("diagnosisDurationMs", a.getDiagnosisDurationMs() != null ? a.getDiagnosisDurationMs() : 0);
        m.put("notified", a.getNotified() != null ? a.getNotified() : false);
        return m;
    }

    private String nullSafe(String s) { return s != null ? s : ""; }

    private String esc(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
