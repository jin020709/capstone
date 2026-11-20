package com.teamsoda.globalprice.support;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 테스트용 가짜 HTTP 서버 (JDK 내장 HttpServer). 경로별로 상태 · 본문 · 지연을 정하고 호출 수를 센다.
 * MockWebServer 대신 써서 의존성을 늘리지 않는다.
 */
public class MockHttp implements AutoCloseable {

    public record Response(int status, String body, String contentType, long delayMillis) {
        public static Response json(String body) {
            return new Response(200, body, "application/json", 0);
        }

        public static Response status(int status, String body) {
            return new Response(status, body, "application/json", 0);
        }

        public Response delayed(long millis) {
            return new Response(status, body, contentType, millis);
        }
    }

    public record Request(String method, String path, String query, Map<String, List<String>> headers, String body) {
        public String header(String name) {
            for (var e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) return e.getValue().getFirst();
            }
            return null;
        }
    }

    private final HttpServer server;
    private final Map<String, Response> routes = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    public MockHttp() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** path 는 쿼리 없이 (예: /buy/browse/v1/item_summary/search) */
    public MockHttp on(String path, Response response) {
        routes.put(path, response);
        return this;
    }

    public int count(String path) {
        AtomicInteger c = counts.get(path);
        return c == null ? 0 : c.get();
    }

    public int totalCount() {
        return requests.size();
    }

    public List<Request> requests() {
        return requests;
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Request(ex.getRequestMethod(), path, ex.getRequestURI().getRawQuery(),
                Map.copyOf(ex.getRequestHeaders()), body));
        counts.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
        Response r = routes.get(path);
        if (r == null) {
            for (var e : routes.entrySet()) {
                if (e.getKey().endsWith("*") && path.startsWith(e.getKey().substring(0, e.getKey().length() - 1))) {
                    r = e.getValue();
                }
            }
        }
        if (r == null) r = Response.status(404, "{}");
        try {
            if (r.delayMillis() > 0) Thread.sleep(r.delayMillis());
            byte[] bytes = r.body() == null ? new byte[0] : r.body().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", r.contentType());
            ex.sendResponseHeaders(r.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(bytes);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // 클라이언트가 타임아웃으로 먼저 끊은 경우
        } finally {
            ex.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
