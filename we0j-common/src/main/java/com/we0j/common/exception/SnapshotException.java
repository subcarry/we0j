package com.we0j.common.exception;

public class SnapshotException extends We0jException {
    public SnapshotException(String message) { super(message); }
    public SnapshotException(String message, Throwable cause) { super(message, cause); }
    @Override public String userFacingMessage() { return getMessage(); }
}
