package com.dbstudio.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

public final class LocalAccessToken {
    public static final String COOKIE_NAME = "DBSTUDIO_SESSION";
    private final String value;

    public LocalAccessToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        this.value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        java.util.Arrays.fill(bytes, (byte) 0);
    }

    public String launchValue() { return value; }

    public boolean matches(String candidate) {
        if (candidate == null) return false;
        return MessageDigest.isEqual(value.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }

    public boolean authenticated(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return false;
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName()) && matches(cookie.getValue())) return true;
        }
        return false;
    }
}
