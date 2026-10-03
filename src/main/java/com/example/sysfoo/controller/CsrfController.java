package com.example.sysfoo.controller;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/csrf — public endpoint the frontend calls once on page load,
 * before it ever needs to submit a form.
 *
 * Spring Security's CSRF token is loaded "on demand" (deferred): nothing
 * generates or writes the token cookie until some code actually resolves
 * the CsrfToken for the current request. Simply declaring it as a method
 * parameter here forces that resolution — Spring Security recognises
 * CsrfToken as a resolvable controller argument and, as a side effect of
 * this request, writes the XSRF-TOKEN cookie configured in SecurityConfig
 * (CookieCsrfTokenRepository.withHttpOnlyFalse()). The JSON response
 * (token/headerName/parameterName) is a convenience for the frontend, but
 * since the cookie is readable by JS, index.html/login.html's apiFetch
 * helper actually just reads the cookie directly for every request rather
 * than caching this response.
 */
@RestController
public class CsrfController {

    @GetMapping("/api/csrf")
    public CsrfToken csrf(CsrfToken token) {
        return token;
    }
}
