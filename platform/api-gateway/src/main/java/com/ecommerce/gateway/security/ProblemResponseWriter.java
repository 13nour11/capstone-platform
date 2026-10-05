package com.ecommerce.gateway.security;

import java.net.URI;
import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Mono;

/** Writes the platform's RFC 7807 error contract for responses the gateway produces itself (401, 403, 429). */
@Component
public class ProblemResponseWriter {

    private final ObjectMapper objectMapper;

    public ProblemResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setInstance(URI.create(exchange.getRequest().getPath().value()));
        problem.setProperty("code", code);
        problem.setProperty("timestamp", Instant.now().toString());

        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        try {
            byte[] body = objectMapper.writeValueAsBytes(problem);
            return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
    }
}
