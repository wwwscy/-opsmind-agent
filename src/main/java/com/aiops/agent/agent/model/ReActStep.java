package com.aiops.agent.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * ReAct 单步推理记录
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReActStep {
    private int stepNum;
    private String thought;      // 分析思路
    private String action;       // 工具名
    private String toolArgs;     // 工具参数 JSON
    private String observation;  // 工具执行结果
    private String decision;     // 继续 / 输出结论
    private Boolean success;     // 工具是否执行成功
}
