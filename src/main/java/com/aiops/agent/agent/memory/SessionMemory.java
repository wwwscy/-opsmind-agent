package com.aiops.agent.agent.memory;

import com.aiops.agent.agent.model.ReActStep;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 短期对话记忆（开发阶段用 ConcurrentHashMap，生产换 Redis）
 *
 * 同一 Session 内的对话上下文、Tool 调用历史全部存入内存 Map
 * 支持追问："刚才那个 Pod 现在怎么样了？"
 * Session 默认 30 分钟过期
 */
@Slf4j
@Service
public class SessionMemory {

    private static final String KEY_PREFIX = "opsmind:session:";
    private static final long SESSION_TTL_MS = 30 * 60 * 1000L;

    private final ObjectMapper mapper = new ObjectMapper();

    // ConcurrentHashMap 替代 Redis（开发/测试用，生产换 Redis）
    private final Map<String, SessionData> sessions = new ConcurrentHashMap<>();

    /**
     * 追加 Tool 调用结果到当前 Session
     */
    public void append(String sessionId, String toolResult) {
        SessionData data = sessions.computeIfAbsent(sessionId, k -> new SessionData());
        data.toolHistory.add(toolResult);
        data.lastAccess = System.currentTimeMillis();
    }

    /**
     * 获取当前 Session 的完整上下文（供 ReAct Prompt 使用）
     */
    public String getContext(String sessionId) {
        SessionData data = sessions.get(sessionId);
        if (data == null || data.toolHistory.isEmpty()) {
            return "";
        }
        cleanExpired();
        return String.join("\n", data.toolHistory);
    }

    /**
     * 保存诊断结论（用于反馈闭环）
     */
    public void saveDiagnosis(String sessionId, String conclusion, List<ReActStep> steps) {
        SessionData data = sessions.computeIfAbsent(sessionId, k -> new SessionData());
        data.diagnosis = conclusion;
        data.lastAccess = System.currentTimeMillis();
    }

    /**
     * 清除 Session（对话结束时调用）
     */
    public void clear(String sessionId) {
        sessions.remove(sessionId);
        log.info("[SessionMemory] Session 已清除: {}", sessionId);
    }

    /**
     * 清理过期 Session（超过 30 分钟未访问）
     */
    private void cleanExpired() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry ->
                now - entry.getValue().lastAccess > SESSION_TTL_MS
        );
    }

    /**
     * 检查并刷新 Session
     */
    public void touch(String sessionId) {
        SessionData data = sessions.get(sessionId);
        if (data != null) {
            data.lastAccess = System.currentTimeMillis();
        }
    }

    private static class SessionData {
        List<String> toolHistory = new java.util.concurrent.CopyOnWriteArrayList<>();
        String diagnosis;
        long lastAccess = System.currentTimeMillis();
    }
}
