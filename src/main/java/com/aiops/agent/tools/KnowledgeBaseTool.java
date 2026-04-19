package com.aiops.agent.tools;

import com.aiops.agent.rag.KnowledgeBaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 知识库检索工具（RAG）
 * 将告警/故障描述向量化，在 Milvus 中检索相似历史案例
 * 返回相似案例的告警特征 + 根因 + 处理步骤
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeBaseTool implements Tool {

    private final KnowledgeBaseService knowledgeBaseService;
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String name() {
        return "search_knowledge_base";
    }

    @Override
    public String description() {
        return "在故障知识库中检索相似的历史案例，返回告警特征、根因和处理步骤。适用于已有经验场景的快速诊断参考。";
    }

    @Override
    public Object handle(com.fasterxml.jackson.databind.JsonNode params) throws Exception {
        String query = params.path("query").asText();
        int topK = params.path("top_k").asInt(5);

        // 调用 RAG 服务检索相似案例
        return knowledgeBaseService.searchSimilarCases(query, topK);
    }
}
