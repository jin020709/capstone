package com.teamsoda.globalprice.exception;

import org.springframework.http.HttpStatus;

/**
 * 서비스 전체에서 쓰는 오류 목록.
 *
 * <p>오류가 생기면 여기 있는 코드 중 하나로 응답합니다.
 * message 는 프론트 화면에 그대로 보여 줄 문구라서, 사용자에게 보여 줘도 되는 말로 씁니다.
 * (프론트 api.js 는 오류 응답의 message 를 화면에 띄웁니다)
 *
 * <p>새 오류가 필요하면 여기에 한 줄 추가하고, 서비스 코드에서
 * {@code throw new BusinessException(ErrorCode.XXX);} 로 던지면 됩니다.
 */
public enum ErrorCode {

    // ---- 400 : 요청이 잘못됨 ----
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요."),

    // ---- 401 / 403 : 로그인 · 권한 ----
    LOGIN_REQUIRED(HttpStatus.UNAUTHORIZED, "로그인이 필요해요."),
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호를 확인해 주세요."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없어요."),

    // ---- 404 : 없음 ----
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 주소를 찾을 수 없어요."),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "상품을 찾을 수 없어요."),

    // ---- 405 / 409 ----
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식이에요."),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 가입된 이메일이에요."),

    // ---- 5xx : 서버 · 외부 API ----
    EXTERNAL_INVALID_RESPONSE(HttpStatus.BAD_GATEWAY, "쇼핑몰 응답을 해석하지 못했어요. 잠시 후 다시 시도해 주세요."),
    EXTERNAL_API_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "가격 정보를 잠시 불러올 수 없어요. 잠시 후 다시 시도해 주세요."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버에 문제가 생겼어요. 잠시 후 다시 시도해 주세요.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
