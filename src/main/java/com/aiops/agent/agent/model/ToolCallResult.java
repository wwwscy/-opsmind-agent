package com.aiops.agent.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallResult {
    private boolean success;
    private String result;
    private String error;

    public static ToolCallResult success(String result) {
        return new ToolCallResult(true, result, null);
    }

    public static ToolCallResult fail(String error) {
        return new ToolCallResult(false, null, error);
    }
}
