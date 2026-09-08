package com.we0j.common.exception;

/** 配置校验失败。userFacingReport() 输出多行可操作报告（文件路径+位置+期望+修复建议）。 */
public class ConfigValidationException extends We0jException {
    private final String report;
    public ConfigValidationException(String report) { super(report); this.report = report; }
    public ConfigValidationException(String message, Throwable cause) { super(message, cause); this.report = message; }
    @Override public String userFacingMessage() { return getMessage(); }
    public String userFacingReport() { return report; }
}
