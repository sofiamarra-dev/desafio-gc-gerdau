package com.example.demo.api;

import com.example.demo.security.AdminAccounts;
import com.example.demo.security.TokenGateway;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@RestController
public class AuthenticationController {

    private static final String PENDING_EMAIL_ATTRIBUTE = "TOKEN_PENDING_EMAIL";

    private final TokenGateway tokenGateway;
    private final SecurityContextRepository securityRepository;

    public AuthenticationController(TokenGateway tokenGateway, SecurityContextRepository securityRepository) {
        this.tokenGateway = tokenGateway;
        this.securityRepository = securityRepository;
    }


    public record EmailRequest(String email) {}

    public record CodeRequest(String email, String codigo) {}

    @PostMapping(value = "/enviar", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> sendCode(@RequestBody EmailRequest input, HttpServletRequest request) {

        validateRequestOrigin(request);

        String email = normalizeAndValidateEmail(input.email());

        // recupera a sessão ou cria uma nova.
        HttpSession session = request.getSession(true);

        synchronized (session) {

            // faz uma requisição pra parte em python de "/enviar"
            TokenGateway.Reply reply = tokenGateway.send(email);

            // passa o email que está entrando
            if (wasSuccessful(reply)) {
                session.setAttribute(PENDING_EMAIL_ATTRIBUTE, email);
            }

            return ResponseEntity
                    .status(reply.status())
                    .body(reply.body());
        }
    }

    @PostMapping(value = "/verificar", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> verifyCode(@RequestBody CodeRequest input, HttpServletRequest request, HttpServletResponse response) {

        validateRequestOrigin(request);

        String email = normalizeAndValidateEmail(input.email());
        String code = Objects.toString(input.codigo(), "").strip();

        HttpSession session = request.getSession(false);


        if (session == null) {
            return invalidRequest("Solicite um código neste navegador primeiro.");
        }

        synchronized (session) {
            Object pendingEmail = session.getAttribute(PENDING_EMAIL_ATTRIBUTE);

            boolean matchesPendingEmail = email.equals(pendingEmail);
            boolean hasValidCodeFormat = code.matches("[0-9]{10}");

            if (!matchesPendingEmail || !hasValidCodeFormat) {
                return invalidRequest("Confira o e-mail e o código solicitado neste navegador.");
            }

            TokenGateway.Reply reply = tokenGateway.verify(email, code);

            if (wasSuccessful(reply)) {
                authenticateUser(email, session, request, response);
            }

            return ResponseEntity
                    .status(reply.status())
                    .body(reply.body());
        }
    }

    @GetMapping("/api/auth/status")
    public Map<String, Object> authenticationStatus(HttpServletRequest request) {

        Authentication authentication = SecurityContextHolder
                .getContext()
                .getAuthentication();

        boolean authenticated = authentication != null
                        && authentication.isAuthenticated()
                        && !"anonymousUser".equals(authentication.getPrincipal());

        Map<String, Object> result = new LinkedHashMap<>();

        result.put("authenticated", authenticated);

        result.put("email", authenticated ? authentication.getName() : "");

        result.put("admin", authenticated && AdminAccounts.isAdmin(authentication.getName()));

        Object csrfAttribute = request.getAttribute(CsrfToken.class.getName());

        if (csrfAttribute instanceof CsrfToken csrfToken) {
            result.put("csrfToken", csrfToken.getToken());
            result.put("csrfHeader", csrfToken.getHeaderName());
        }

        return result;
    }

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> handleAuthenticationError(ResponseStatusException exception) {

        String message = Objects.toString(exception.getReason(), "Não foi possível concluir o acesso.");

        return ResponseEntity
                .status(exception.getStatusCode())
                .body(Map.of(
                        "ok", false,
                        "msg", message
                ));
    }

    private String normalizeAndValidateEmail(String receivedEmail) {
        String email = Objects.toString(receivedEmail, "")
                .strip()
                .toLowerCase(Locale.ROOT);

        boolean exceedsMaximumLength = email.length() > 254;

        boolean isValidGmail = email.matches("[a-z0-9._%+-]+@gmail\\.com");

        if (exceedsMaximumLength || !isValidGmail) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Informe um Gmail válido."
            );
        }

        return email;
    }

    private void validateRequestOrigin(HttpServletRequest request) {
        String receivedOrigin = request.getHeader("Origin");

        String expectedOrigin = request.getScheme() + "://" + request.getServerName();

        int port = request.getServerPort();

        if (port != 80 && port != 443) {
            expectedOrigin += ":" + port;
        }

        boolean isCrossSite = "cross-site".equals(request.getHeader("Sec-Fetch-Site"));

        boolean hasDifferentOrigin = receivedOrigin != null && !receivedOrigin.equals(expectedOrigin);

        if (isCrossSite || hasDifferentOrigin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Origem da solicitação não permitida.");
        }
    }

    private boolean wasSuccessful(TokenGateway.Reply reply) {
        return reply.status() == 200
                && reply.body().path("ok").asBoolean(false);
    }

    private void authenticateUser(String email, HttpSession session, HttpServletRequest request, HttpServletResponse response) {


        request.changeSessionId();

        session.removeAttribute(PENDING_EMAIL_ATTRIBUTE);

        // aqui verifica se o email que tá entrando é adm
        String role = AdminAccounts.isAdmin(email) ? "ROLE_ADMIN" : "ROLE_USER";

        List<SimpleGrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority(role)
        );

        Authentication authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        email,
                        null,
                        authorities
                );

        SecurityContext context = SecurityContextHolder.createEmptyContext();

        context.setAuthentication(authentication);

        SecurityContextHolder.setContext(context);

        securityRepository.saveContext(context, request, response);
    }

    private ResponseEntity<?> invalidRequest(String message) {
        return ResponseEntity
                .badRequest()
                .body(Map.of(
                        "ok", false,
                        "msg", message
                ));
    }
}