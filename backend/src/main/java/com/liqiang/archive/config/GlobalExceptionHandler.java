package com.liqiang.archive.config;

import com.liqiang.archive.dto.ApiResponse;
import com.liqiang.archive.erp.ErpBusinessException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.NestedServletException;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 全局异常处理器（统一使用 {@link ApiResponse} 包装响应）。
 *
 * <p>所有异常统一通过 {@code dto.ApiResponse} 包装后返回，前端可基于 {@code code} 字段做差异化处理。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 资源不存在 */
    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleEntityNotFound(EntityNotFoundException ex) {
        log.warn("Entity not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.notFound(ex.getMessage()));
    }

    /** Bean Validation（@Valid 校验失败）：返回字段级错误明细 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationException(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((org.springframework.validation.FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        log.warn("Validation failed: {}", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiResponse<>(400, "参数校验失败", errors, null,
                        java.time.LocalDateTime.now()));
    }

    /** Query/Path 参数校验失败（@RequestParam @PathVariable @Validated） */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));
        if (message.isBlank()) {
            message = "请求参数不合法";
        }
        log.warn("Constraint violation: {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.badRequest(message));
    }

    /** 请求体无法解析（JSON 格式错误） */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException ex) {
        Throwable cause = ex.getMostSpecificCause() != null ? ex.getMostSpecificCause() : ex;
        log.warn("请求体解析失败: {}", cause.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.badRequest("请求体格式错误或字段类型不匹配"));
    }

    /** 请求方法不支持 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error(HttpStatus.METHOD_NOT_ALLOWED.value(), ex.getMessage()));
    }

    /** 路由不存在（404） */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoHandler(NoHandlerFoundException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.notFound("接口不存在: " + req.getMethod() + " " + req.getRequestURI()));
    }

    /** 上传文件超过限制 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadSize(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.error(HttpStatus.PAYLOAD_TOO_LARGE.value(), "上传文件超过大小限制"));
    }

    /** 请求不是 multipart/form-data，或缺少文件参数 */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMultipart(MultipartException ex) {
        String msg = ex.getMessage();
        if (msg != null && msg.contains("not a multipart request")) {
            msg = "请使用表单方式上传 Excel 文件（multipart/form-data，字段名：file）";
        }
        log.warn("Multipart request error: {}", msg);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.badRequest(msg != null && !msg.isBlank() ? msg : "文件上传请求格式错误"));
    }

    /** 缺少必需的请求参数（包括缺少 file 参数） */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
        String param = ex.getParameterName();
        String msg;
        if ("file".equals(param)) {
            msg = "未选择文件或文件字段名不正确，请选择 Excel 文件后重试";
        } else {
            msg = "缺少必需参数: " + param;
        }
        log.warn("Missing request param: {}", msg);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.badRequest(msg));
    }

    /** multipart 请求中缺少指定的文件 part（例：用户在表单中没选文件就点了上传） */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingPart(MissingServletRequestPartException ex) {
        String part = ex.getRequestPartName();
        String msg;
        if ("file".equals(part)) {
            msg = "未选择文件，请选择销售出库的 Excel 文件后再导入";
        } else {
            msg = "请求缺少必需的表单字段: " + part;
        }
        log.warn("Missing multipart part: {}", msg);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.badRequest(msg));
    }

    /** 控制器主动返回的 HTTP 业务状态（如已停用接口 410）。 */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatus(ResponseStatusException ex) {
        int status = ex.getStatusCode().value();
        String message = ex.getReason() == null ? "请求无法处理" : ex.getReason();
        return ResponseEntity.status(ex.getStatusCode())
                .body(ApiResponse.error(status, message));
    }

    /** 数据完整性冲突（外键、唯一约束等） */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation", ex);
        // 提取根因（约束名/SQL 异常信息），便于前端定位
        String rootMsg = ex.getMostSpecificCause() != null ? ex.getMostSpecificCause().getMessage() : ex.getMessage();
        String message = "数据操作冲突，请检查关联数据是否存在或重复";
        if (rootMsg != null && !rootMsg.isBlank()) {
            message = message + "：" + rootMsg;
        }
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(HttpStatus.CONFLICT.value(), message));
    }

    /** 参数非法（业务校验失败） */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class, UnsupportedOperationException.class})
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(RuntimeException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.badRequest(ex.getMessage()));
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ApiResponse<Void>> handleForbidden(SecurityException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(403, ex.getMessage()));
    }

    /**
     * ERP 业务异常（技术方案映射 §5.1 / DA7）。
     *
     * <p>HTTP 状态码仍放在 {@code code}（前端现有判断不变），业务码、字段定位与可修正数据
     * 放在 {@code data} 里，前端如需区分「同一个 422」的具体原因读 {@code data.errorCode}。
     */
    @ExceptionHandler(ErpBusinessException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleErpBusiness(ErpBusinessException ex) {
        log.warn("ERP 业务校验失败 [{}]: {}", ex.errorCode().name(), ex.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorCode", ex.errorCode().name());
        if (ex.field() != null) {
            body.put("field", ex.field());
        }
        if (!ex.detail().isEmpty()) {
            body.put("detail", ex.detail());
        }
        return ResponseEntity.status(ex.errorCode().httpStatus())
                .body(new ApiResponse<>(ex.errorCode().httpStatusCode(), ex.getMessage(), body, null,
                        java.time.LocalDateTime.now()));
    }

    /** 钉钉集成未启用或凭证未配置（登录等场景返回 503 与明确提示）。 */
    @ExceptionHandler(com.liqiang.archive.service.DingtalkNotEnabledException.class)
    public ResponseEntity<ApiResponse<Void>> handleDingtalkDisabled(com.liqiang.archive.service.DingtalkNotEnabledException ex) {
        log.warn("Dingtalk not enabled: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error(HttpStatus.SERVICE_UNAVAILABLE.value(), ex.getMessage()));
    }

    /** 未登录或会话失效（前端应跳转登录页）。 */
    @ExceptionHandler(com.liqiang.archive.security.NotAuthenticatedException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotAuthenticated(com.liqiang.archive.security.NotAuthenticatedException ex) {
        log.warn("Not authenticated: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(HttpStatus.UNAUTHORIZED.value(), ex.getMessage()));
    }

    /** 兜底：未捕获异常 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(Exception ex) {
        log.error("Unexpected error occurred", ex);
        String detail = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.internalError("系统内部错误：" + detail)); // TODO 调试完成后恢复固定文案
    }
}
