package com.example.sysfoo.security;

import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.ConcurrentSessionControlAuthenticationStrategy;
import org.springframework.security.web.authentication.session.RegisterSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.session.ConcurrentSessionFilter;
import org.springframework.security.web.session.HttpSessionEventPublisher;

import java.util.List;

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
 *   • GET /todos: requires a signed-in user (see TodoController) — it now
 *     filters to only the tasks that user created or is assigned to, so it
 *     needs to know who's asking. GET /api/posts (the public Watering Hole
 *     board) has no such per-row rule and stays public at the API level.
 *   • POST /todos, POST /api/posts, POST /api/notify: require a signed-in user.
 *   • Everything else under /todos/** (PATCH/DELETE on a task, its comments,
 *     its attachments) requires a signed-in user at this layer; TodoController
 *     itself enforces the actual creator-or-assignee check per task.
 *   • GET /api/users and /api/users/me/profile: require a signed-in user.
 *   • GET /api/files/{id}: public at this layer — FileController decides per
 *     download (public for a post's image/file, creator-or-assignee only
 *     for a task's attachment).
 *   • /api/auth/register and /api/auth/login: public (that's the point).
 *   • /api/auth/forgot-password, /api/auth/reset-password, /api/auth/verify-email: public.
 *   • GET /api/csrf: public — hands the frontend a CSRF token/cookie before
 *     it needs to submit its first form.
 *   • /api/auth/logout, /api/auth/logout-everywhere: require a signed-in user.
 *   • GET /actuator/health: public — the container/orchestrator health probe
 *     calls this unauthenticated. Only "health" is exposed (see
 *     application.properties) and it never returns dependency details.
 *
 * ── SECURITY FIX: CSRF protection ────────────────────────────────────────
 *   CSRF used to be disabled outright here — an accepted trade-off noted in
 *   an earlier version of this class. It's now enabled using the
 *   cookie-based "double submit" pattern Spring Security documents for
 *   single-page apps: the token is handed to the browser as a readable
 *   (non-HttpOnly) cookie, and the frontend echoes it back as a header
 *   (X-XSRF-TOKEN) on every state-changing request. See GET /api/csrf
 *   (CsrfController) for how the frontend obtains the first token, and
 *   static/index.html + static/login.html's shared `apiFetch` helper for
 *   how every mutating fetch() call attaches the header automatically.
 *   csrfTokenRequestHandler is the plain (non-XOR) handler because the
 *   frontend reads and resubmits the raw cookie value verbatim, rather than
 *   an XOR-masked one — this is Spring Security's documented pairing for
 *   cookie-based SPA CSRF.
 *
 * ── SECURITY FIX: session management ─────────────────────────────────────
 *   Added a SessionRegistry-backed setup so the app can enforce a cap on
 *   concurrent sessions per account and expose "sign out everywhere" (see
 *   AuthController.logoutEverywhere). Because login is handled manually in
 *   AuthController (not through Spring's own login filter), the
 *   SessionAuthenticationStrategy bean below is invoked explicitly from
 *   there right after authentication succeeds — configuring it here alone
 *   would silently do nothing, since the normal filter that would trigger
 *   it automatically is disabled (see AuthController.authenticateAndPersist).
 *   Session idle/absolute timeout itself is configured via
 *   server.servlet.session.timeout in application.properties, which the
 *   servlet container enforces without any code here.
 *
 * ── Known, documented simplification ─────────────────────────────────────
 *   The login gate on index.html is a client-side UX redirect, not a
 *   server-side access boundary — a determined caller can still hit the
 *   GET endpoints directly. That's an accepted trade-off for this project;
 *   a stricter deployment would also gate GET /todos and GET /api/posts.
 *   SessionRegistry here is the default in-memory implementation, which
 *   (like the rate limiter and local file storage elsewhere in this app)
 *   only works correctly for a single application instance — a
 *   multi-instance deployment would need a shared/external session store.
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
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /**
     * Publishes HttpSessionEvent notifications into Spring's ApplicationContext
     * so SessionRegistry finds out when a session actually expires/is
     * invalidated (e.g. the servlet container's own idle timeout) and cleans
     * up its bookkeeping — without this, SessionRegistry would think expired
     * sessions were still active forever.
     */
    @Bean
    public ServletListenerRegistrationBean<HttpSessionEventPublisher> httpSessionEventPublisher() {
        return new ServletListenerRegistrationBean<>(new HttpSessionEventPublisher());
    }

    /**
     * Invoked manually by AuthController right after a successful manual
     * authentication (see the class javadoc for why). Order matters:
     *   1. Rotate the session ID (fixation protection).
     *   2. Enforce the per-account concurrent session cap, expiring the
     *      oldest session if a new login pushes the count over the limit
     *      rather than blocking the new login outright.
     *   3. Register the new session so it shows up for "sign out everywhere".
     */
    @Bean
    public SessionAuthenticationStrategy sessionAuthenticationStrategy(SessionRegistry sessionRegistry) {
        ConcurrentSessionControlAuthenticationStrategy concurrentStrategy =
                new ConcurrentSessionControlAuthenticationStrategy(sessionRegistry);
        concurrentStrategy.setMaximumSessions(5);
        concurrentStrategy.setExceptionIfMaximumExceeded(false);

        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(),
                concurrentStrategy,
                new RegisterSessionAuthenticationStrategy(sessionRegistry)
        ));
    }

    /**
     * Watches every request for a SessionInformation that's been marked
     * expired (e.g. by AuthController.logoutEverywhere calling
     * SessionInformation.expireNow()) and invalidates that session
     * immediately, rejecting the request with a clear JSON message instead
     * of letting it through as if still authenticated.
     */
    @Bean
    public ConcurrentSessionFilter concurrentSessionFilter(SessionRegistry sessionRegistry) {
        return new ConcurrentSessionFilter(sessionRegistry, event -> {
            event.getResponse().setContentType(MediaType.APPLICATION_JSON_VALUE);
            event.getResponse().setStatus(401);
            event.getResponse().getWriter().write(
                    "{\"status\":\"error\",\"message\":\"Your session was signed out from another device\"}");
        });
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                            SecurityContextRepository securityContextRepository,
                                            ConcurrentSessionFilter concurrentSessionFilter) throws Exception {
        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();

        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(csrfRequestHandler)
            )
            .securityContext(sc -> sc.securityContextRepository(securityContextRepository))
            .addFilterBefore(concurrentSessionFilter, org.springframework.security.web.context.SecurityContextHolderFilter.class)
            .authorizeHttpRequests(auth -> auth
                // Dashboard shell & static assets
                .requestMatchers("/", "/index.html", "/login.html", "/css/**", "/js/**", "/favicon.ico").permitAll()
                // BUG FIX: the container HEALTHCHECK / a Kubernetes liveness probe hits
                // this endpoint unauthenticated — without this rule it fell through to
                // .anyRequest().authenticated() and every health check got a 401, which
                // orchestrators treat exactly the same as a failing health check.
                // Only the "health" endpoint is exposed at all (see application.properties),
                // and show-details=never means it leaks nothing beyond UP/DOWN.
                .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                // Public read-only system / database info
                .requestMatchers(HttpMethod.GET, "/system-info", "/version", "/database-info").permitAll()
                // CSRF token bootstrap — must be reachable before the user has a session.
                .requestMatchers(HttpMethod.GET, "/api/csrf").permitAll()
                // Public auth endpoints
                .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login", "/api/auth/force-change-password").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/auth/me", "/api/auth/config").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/forgot-password", "/api/auth/reset-password").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/auth/verify-email").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/resend-verification").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/auth/logout-everywhere").authenticated()
                // ENHANCEMENT ("assignee can only see the task, rest can't view
                // it"): GET /todos used to be public (see the old comment this
                // replaced) — it now needs to know WHO is asking so it can
                // filter to just that person's created/assigned tasks (see
                // TodoController.getAllTodos()), so it requires authentication.
                // The Watering Hole board has no such per-row visibility rule
                // and stays public.
                .requestMatchers(HttpMethod.GET, "/todos").authenticated()
                // ENHANCEMENT (post editing/deleting, likes/comments): GET
                // extended to cover /api/posts/{id}/comments too — comments
                // on a public post are public, same as the post itself.
                .requestMatchers(HttpMethod.GET, "/api/posts", "/api/posts/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/todos", "/api/posts", "/api/posts/**", "/api/notify", "/api/auth/logout").authenticated()
                .requestMatchers(HttpMethod.PATCH, "/api/posts/**").authenticated()
                .requestMatchers(HttpMethod.DELETE, "/api/posts/**").authenticated()
                // Comments/attachments sub-resources, and PATCH/DELETE on a
                // specific task, all need a signed-in user — TodoController's
                // own creator-or-assignee check narrows it further per task.
                .requestMatchers("/todos/**").authenticated()
                // ENHANCEMENT: user directory (assignee picker) and the
                // profile endpoint both require a signed-in user.
                .requestMatchers(HttpMethod.GET, "/api/users", "/api/users/**").authenticated()
                .requestMatchers(HttpMethod.PATCH, "/api/users/me/preferences").authenticated()
                // ENHANCEMENT ("roles & permissions"): authenticated here —
                // AdminController itself enforces the actual admin check
                // (returning 404 for a non-admin), same pattern as every
                // other creator/assignee-only check in this app.
                .requestMatchers("/api/admin/**").authenticated()
                // ENHANCEMENT ("real-time updates"): long-lived SSE connection, authenticated like everything else.
                .requestMatchers(HttpMethod.GET, "/api/events").authenticated()
                // ENHANCEMENT: global search across tasks and posts — needs to know who's asking (task results are scoped to creator-or-assignee).
                .requestMatchers(HttpMethod.GET, "/api/search").authenticated()
                // ENHANCEMENT: file downloads — permitAll at this layer because
                // FileController itself decides per-attachment (public for a
                // post's image/file, creator-or-assignee only for a task's).
                .requestMatchers(HttpMethod.GET, "/api/files/**").permitAll()
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
                    // SECURITY FIX (CSRF re-enabled): a missing/invalid/expired
                    // CSRF token surfaces here too (both are AccessDeniedException
                    // subclasses) — give a message that tells a legitimate user
                    // what to actually do about it, instead of a bare "Access denied".
                    if (accessDeniedException instanceof org.springframework.security.web.csrf.CsrfException) {
                        response.getWriter().write(
                                "{\"status\":\"error\",\"message\":\"Your session token expired — please refresh the page and try again\"}");
                    } else {
                        response.getWriter().write("{\"status\":\"error\",\"message\":\"Access denied\"}");
                    }
                })
            );

        return http.build();
    }
}
