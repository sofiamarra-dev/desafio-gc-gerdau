package com.example.demo.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.*;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration
public class SecurityConfiguration {
    @Bean SecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }
    @Bean SecurityFilterChain security(HttpSecurity http,SecurityContextRepository repository,
                                      @Value("${app.auth.enabled:true}") boolean enabled) throws Exception {
        if (!enabled) { http.csrf(csrf->csrf.disable()).authorizeHttpRequests(auth->auth.anyRequest().permitAll());return http.build(); }
        var csrf=new CookieCsrfTokenRepository();csrf.setCookieCustomizer(cookie->cookie.sameSite("Strict"));
        http.csrf(config->config.csrfTokenRepository(csrf).ignoringRequestMatchers("/enviar","/verificar"))
            .securityContext(context->context.securityContextRepository(repository))
            .headers(headers->headers.frameOptions(frame->frame.sameOrigin()))
            .authorizeHttpRequests(auth->auth
                .requestMatchers("/login.html","/login-bridge.js","/token-login.html","/enviar","/verificar","/api/auth/status","/api/health","/error").permitAll()
                .requestMatchers("/api/abbreviations","/api/abbreviations/**","/api/cleanups","/api/cleanups/**","/siglas.*","/limpeza.*").hasRole("ADMIN")
                .anyRequest().authenticated())
            .exceptionHandling(errors->errors.authenticationEntryPoint((request,response,e)->{
                if(request.getRequestURI().startsWith("/api/") || request.getHeader("Accept")!=null && request.getHeader("Accept").contains("application/json")) {
                    response.setStatus(401);response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"error\":\"Entre com seu e-mail para continuar.\"}");
                } else response.sendRedirect("/login.html");
            }).accessDeniedHandler((request,response,e)->{
                response.setStatus(403);response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"error\":\"Acesso não permitido para esta conta ou sessão expirada.\"}");
            }))
            .logout(logout->logout.logoutUrl("/api/auth/logout").invalidateHttpSession(true).clearAuthentication(true)
                .deleteCookies("JSESSIONID").logoutSuccessHandler((request,response,authentication)->response.setStatus(204)));
        return http.build();
    }
}
