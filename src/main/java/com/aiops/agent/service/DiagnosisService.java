package com.aiops.agent.service;

import com.aiops.agent.agent.ReactEngine;
import com.aiops.agent.agent.memory.SessionMemory;
import com.aiops.agent.rag.KnowledgeBaseService;
import com.aiops.agent.service.entity.DiagnosisRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 诊断服务
 * 对话入口：接收用户输入 → ReAct 推理 → 返回诊断结论
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiagnosisService {

    private final ReactEngine reactEngine;
    private final SessionMemory sessionMemory;
    private final DiagnosisRecordRepository recordRepository;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 执行一次诊断对话
     * @param userInput 用户输入（告警描述）
     * @param sessionId 会话 ID（为空则自动生成）
     * @return 诊断结果
     */
    public DiagnosisResult diagnose(String userInput, String sessionId) {
        long start = System.currentTimeMillis();

        // 自动生成 sessionId
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        log.info("[Diagnosis] sessionId={}, input={}", sessionId, userInput);

        try {
            // ── 1. RAG 检索相似案例（注入上下文）────────────────────
            String ragContext = knowledgeBaseService.searchSimilarCases(userInput, 3).toString();
            String enhancedInput = userInput + "\n\n[参考历史案例]\n" + ragContext;

            // ── 2. ReAct 推理 ────────────────────────────────────
            String conclusion = reactEngine.run(enhancedInput, sessionId);

            long duration = System.currentTimeMillis() - start;
            log.info("[Diagnosis] 推理完成，耗时 {}ms，结论={}", duration, conclusion);

            // ── 3. 持久化诊断记录 ─────────────────────────────────
            DiagnosisRecord record = DiagnosisRecord.builder()
                    .sessionId(sessionId)
                    .userInput(userInput)
                    .finalDiagnosis(conclusion)
                    .durationMs((int) duration)
                    .createdAt(LocalDateTime.now())
                    .build();
            recordRepository.save(record);

            // ── 4. 存入长期记忆（用于下次 RAG）──────────────────
            sessionMemory.saveDiagnosis(sessionId, conclusion, null);

            return new DiagnosisResult(sessionId, conclusion, duration, "success");
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[Diagnosis] 诊断异常: {}", e.getMessage(), e);
            return new DiagnosisResult(sessionId, "诊断异常: " + e.getMessage(), duration, "error");
        }
    }

    /**
     * 用户反馈（采纳/不采纳）
     */
    public void feedback(String sessionId, String feedback) {
        recordRepository.findBySessionId(sessionId)
                .ifPresent(record -> {
                    record.setUserFeedback(feedback);
                    recordRepository.save(record);
                    log.info("[Feedback] sessionId={}, feedback={}", sessionId, feedback);
                });
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class DiagnosisResult {
        private String sessionId;
        private String conclusion;
        private long durationMs;
        private String status;
    }
}
