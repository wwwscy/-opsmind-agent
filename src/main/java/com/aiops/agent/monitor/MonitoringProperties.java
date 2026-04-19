package com.aiops.agent.monitor;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 监控配置属性（绑定 application.yml 中的 monitoring.*）
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "monitoring")
public class MonitoringProperties {

    private int collectIntervalSeconds = 30;
    private String feishuWebhook = "";
    private boolean feishuEnabled = true;
    private boolean autoDiagnosisEnabled = true;
    /** 同一指标诊断抑制时间（分钟），避免重复调用 AI */
    private int diagnosisSuppressMinutes = 10;
    private List<AlertRuleConfig> alertRules = new ArrayList<>();

    @Data
    public static class AlertRuleConfig {
        private String name;
        private String metric;
        private String target;
        private String condition = ">";
        private Double threshold;
        private int durationSeconds = 60;
        private String severity = "medium";
    }
}
