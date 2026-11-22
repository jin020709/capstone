package com.teamsoda.globalprice.common;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import com.teamsoda.globalprice.exception.ErrorCode;

/**
 * 모든 오류 응답의 공통 형식.
 *
 * <pre>
 * { "status": 404, "code": "PRODUCT_NOT_FOUND", "message": "상품을 찾을 수 없어요.", "timestamp": "2026-11-12T19:30:00" }
 * </pre>
 *
 * <p>형식은 BACKEND_SPEC.md 6-1. 프론트는 code 로 화면을 나누고 message 를 보여 줍니다.
 * status · code · timestamp 는 디버깅과 로그 확인용으로 함께 보냅니다.
 *
 * <p>성공 응답은 감싸지 않고 데이터를 그대로 보냅니다 (BACKEND_SPEC.md 6-0).
 *
 * <p>record : Java 16+ 문법. 필드 · 생성자 · getter 를 자동으로 만들어 주는 간단한 데이터 클래스입니다.
 */
public record ErrorResponse(int status, String code, String message, String timestamp) {

    public static ErrorResponse of(ErrorCode errorCode) {
        return of(errorCode, errorCode.getMessage());
    }

    public static ErrorResponse of(ErrorCode errorCode, String message) {
        return new ErrorResponse(
                errorCode.getStatus().value(),
                errorCode.name(),
                message,
                LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString());
    }
}
