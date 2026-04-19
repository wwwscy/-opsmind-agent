package com.aiops.agent.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LangChain4j 配置
 * 对接 MiniMax（OpenAI 兼容接口）
 *
 * 正确包名：dev.langchain4j.model.openai（不是 dev.langchain4j.model.chat.oai）
 */
@Configuration
public class LangChain4jConfig {

    @Value("${minimax.api-key:sk-xxx}")
    private String apiKey;

    @Value("${minimax.base-url:https://api.minimax.chat/v1}")
    private String baseUrl;

    /**
     * Chat Model（用于 ReAct 推理）
     * MiniMax-M2.7 效果更好，MiniMax-M2.1 更快更便宜
     */
    @Bean
    public ChatLanguageModel chatModel(
            @Value("${minimax.chat-model:MiniMax-M2.1}") String modelName
    ) {
        System.out.println("[DEBUG] LangChain4jConfig init: apiKey=" + (apiKey.length() > 10 ? apiKey.substring(0,10)+"..." : apiKey));
        System.out.println("[DEBUG] LangChain4jConfig init: baseUrl=" + baseUrl);
        System.out.println("[DEBUG] LangChain4jConfig init: modelName=" + modelName);
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(0.3)
                .maxTokens(2048)
                .timeout(java.time.Duration.ofSeconds(120))
                .build();
    }

    /**
     * Embedding Model（用于 RAG 向量检索）
     */
    @Bean
    public EmbeddingModel embeddingModel(
            @Value("${minimax.embedding-model:embo01}") String embeddingModelName
    ) {
        return OpenAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(embeddingModelName)
                .build();
    }
}
