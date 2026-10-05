package com.ecommerce.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.ecommerce.gateway.security.ProblemResponseWriter;

import reactor.core.publisher.Mono;

/**
 * The built-in RequestRateLimiter answers 429 with an empty body. This decorator turns that answer into the
 * platform error contract ({@code code=RATE_LIMITED}) and adds {@code Retry-After}.
 */
@Component
public class RateLimitedResponseFilter implements GlobalFilter, Ordered {

    private static final String RETRY_AFTER_SECONDS = "1";

    private final ProblemResponseWriter problems;

    public RateLimitedResponseFilter(ProblemResponseWriter problems) {
        this.problems = problems;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpResponseDecorator response = new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override
            public Mono<Void> setComplete() {
                if (HttpStatus.TOO_MANY_REQUESTS.equals(getStatusCode())) {
                    getHeaders().set(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
                    return problems.write(exchange, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                            "Too many requests, retry after " + RETRY_AFTER_SECONDS + " second(s)");
                }
                return super.setComplete();
            }
        };
        return chain.filter(exchange.mutate().response(response).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
