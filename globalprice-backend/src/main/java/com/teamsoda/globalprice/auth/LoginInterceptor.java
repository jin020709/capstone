package com.teamsoda.globalprice.auth;

import org.springframework.web.servlet.HandlerInterceptor;

import com.teamsoda.globalprice.exception.BusinessException;
import com.teamsoda.globalprice.exception.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/** 로그인이 필요한 주소에서 세션에 회원 ID 가 없으면 401 LOGIN_REQUIRED */
public class LoginInterceptor implements HandlerInterceptor {

    public static final String SESSION_USER_ID = "LOGIN_USER_ID";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute(SESSION_USER_ID) == null) {
            throw new BusinessException(ErrorCode.LOGIN_REQUIRED);
        }
        return true;
    }

    public static long currentUserId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object id = session == null ? null : session.getAttribute(SESSION_USER_ID);
        if (id == null) throw new BusinessException(ErrorCode.LOGIN_REQUIRED);
        return (Long) id;
    }

    public static Long currentUserIdOrNull(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object id = session == null ? null : session.getAttribute(SESSION_USER_ID);
        return id == null ? null : (Long) id;
    }
}
