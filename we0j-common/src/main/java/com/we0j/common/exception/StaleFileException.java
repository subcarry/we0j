package com.we0j.common.exception;

/** 编辑安全链路：文件未读过或被外部修改（FR-073 步骤 2）。 */
public class StaleFileException extends ToolException {
    public StaleFileException(String message) { super(message); }
}
