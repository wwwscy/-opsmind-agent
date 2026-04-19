package com.aiops.agent.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MCP 工具规范
 * 每个 Tool 都有 name（工具名）、description（描述）、inputSchema（输入参数 schema）
 * Agent 看到 description 知道什么场景该调用，inputSchema 指导如何构造参数
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolSpec {

    /** 工具唯一标识，如 "query_metrics"、"search_knowledge_base" */
    private String name;

    /** 工具描述，LLM 根据这个判断何时调用 */
    private String description;

    /**
     * 输入参数 JSON Schema
     * 例如：{"type":"object","properties":{"pod":{"type":"string","description":"Pod名称"}},"required":["pod"]}
     */
    private Object inputSchema;

    /** 工具的 HTTP URL（MCP 协议下工具不一定在本地） */
    private String endpoint;

    /** 工具描述的中文版本（方便调试） */
    private String descriptionZh;
}
