package com.ecommerce.gateway;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;

import com.sun.net.httpserver.HttpServer;

/** In-process downstream service: answers 200 and echoes the identity headers the gateway forwarded. */
final class DownstreamStub {

    static final String ECHO_PREFIX = "Echo-";

    private final HttpServer server;

    private DownstreamStub(HttpServer server) {
        this.server = server;
    }

    static DownstreamStub start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                for (String header : new String[] {"X-User-Id", "X-User-Roles", "X-Tenant-Id"}) {
                    String value = exchange.getRequestHeaders().getFirst(header);
                    if (value != null) {
                        exchange.getResponseHeaders().set(ECHO_PREFIX + header, value);
                    }
                }
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.start();
            return new DownstreamStub(server);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    void stop() {
        server.stop(0);
    }
}
