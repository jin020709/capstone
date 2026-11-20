package com.teamsoda.globalprice.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.teamsoda.globalprice.common.ErrorResponse;

/**
 * 전역 예외 처리기.
 *
 * <p>컨트롤러 · 서비스 어디서든 예외가 터지면 여기로 모입니다.
 * 예외 종류별로 알맞은 HTTP 상태코드와 {@link ErrorResponse} JSON 으로 바꿔서 응답합니다.
 *
 * <ul>
 *   <li>우리가 던진 예외(BusinessException) → ErrorCode 에 적힌 상태 · 문구</li>
 *   <li>입력값 검증 실패 · 잘못된 JSON · 파라미터 누락 → 400</li>
 *   <li>없는 주소 → 404, 지원하지 않는 메서드 → 405</li>
 *   <li>예상 못 한 오류 → 500. 내부 오류 내용은 로그에만 남기고 사용자에게는 일반 문구만 보냄</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 우리가 직접 던진 예외 (상품 없음, 이메일 중복 등) */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        ErrorCode code = e.getErrorCode();
        log.info("[BusinessException] {} - {}", code.name(), e.getMessage());
        return build(code, e.getMessage());
    }

    /** @Valid 검증 실패 (예: 이메일 형식이 아님). 첫 번째 필드 오류 문구를 보여 줌 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = (fieldError != null && fieldError.getDefaultMessage() != null)
                ? fieldError.getDefaultMessage()
                : ErrorCode.INVALID_INPUT.getMessage();
        return build(ErrorCode.INVALID_INPUT, message);
    }

    /** 요청 본문 JSON 이 깨졌거나 타입이 맞지 않음 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException e) {
        return build(ErrorCode.INVALID_INPUT, "요청 형식이 올바르지 않아요.");
    }

    /** 필수 쿼리 파라미터 누락 (예: ?q= 없음) */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException e) {
        return build(ErrorCode.INVALID_INPUT, "필수 값(" + e.getParameterName() + ")이 빠졌어요.");
    }

    /** 파라미터 타입 불일치 (예: /api/products/abc 처럼 숫자 자리에 글자) */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return build(ErrorCode.INVALID_INPUT, "값의 형식이 올바르지 않아요: " + e.getName());
    }

    /** 없는 주소 (컨트롤러도 없고 static 파일도 없음) */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException e) {
        return build(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.getMessage());
    }

    /** GET 만 되는 주소에 POST 를 보내는 경우 등 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethod(HttpRequestMethodNotSupportedException e) {
        return build(ErrorCode.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED.getMessage());
    }

    /** 그 밖의 모든 예상 못 한 오류. 원인은 로그로만 남김 (사용자에게 내부 정보 노출 금지) */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("[UnexpectedException]", e);
        return build(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getMessage());
    }

    private ResponseEntity<ErrorResponse> build(ErrorCode code, String message) {
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code, message));
    }
}
