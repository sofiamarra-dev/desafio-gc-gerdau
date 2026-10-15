package com.example.demo.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

@RestControllerAdvice
public class ErrorHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> tooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("error", "O arquivo deve ter no máximo 15 MB."));
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<Map<String, String>> unavailable(RestClientException e) {
        if (e instanceof RestClientResponseException responseException) {
            String remoteBody = responseException.getResponseBodyAsString();
            String message = remoteBody.isBlank()
                    ? "Serviço Python rejeitou a requisição (HTTP " + responseException.getStatusCode().value() + ")."
                    : "Serviço Python rejeitou a requisição (HTTP " + responseException.getStatusCode().value() + "): " + remoteBody;
            return ResponseEntity.status(responseException.getStatusCode()).body(Map.of("error", message));
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "Serviço Python indisponível ou retornou erro. Verifique http://localhost:8001/docs."));
    }

}
