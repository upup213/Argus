package org.example.common;

/**
 * 业务异常，携带错误码与可选自定义消息（必须脱敏）
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String message;

    public BizException(ErrorCode errorCode) {
        this(errorCode, null);
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.message = message;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    @Override
    public String getMessage() {
        if (message != null && !message.isEmpty()) {
            return message;
        }
        return errorCode.getDefaultMessage();
    }
}
