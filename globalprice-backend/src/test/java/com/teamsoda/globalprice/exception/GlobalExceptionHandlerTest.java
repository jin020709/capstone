package com.teamsoda.globalprice.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * 오류가 났을 때 정해진 JSON 형식 { status, code, message, timestamp } 으로 응답하는지 확인.
 * 테스트 전용 가짜 컨트롤러(TestController)로 일부러 오류를 일으킵니다.
 */
class GlobalExceptionHandlerTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new TestController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void businessException_usesErrorCodeStatusAndMessage() throws Exception {
        mockMvc.perform(get("/test/product-missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("상품을 찾을 수 없어요."))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void validationFailure_returns400WithFieldMessage() throws Exception {
        mockMvc.perform(post("/test/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value("이메일 형식이 올바르지 않아요."));
    }

    @Test
    void brokenJson_returns400() throws Exception {
        mockMvc.perform(post("/test/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void missingParam_returns400() throws Exception {
        mockMvc.perform(get("/test/search"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("필수 값(q)이 빠졌어요."));
    }

    @Test
    void typeMismatch_returns400() throws Exception {
        mockMvc.perform(get("/test/products/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void wrongMethod_returns405() throws Exception {
        mockMvc.perform(post("/test/search"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void unexpectedError_returns500WithoutLeakingDetails() throws Exception {
        mockMvc.perform(get("/test/crash"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("서버에 문제가 생겼어요. 잠시 후 다시 시도해 주세요."));
    }

    // ---------------- 테스트 전용 컨트롤러 ----------------

    record SignupForm(
            @NotBlank(message = "이메일을 입력해 주세요.")
            @Email(message = "이메일 형식이 올바르지 않아요.")
            String email) {
    }

    @RestController
    static class TestController {

        @GetMapping("/test/product-missing")
        String productMissing() {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }

        @PostMapping("/test/signup")
        String signup(@Valid @RequestBody SignupForm form) {
            return "ok";
        }

        @GetMapping("/test/search")
        String search(@RequestParam("q") String q) {
            return q;
        }

        @GetMapping("/test/products/{id}")
        String product(@PathVariable("id") Long id) {
            return String.valueOf(id);
        }

        @GetMapping("/test/crash")
        String crash() {
            throw new IllegalStateException("DB password=secret 같은 내부 정보");
        }
    }
}
