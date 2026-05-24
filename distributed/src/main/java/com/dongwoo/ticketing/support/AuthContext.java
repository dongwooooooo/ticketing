package com.dongwoo.ticketing.support;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Stage 1 mock 인증. 실제 JWT 검증 없이 X-User-Id 헤더만 추출.
 * Stage 4에서 진짜 IdP 통합으로 교체.
 */
@Component
public class AuthContext {

    public String currentUserId(HttpServletRequest request) {
        String userId = request.getHeader("X-User-Id");
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("X-User-Id header required");
        }
        return userId;
    }
}
