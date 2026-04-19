package com.aiops.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 所有 Tool 的统一接口
 * 每个 Tool 实现 handle(jsonParams) 方法，接收 LLM 传来的参数，返回 JSON 结果
 */
public interface Tool {

    ObjectMapper mapper = new ObjectMapper();

    /** 工具名称 */
    String name();

    /** 工具描述（供 LLM 判断何时调用）*/
    String description();

    /**
     * 执行工具
     * @param params JSON 字符串参数（由 LLM 根据 inputSchema 生成）
     * @return 执行结果字符串
     */
    default String execute(String params) {
        try {
            JsonNode parsed = mapper.readTree(params);
            Object result = handle(parsed);
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage().replace("\"", "\\\"") + "\"}";
        }
    }

    /**
     * 核心执行逻辑，子类实现
     */
    Object handle(JsonNode params) throws Exception;
}
