package com.aiops.agent.rag;

import java.util.*;

/**
 * 知识库服务接口（Strategy 模式）
 *
 * 开发阶段用 InMemoryKnowledgeBaseService（内存），激活 milvus profile 切换到 MilvusKnowledgeBaseService
 */
public interface KnowledgeBaseService {

    /** 检索相似案例，返回 JSON 字符串 */
    String searchSimilarCases(String query, int topK);

    /** 新增案例入库 */
    void addCase(String alertFeatures, String rootCause, List<String> solutionSteps, String result);

    /** 列出所有案例（前端展示用） */
    List<Map<String, Object>> listCases();
}
