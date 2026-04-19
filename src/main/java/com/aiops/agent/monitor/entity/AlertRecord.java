package com.aiops.agent.monitor.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 告警记录
 * 每次指标异常触发时创建，诊断完成后更新结论
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "alert_records")
public class AlertRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 告警名称 */
    @Column(name = "alert_name", length = 100)
    private String alertName;

    /** 指标名 */
    @Column(name = "metric_name", length = 200)
    private String metricName;

    /** 指标当前值 */
    @Column(name = "metric_value")
    private Double metricValue;

    /** 告警阈值 */
    @Column(name = "threshold_value")
    private Double thresholdValue;

    /** 告警条件（> < >= <=） */
    @Column(name = "condition_type", length = 10)
    private String condition;

    /** 告警等级：low / medium / high / critical */
    @Column(name = "severity", length = 20)
    private String severity;

    /** 触发时间 */
    @Column(name = "triggered_at")
    private LocalDateTime triggeredAt;

    /** 诊断状态：pending / diagnosing / resolved / error */
    @Column(name = "status", length = 20)
    private String status;

    /** AI 诊断结论 */
    @Column(name = "diagnosis", columnDefinition = "TEXT")
    private String diagnosis;

    /** 恢复建议 */
    @Column(name = "recovery_suggestion", columnDefinition = "TEXT")
    private String recoverySuggestion;

    /** 诊断耗时（毫秒） */
    @Column(name = "diagnosis_duration_ms")
    private Integer diagnosisDurationMs;

    /** 是否已推送 */
    @Column(name = "notified")
    private Boolean notified;

    /** 告警来源标签（Pod名/服务名） */
    @Column(name = "target", length = 100)
    private String target;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (triggeredAt == null) triggeredAt = LocalDateTime.now();
        if (status == null) status = "pending";
        if (notified == null) notified = false;
    }
}
