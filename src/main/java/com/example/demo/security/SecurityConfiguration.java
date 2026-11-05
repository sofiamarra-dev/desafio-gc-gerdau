package com.example.demo.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration
public class SecurityConfiguration {

    private static final String LOGIN_PAGE = "/login.html";

    private static final String[] PUBLIC_ROUTES = {
            "/login.html",
            "/design/**",
            "/login-bridge.js",
            "/token-login.html",
            "/enviar",
            "/verificar",
            "/api/auth/status",
            "/api/health",
            "/error"
    };

    private static final String[] ADMIN_ROUTES = {
            "/api/abbreviations",
            "/api/abbreviations/**",
            "/api/cleanups",
            "/api/cleanups/**",
            "/siglas.*",
            "/limpeza.*"
    };

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, SecurityContextRepository repository, @Value("${app.auth.enabled:true}") boolean authenticationEnabled) throws Exception {

        if (!authenticationEnabled) {
            http.csrf(csrf -> csrf.disable());

            http.authorizeHttpRequests(access -> {
                access.anyRequest().permitAll();
            });

            return http.build();
        }


        CookieCsrfTokenRepository csrfRepository = new CookieCsrfTokenRepository();

        csrfRepository.setCookieCustomizer(cookie -> {
            cookie.sameSite("Strict");
        });

        http.csrf(csrf -> {
            csrf.csrfTokenRepository(csrfRepository);


            csrf.ignoringRequestMatchers("/enviar", "/verificar");
        });


        http.securityContext(context -> {
            context.securityContextRepository(repository);
        });


        http.headers(headers -> {
            headers.frameOptions(frame -> frame.sameOrigin());
        });


        http.authorizeHttpRequests(access -> {
            access.requestMatchers(PUBLIC_ROUTES).permitAll();

            access.requestMatchers(ADMIN_ROUTES).hasRole("ADMIN");

            access.anyRequest().authenticated();
        });


        http.exceptionHandling(errors -> {
            errors.authenticationEntryPoint(
                    (request, response, exception) ->
                            handleUnauthenticatedRequest(request, response)
            );

            errors.accessDeniedHandler(
                    (request, response, exception) ->
                            writeJsonError(
                                    response,
                                    HttpServletResponse.SC_FORBIDDEN,
                                    "Acesso não permitido para esta conta ou sessão expirada."
                            )
            );
        });


        http.logout(logout -> {
            logout.logoutUrl("/api/auth/logout");
            logout.invalidateHttpSession(true);
            logout.clearAuthentication(true);
            logout.deleteCookies("JSESSIONID");

            logout.logoutSuccessHandler(
                    (request, response, authentication) ->
                            response.setStatus(HttpServletResponse.SC_NO_CONTENT)
            );
        });

        return http.build();
    }

    private void handleUnauthenticatedRequest(
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {

        String acceptHeader = request.getHeader("Accept");

        boolean isApiRequest =
                request.getRequestURI().startsWith("/api/");

        boolean expectsJson =
                acceptHeader != null
                        && acceptHeader.contains("application/json");

        if (isApiRequest || expectsJson) {
            writeJsonError(
                    response,
                    HttpServletResponse.SC_UNAUTHORIZED,
                    "Entre com seu e-mail para continuar."
            );

            return;
        }

        response.sendRedirect(LOGIN_PAGE);
    }


    private void writeJsonError(HttpServletResponse response, int status, String message) throws IOException {

        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");

        response.getWriter().write(
                "{\"error\":\"" + message + "\"}"
        );
    }
}