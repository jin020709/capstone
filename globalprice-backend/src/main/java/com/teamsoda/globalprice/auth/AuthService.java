package com.teamsoda.globalprice.auth;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teamsoda.globalprice.exception.BusinessException;
import com.teamsoda.globalprice.exception.ErrorCode;

@Service
public class AuthService {

    /** 이메일이 없을 때도 BCrypt 비교를 한 번 해서 응답 시간으로 가입 여부가 드러나지 않게 한다 */
    private final String dummyHash;

    public record UserView(long userId, String email, String nickname, LocalDateTime createdAt) {
        static UserView of(User u) {
            return new UserView(u.getId(), u.getEmail(), u.getNickname(),
                    u.getCreatedAt() == null ? null : u.getCreatedAt().truncatedTo(ChronoUnit.SECONDS));
        }
    }

    private final UserRepository users;
    private final BCryptPasswordEncoder encoder;
    private final Clock clock;

    public AuthService(UserRepository users, BCryptPasswordEncoder encoder, Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.clock = clock;
        this.dummyHash = encoder.encode("dummy-password-for-timing");
    }

    @Transactional
    public UserView signup(String email, String nickname, String password) {
        String e = normalize(email);
        if (users.existsByEmail(e)) throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        try {
            User u = users.saveAndFlush(new User(e, encoder.encode(password), nickname.strip(),
                    LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS)));
            return UserView.of(u);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }
    }

    @Transactional(readOnly = true)
    public UserView login(String email, String password) {
        User u = users.findByEmail(normalize(email)).orElse(null);
        if (u == null) {
            encoder.matches(password, dummyHash);
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }
        boolean ok;
        try {
            ok = encoder.matches(password, u.getPasswordHash());
        } catch (IllegalArgumentException ex) {
            ok = false;   // 시드의 'TEST_HASH_VALUE' 처럼 BCrypt 형식이 아닌 값
        }
        if (!ok) throw new BusinessException(ErrorCode.LOGIN_FAILED);
        return UserView.of(u);
    }

    @Transactional(readOnly = true)
    public UserView me(long userId) {
        return users.findById(userId).map(UserView::of)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_REQUIRED));
    }

    static String normalize(String email) {
        return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }
}
