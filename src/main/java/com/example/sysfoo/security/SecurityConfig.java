package com.example.sysfoo.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * Session-cookie based security configuration for the Sysfoo dashboard.
 *
 * Login/registration/logout are handled by {@code AuthController} (not Spring
 * Security's built-in form-login filter) so that {@code login.html} — a
 * dedicated login page, separate from the dashboard — can call plain JSON
 * endpoints and get JSON responses instead of redirects.
 *
 * ── Access rules ─────────────────────────────────────────────────────────
 *   • index.html, login.html, static assets, and read-only system/DB info: public
 *     at the HTTP layer. index.html itself is gated client-side: it calls
 *     GET /api/auth/me on load and redirects to login.html if there's no
 *     session, so in practice the dashboard is only ever seen by signed-in
 *     users, while login.html does the reverse (redirects straight to
 *     index.html if a session already exists).
 *   • GET /todos and GET /api/posts (viewing tasks/posts): public at the API
 *     level — this keeps the data endpoints simple/reusable — but the only
 *     page that calls them (index.html) is itself behind the client-side
 *     login gate described above.
 *   • POST /todos, POST /api/posts, POST /api/notify: require a signed-in user.
 *   • /api/auth/register and /api/auth/login: public (that's the point).
 *   • /api/auth/logout: requires a signed-in user.
 *
 * ── Known simplification ────────────────────────────────────────────────
 *   CSRF protection is disabled here. This is an accepted trade-off for a
 *   small practice/demo app using session cookies with a same-origin JSON
 *   API and no third-party embedding. For a real production deployment,
 *   re-enable CSRF and expose the token to the frontend (e.g. a `/api/csrf`
 *   endpoint that hands back the token for subsequent POSTs), or switch to
 *   stateless bearer-token (JWT) authentication instead of session cookies.
 *   Likewise, the login gate on index.html is a client-side UX redirect, not
 *   a server-side access boundary — a determined caller can still hit the
 *   GET endpoints directly. That's an accepted trade-off for this project;
 *   a stricter deployment would also gate GET /todos and GET /api/posts.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, SecurityContextRepository securityContextRepository) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .securityContext(sc -> sc.securityContextRepository(securityContextRepository))
            .authorizeHttpRequests(auth -> auth
                // Dashboard shell & static assets
                .requestMatchers("/", "/index.html", "/login.html", "/css/**", "/js/**", "/favicon.ico").permitAll()
                // Public read-only system / database info
                .requestMatchers(HttpMethod.GET, "/system-info", "/version", "/database-info").permitAll()
                // Public auth endpoints
                .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/auth/me").permitAll()
                // Public read access; writes require a signed-in user
                .requestMatchers(HttpMethod.GET, "/todos", "/api/posts").permitAll()
                .requestMatchers(HttpMethod.POST, "/todos", "/api/posts", "/api/notify", "/api/auth/logout").authenticated()
                .anyRequest().authenticated()
            )
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .logout(logout -> logout.disable()) // logout is handled by AuthController
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setStatus(401);
                    response.getWriter().write("{\"status\":\"error\",\"message\":\"Login required\"}");
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setStatus(403);
                    response.getWriter().write("{\"status\":\"error\",\"message\":\"Access denied\"}");
                })
            );

        return http.build();
    }
}
