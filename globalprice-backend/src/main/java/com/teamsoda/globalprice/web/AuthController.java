package com.teamsoda.globalprice.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.teamsoda.globalprice.auth.AuthService;
import com.teamsoda.globalprice.auth.AuthService.UserView;
import com.teamsoda.globalprice.auth.LoginInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 회원가입 · 로그인 (세션). 비밀번호는 응답 · 로그에 넣지 않는다 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record SignupForm(
            @NotBlank(message = "이메일을 입력해 주세요.") @Email(message = "이메일 형식이 올바르지 않아요.")
            @Size(max = 255, message = "이메일이 너무 길어요.") String email,
            @NotBlank(message = "닉네임을 입력해 주세요.") @Size(max = 50, message = "닉네임은 50자 이내로 입력해 주세요.")
            String nickname,
            @NotBlank(message = "비밀번호를 입력해 주세요.")
            @Size(min = 8, max = 72, message = "비밀번호는 8~72자로 입력해 주세요.") String password) {
        @Override public String toString() { return "SignupForm[email=?]"; }
    }

    public record LoginForm(
            @NotBlank(message = "이메일을 입력해 주세요.") String email,
            @NotBlank(message = "비밀번호를 입력해 주세요.") String password) {
        @Override public String toString() { return "LoginForm[?]"; }
    }

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/signup")
    public ResponseEntity<UserView> signup(@Valid @RequestBody SignupForm form) {
        return ResponseEntity.status(HttpStatus.CREATED).body(auth.signup(form.email(), form.nickname(), form.password()));
    }

    @PostMapping("/login")
    public UserView login(@Valid @RequestBody LoginForm form, HttpServletRequest request) {
        UserView u = auth.login(form.email(), form.password());
        HttpSession session = request.getSession(true);
        request.changeSessionId();   // 세션 고정 공격 방지
        session.setAttribute(LoginInterceptor.SESSION_USER_ID, u.userId());
        return u;
    }

    @GetMapping("/me")
    public UserView me(HttpServletRequest request) {
        return auth.me(LoginInterceptor.currentUserId(request));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        return ResponseEntity.noContent().build();
    }
}
