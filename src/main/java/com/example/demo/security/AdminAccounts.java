package com.example.demo.security;
import java.util.Locale;
import java.util.Set;

public final class AdminAccounts {
    private AdminAccounts() {}
    // Lista explícita solicitada para o protótipo. Nunca confiar em um perfil enviado pelo navegador.
    public static final Set<String> EMAILS=Set.of("danielosterkamp38@gmail.com");
    public static boolean isAdmin(String email) { return EMAILS.contains(email.strip().toLowerCase(Locale.ROOT)); }
}
