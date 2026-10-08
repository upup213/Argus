package org.example.common;

/**
 * 业务错误码，与 HTTP 状态码语义一一对应
 */
public enum ErrorCode {

    BAD_REQUEST(400, "bad request"),
    UNAUTHORIZED(401, "unauthorized"),
    NOT_FOUND(404, "not found"),
    INTERNAL_ERROR(500, "internal error"),
    SERVICE_UNAVAILABLE(503, "service unavailable");

    private final int code;
    private final String defaultMessage;

    ErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public int getCode() {
        return code;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
