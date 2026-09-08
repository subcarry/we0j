package com.we0j.common.exception;

import java.util.Map;

public class ProviderAuthException extends ModelException {
    public ProviderAuthException(String message, Integer statusCode) {
        super(message, statusCode, false, Map.of(), null, "auth error");
    }
}
