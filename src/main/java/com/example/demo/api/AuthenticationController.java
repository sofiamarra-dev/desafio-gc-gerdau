package com.example.demo.api;

import com.example.demo.security.*;
import jakarta.servlet.http.*;
import org.springframework.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
public class AuthenticationController {
    private static final String PENDING="TOKEN_PENDING_EMAIL";
    private final TokenGateway gateway;
    private final SecurityContextRepository repository;
    public AuthenticationController(TokenGateway gateway,SecurityContextRepository repository) { this.gateway=gateway;this.repository=repository; }
    public record Email(String email) {}
    public record Code(String email,String codigo) {}
    private String email(String email) {
        String normalized=Objects.toString(email,"").strip().toLowerCase(Locale.ROOT);
        if (normalized.length()>254 || !normalized.matches("[a-z0-9._%+-]+@gmail\\.com")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Informe um Gmail válido.");
        return normalized;
    }
    private void sameOrigin(HttpServletRequest request) {
        String origin=request.getHeader("Origin");
        String expected=request.getScheme()+"://"+request.getServerName();
        int port=request.getServerPort();if(port!=80 && port!=443) expected+=":"+port;
        if ("cross-site".equals(request.getHeader("Sec-Fetch-Site")) || origin!=null && !origin.equals(expected))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Origem da solicitação não permitida.");
    }
    @PostMapping(value="/enviar",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> send(@RequestBody Email input,HttpServletRequest request) {
        sameOrigin(request);String email=email(input.email());var session=request.getSession(true);
        synchronized(session) {
            var reply=gateway.send(email);
            if(reply.status()==200 && reply.body().path("ok").asBoolean(false)) session.setAttribute(PENDING,email);
            return ResponseEntity.status(reply.status()).body(reply.body());
        }
    }
    @PostMapping(value="/verificar",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> verify(@RequestBody Code input,HttpServletRequest request,HttpServletResponse response) {
        sameOrigin(request);String email=email(input.email());var session=request.getSession(false);
        String code=Objects.toString(input.codigo(),"").strip();
        if(session==null) return ResponseEntity.status(400).body(Map.of("ok",false,"msg","Solicite um código neste navegador primeiro."));
        synchronized(session) {
            if (!email.equals(session.getAttribute(PENDING)) || !code.matches("[0-9]{10}"))
                return ResponseEntity.status(400).body(Map.of("ok",false,"msg","Confira o e-mail e o código solicitado neste navegador."));
            var reply=gateway.verify(email,code);
            if(reply.status()==200 && reply.body().path("ok").asBoolean(false)) {
                request.changeSessionId();session.removeAttribute(PENDING);
                var authorities=List.of(new SimpleGrantedAuthority(AdminAccounts.isAdmin(email)?"ROLE_ADMIN":"ROLE_USER"));
                var context=SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(email,null,authorities));
                SecurityContextHolder.setContext(context);repository.saveContext(context,request,response);
            }
            return ResponseEntity.status(reply.status()).body(reply.body());
        }
    }
    @GetMapping("/api/auth/status") public Map<String,Object> status(HttpServletRequest request) {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated=auth!=null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal());
        var result=new LinkedHashMap<String,Object>();result.put("authenticated",authenticated);
        result.put("email",authenticated?auth.getName():"");result.put("admin",authenticated && AdminAccounts.isAdmin(auth.getName()));
        Object token=request.getAttribute(CsrfToken.class.getName());
        if(token instanceof CsrfToken csrf) { result.put("csrfToken",csrf.getToken());result.put("csrfHeader",csrf.getHeaderName()); }
        return result;
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> failure(ResponseStatusException e) { return ResponseEntity.status(e.getStatusCode()).body(Map.of("ok",false,"msg",Objects.toString(e.getReason(),"Não foi possível concluir o acesso."))); }
    @GetMapping("/api/health") public Map<String,String> health() { return Map.of("status","UP"); }
}
