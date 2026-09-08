package com.we0j.common.exception;

/** 模型产出的工具参数 JSON 无法解析（FR-032 / OpenAI 分片重组失败）。 */
public class MalformedToolArgumentsException extends ToolException {
    private final String toolName;
    private final String rawArguments;

    public MalformedToolArgumentsException(String toolName, String rawArguments, String message) {
        super(message);
        this.toolName = toolName;
        this.rawArguments = rawArguments;
    }
    public String toolName() { return toolName; }
    public String rawArguments() { return rawArguments; }
    @Override public String userFacingMessage() {
        return "Model produced malformed JSON arguments for tool '" + toolName + "': " + getMessage();
    }
}
