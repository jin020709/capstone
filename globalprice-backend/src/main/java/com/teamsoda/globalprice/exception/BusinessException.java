package com.teamsoda.globalprice.exception;

/**
 * 우리가 예상한 오류(상품 없음, 이메일 중복 등)를 알릴 때 던지는 예외.
 *
 * <p>사용 예:
 * <pre>
 *   throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
 *   throw new BusinessException(ErrorCode.INVALID_INPUT, "검색어를 입력해 주세요.");  // 문구만 바꾸고 싶을 때
 * </pre>
 * 던지기만 하면 {@link GlobalExceptionHandler} 가 받아서 정해진 JSON 형식으로 응답합니다.
 * 컨트롤러마다 try-catch 를 쓸 필요가 없습니다.
 */
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
