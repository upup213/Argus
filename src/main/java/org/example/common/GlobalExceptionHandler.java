package org.example.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器：业务异常按 ErrorCode 映射 HTTP 码，兜底异常脱敏返回 500
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBizException(BizException e) {
        int code = e.getErrorCode().getCode();
        String message = e.getMessage();
        logger.warn("业务异常: code={}, message={}", code, message);
        return ResponseEntity.status(code).body(ApiResponse.error(code, message));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String message = "bad request";
        if (e.getBindingResult().getFieldError() != null) {
            String defaultMessage = e.getBindingResult().getFieldError().getDefaultMessage();
            if (defaultMessage != null && !defaultMessage.isEmpty()) {
                message = defaultMessage;
            }
        }
        return ResponseEntity.status(400).body(ApiResponse.error(400, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        logger.error("未处理异常", e);
        return ResponseEntity.status(500).body(ApiResponse.error(500, "internal error"));
    }
}
