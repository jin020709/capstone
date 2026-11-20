package com.teamsoda.globalprice.web;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서버 상태 확인용 API.
 *
 * <p>브라우저에서 http://localhost:8080/api/health 를 열면
 * {@code {"status":"UP","service":"GlobalPrice","time":"..."}} 이 보입니다.
 *
 * <p>컨트롤러 기본 구조 예시이기도 합니다.
 * <ul>
 *   <li>@RestController : 이 클래스의 메서드가 돌려준 값을 JSON 으로 바꿔 응답</li>
 *   <li>@RequestMapping("/api") : 이 클래스의 모든 주소 앞에 /api 가 붙음</li>
 *   <li>@GetMapping("/health") : GET /api/health 요청을 이 메서드가 처리</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();   // 넣은 순서대로 JSON 출력
        body.put("status", "UP");
        body.put("service", "GlobalPrice");
        body.put("time", LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString());
        return body;
    }
}
