package com.aiops.agent.monitor;

import com.aiops.agent.monitor.entity.AlertRecord;
import com.aiops.agent.monitor.entity.AlertRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 告警管理接口
 */
@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AlertController {

    private final AlertRecordRepository alertRecordRepository;

    /** 查询最近告警 */
    @GetMapping
    public Map<String, Object> list() {
        List<AlertRecord> alerts = alertRecordRepository.findTop10ByOrderByTriggeredAtDesc();
        return Map.of(
                "total", alerts.size(),
                "alerts", alerts
        );
    }

    /** 查询单个告警 */
    @GetMapping("/{id}")
    public AlertRecord get(@PathVariable Long id) {
        return alertRecordRepository.findById(id).orElse(null);
    }

    /** 手动触发诊断 */
    @PostMapping("/{id}/diagnose")
    public Map<String, Object> diagnose(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        AlertRecord alert = alertRecordRepository.findById(id).orElse(null);
        if (alert == null) {
            return Map.of("error", "告警不存在");
        }

        String input = body != null && body.containsKey("input")
                ? body.get("input")
                : String.format("%s 告警，指标=%s，请分析根因并给出恢复建议",
                        alert.getAlertName(), alert.getMetricName());

        // 这里可以调用 AutoDiagnosisService，但为简单起见返回告警信息
        return Map.of(
                "alertId", alert.getId(),
                "alertName", alert.getAlertName(),
                "status", alert.getStatus(),
                "diagnosis", alert.getDiagnosis() != null ? alert.getDiagnosis() : "尚未诊断",
                "message", "请访问 http://localhost:8080/ 进行 AI 诊断"
        );
    }
}
