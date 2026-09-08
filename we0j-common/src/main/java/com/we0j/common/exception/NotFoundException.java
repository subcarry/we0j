package com.we0j.common.exception;

public class NotFoundException extends We0jException {
    public NotFoundException(String message) { super(message); }
    @Override public String userFacingMessage() { return getMessage(); }
}
