package com.teamsoda.globalprice.source;

/**
 * 소스 호출 실패. 소스 하나가 실패해도 다른 소스 결과는 그대로 쓴다.
 * 메시지에는 키 · 서명 · Authorization 값을 넣지 않는다.
 */
public abstract sealed class SourceException extends RuntimeException
        permits SourceException.RateLimited, SourceException.AuthFailed, SourceException.Timeout,
        SourceException.BadResponse {

    private static final long serialVersionUID = 1L;

    private final Integer httpStatus;

    protected SourceException(String message, Integer httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    /** 429 */
    public static final class RateLimited extends SourceException {
        private static final long serialVersionUID = 1L;

        public RateLimited(String message) {
            super(message, 429);
        }
    }

    /** 401 · 403 · 키 없음 */
    public static final class AuthFailed extends SourceException {
        private static final long serialVersionUID = 1L;

        public AuthFailed(String message, Integer httpStatus) {
            super(message, httpStatus);
        }
    }

    public static final class Timeout extends SourceException {
        private static final long serialVersionUID = 1L;

        public Timeout(String message) {
            super(message, null);
        }
    }

    /** 5xx · 형식 오류 · 그 밖의 HTTP 오류. retryable 은 5xx 일 때만 true */
    public static final class BadResponse extends SourceException {
        private static final long serialVersionUID = 1L;

        public BadResponse(String message, Integer httpStatus) {
            super(message, httpStatus);
        }

        public boolean retryable() {
            return httpStatus() == null || httpStatus() >= 500;
        }
    }
}
