package com.dbstudio.server;

import java.util.Map;
import javax.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public final class AuthController {
    private final LocalAccessToken token;
    public AuthController(LocalAccessToken token) { this.token = token; }

    @PostMapping("/exchange")
    public Map<String, Object> exchange(@RequestBody Map<String, Object> body, HttpServletResponse response) {
        if (!token.matches(ApiPayloads.text(body, "token"))) {
            throw new ApiException("INVALID_LAUNCH_TOKEN", "启动令牌无效");
        }
        response.addHeader(HttpHeaders.SET_COOKIE, LocalAccessToken.COOKIE_NAME + "=" + token.launchValue()
                + "; Path=/; HttpOnly; SameSite=Strict");
        return ApiPayloads.map("authenticated", true);
    }
}
