package com.teamsoda.globalprice.source;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.function.Supplier;

import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * RestClient 예외 → SourceException 변환. 응답 본문 · URL 은 메시지에 넣지 않는다 (키가 섞일 수 있음).
 */
public final class SourceHttp {

    private SourceHttp() {
    }

    public static <T> T call(String source, Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientResponseException e) {
            int code = e.getStatusCode().value();
            if (code == 429) throw new SourceException.RateLimited(source + " 호출 한도 초과 (429)");
            if (code == 401 || code == 403) throw new SourceException.AuthFailed(source + " 인증 실패 (" + code + ")", code);
            throw new SourceException.BadResponse(source + " HTTP " + code, code);
        } catch (ResourceAccessException e) {
            if (isTimeout(e)) throw new SourceException.Timeout(source + " 응답 시간 초과");
            throw new SourceException.BadResponse(source + " 연결 실패: " + rootName(e), null);
        } catch (RestClientException e) {
            throw new SourceException.BadResponse(source + " 응답 처리 실패: " + rootName(e), null);
        }
    }

    static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException || t instanceof HttpTimeoutException) return true;
            if (t instanceof IOException && t.getMessage() != null && t.getMessage().toLowerCase().contains("timed out")) {
                return true;
            }
        }
        return false;
    }

    private static String rootName(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getClass().getSimpleName();
    }
}
