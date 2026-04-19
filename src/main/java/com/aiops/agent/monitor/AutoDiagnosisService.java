package com.aiops.agent.monitor;

import com.aiops.agent.agent.ReactEngine;
import com.aiops.agent.agent.memory.SessionMemory;
import com.aiops.agent.rag.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 自动诊断服务
 *
 * 当 AnomalyDetector 检测到异常时，调用此服务进行 AI 诊断。
 * 诊断结果存入知识库，供后续 RAG 参考。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoDiagnosisService {

    private final ReactEngine reactEngine;
    private final SessionMemory sessionMemory;
    private final KnowledgeBaseService knowledgeBaseService;

    /**
     * 执行自动诊断
     * @param input 告警描述
     * @param sessionId 会话 ID（可为空自动生成）
     * @return 诊断结论
     */
    public AnomalyDetector.DiagnosisResult diagnose(String input, String sessionId) {
        long start = System.currentTimeMillis();
        try {
            log.info("[AutoDiagnosis] 开始诊断: {}", input);

            // 1. RAG 检索相似案例
            String ragContext = knowledgeBaseService.searchSimilarCases(input, 3).toString();

            // 2. 构造带 RAG 上下文的输入
            String enhancedInput = input + "\n\n[参考历史案例]\n" + ragContext;

            // 3. ReAct 推理（减少迭代次数，加快速度）
            String conclusion = reactEngine.run(enhancedInput, sessionId != null ? sessionId : "auto-" + System.currentTimeMillis());

            long duration = System.currentTimeMillis() - start;
            log.info("[AutoDiagnosis] 诊断完成，耗时 {}ms", duration);

            return new AnomalyDetector.DiagnosisResult(conclusion, duration);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[AutoDiagnosis] 诊断失败: {}", e.getMessage(), e);
            return new AnomalyDetector.DiagnosisResult("诊断异常: " + e.getMessage(), duration);
        }
    }
}
