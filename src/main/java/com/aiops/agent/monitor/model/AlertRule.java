package com.aiops.agent.monitor.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 告警规则配置
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlertRule {

    /** 规则名称 */
    private String name;

    /** 指标名（Micrometer 指标 ID） */
    private String metric;

    /** 目标值（Pod名/服务名，用于构造告警描述） */
    private String target;

    /** 条件：> < >= <= */
    private String condition;

    /** 阈值 */
    private Double threshold;

    /** 持续多少秒才触发（防抖动） */
    private int durationSeconds;

    /** 告警等级 */
    private String severity;

    /**
     * 判断当前值是否触发告警
     */
    public boolean isTriggered(double currentValue) {
        return switch (condition) {
            case ">"  -> currentValue > threshold;
            case "<"  -> currentValue < threshold;
            case ">=" -> currentValue >= threshold;
            case "<=" -> currentValue <= threshold;
            default   -> false;
        };
    }

    public String getSeverityEmoji() {
        return switch (severity) {
            case "critical" -> "🔴";
            case "high"     -> "🟠";
            case "medium"   -> "🟡";
            default         -> "🔵";
        };
    }
}
