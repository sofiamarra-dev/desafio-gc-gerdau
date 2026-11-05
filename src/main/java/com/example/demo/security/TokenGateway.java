package com.example.demo.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.*;
import tools.jackson.databind.*;
import java.util.Map;

@Service
public class TokenGateway {
    public record Reply(int status,JsonNode body) {}
    private final RestTemplate rest;
    private final String url;
    private final ObjectMapper json;

    public TokenGateway(@Value("${auth.token.url:http://127.0.0.1:5000}") String url,ObjectMapper json) {
        var factory = new SimpleClientHttpRequestFactory();factory.setConnectTimeout(10000);factory.setReadTimeout(20000);
        this.rest=new RestTemplate(factory);
        this.url=url.replaceAll("/+$","");
        this.json=json;
    }

    public Reply send(String email) { return post("/enviar",Map.of("email",email)); }

    public Reply verify(String email,String code) { return post("/verificar",Map.of("email",email,"codigo",code)); }

    private Reply post(String path,Map<String,String> fields) {
        try {

            var headers = new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
            var response = rest.postForEntity(url+path,new HttpEntity<>(json.writeValueAsString(fields),headers),String.class);

            return reply(response.getStatusCode().value(),response.getBody());

        } catch (RestClientResponseException e) {
            return reply(e.getStatusCode().value(),e.getResponseBodyAsString());
        } catch (RestClientException e) { return unavailable(); }
    }
    private Reply reply(int status,String body) {
        try {
            var parsed = json.readTree(body);
            return parsed != null && parsed.isObject() ? new Reply(status,parsed):unavailable();
        } catch (RuntimeException invalid) { return unavailable(); }
    }
    private Reply unavailable() {
        return new Reply(502,json.valueToTree(Map.of("ok",false,"msg","O serviço de envio de códigos está indisponível. Tente novamente.")));
    }
}
