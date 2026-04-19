package com.aiops.agent.controller;

import com.aiops.agent.agent.memory.SessionMemory;
import com.aiops.agent.service.DiagnosisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * AI 诊断对话 HTTP 接口
 *
 *  POST /api/diagnosis        发起诊断（body: {input, sessionId?}）
 *  GET  /api/diagnosis/:id    查询诊断记录
 *  POST /api/diagnosis/:id/feedback  提交反馈（body: {feedback: "good"|"bad"}）
 *  DELETE /api/session/:id    清除会话记忆
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AiDiagnosisController {

    private final DiagnosisService diagnosisService;
    private final SessionMemory sessionMemory;

    /**
     * 发起诊断
     */
    @PostMapping("/diagnosis")
    public Map<String, Object> diagnose(@RequestBody Map<String, String> body) {
        String input = body.get("input");
        String sessionId = body.get("sessionId");

        if (input == null || input.isBlank()) {
            return Map.of("error", "input 不能为空");
        }

        DiagnosisService.DiagnosisResult result = diagnosisService.diagnose(input, sessionId);
        return Map.of(
                "sessionId", result.getSessionId(),
                "conclusion", result.getConclusion(),
                "durationMs", result.getDurationMs(),
                "status", result.getStatus()
        );
    }

    /**
     * 提交反馈
     */
    @PostMapping("/diagnosis/{sessionId}/feedback")
    public Map<String, Object> feedback(
            @PathVariable String sessionId,
            @RequestBody Map<String, String> body
    ) {
        String feedback = body.get("feedback"); // "good" or "bad"
        diagnosisService.feedback(sessionId, feedback);
        return Map.of("ok", true, "sessionId", sessionId, "feedback", feedback);
    }

    /**
     * 清除会话记忆
     */
    @DeleteMapping("/session/{sessionId}")
    public Map<String, Object> clearSession(@PathVariable String sessionId) {
        sessionMemory.clear(sessionId);
        return Map.of("ok", true, "sessionId", sessionId);
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "service", "OpsMind Agent");
    }
}
