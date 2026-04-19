package com.aiops.agent.service.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 诊断记录实体
 * 每次完整的诊断对话结束后写入 MySQL
 * 用于：1) 历史追溯 2) RAG 知识库素材
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "diagnosis_records")
public class DiagnosisRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 会话 ID */
    @Column(name = "session_id", length = 64)
    private String sessionId;

    /** 用户原始输入 */
    @Column(columnDefinition = "TEXT")
    private String userInput;

    /** ReAct 推理过程（JSON 格式存储） */
    @Column(name = "agent_thought", columnDefinition = "TEXT")
    private String agentThought;

    /** 调用的工具及参数（JSON） */
    @Column(name = "tool_calls", columnDefinition = "TEXT")
    private String toolCalls;

    /** 最终诊断结论 */
    @Column(name = "final_diagnosis", columnDefinition = "TEXT")
    private String finalDiagnosis;

    /** 诊断耗时（毫秒） */
    @Column(name = "duration_ms")
    private Integer durationMs;

    /** 用户反馈：good / bad / null */
    @Column(name = "user_feedback", length = 20)
    private String userFeedback;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
