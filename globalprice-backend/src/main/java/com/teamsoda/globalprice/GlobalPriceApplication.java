package com.teamsoda.globalprice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 프로그램 시작점. 이 main 을 실행하면 내장 웹 서버(Tomcat)가 8080 포트로 켜집니다.
 *
 * <p>@SpringBootApplication 이 붙은 이 클래스의 패키지(com.teamsoda.globalprice)와
 * 그 하위 패키지에 있는 @RestController, @Service 등을 Spring 이 자동으로 찾아서 등록합니다.
 * 그래서 새 클래스는 반드시 이 패키지 아래에 만들어야 합니다.
 */
@SpringBootApplication
public class GlobalPriceApplication {

    public static void main(String[] args) {
        // 시각은 모두 한국 시간 (오류 응답 timestamp 등 Clock 을 쓰지 않는 곳 포함)
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Seoul"));
        SpringApplication.run(GlobalPriceApplication.class, args);
    }
}
