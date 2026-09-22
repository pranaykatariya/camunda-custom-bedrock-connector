package com.anthrobyte.camunda.aiagent.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal programmable HTTP server (JDK built-in, no extra dependencies) that records every
 * request. It stands in for the organization token endpoint, the organization Bedrock gateway and,
 * in pass-through tests, an OpenAI-compatible backend.
 */
public final class FakeHttpServer implements AutoCloseable {

  public record RecordedRequest(
      String method, URI uri, Map<String, List<String>> headers, String body) {
    /** Case-insensitive single-value header lookup. */
    public String header(String name) {
      final List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
      return values == null || values.isEmpty() ? null : values.getFirst();
    }

    public List<String> headerValues(String name) {
      return headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
    }

    public String path() {
      return uri.getPath();
    }
  }

  public record Response(int status, String body, Map<String, String> headers, Duration delay) {
    public static Response json(int status, String body) {
      return new Response(status, body, Map.of("Content-Type", "application/json"), Duration.ZERO);
    }

    public Response delayedBy(Duration delay) {
      return new Response(status, body, headers, delay);
    }
  }

  @FunctionalInterface
  public interface Responder {
    /**
     * @param callNumber 1-based number of this call to the path
     */
    Response respond(RecordedRequest request, int callNumber);
  }

  private final HttpServer server;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final Map<String, Responder> responders = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> callCounters = new ConcurrentHashMap<>();
  private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();

  public FakeHttpServer() {
    try {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    server.setExecutor(executor);
    server.createContext("/", this::handle);
    server.start();
  }

  public FakeHttpServer on(String path, Responder responder) {
    responders.put(path, responder);
    return this;
  }

  public FakeHttpServer on(String path, Response response) {
    return on(path, (request, n) -> response);
  }

  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  public URI uri(String path) {
    return URI.create(baseUrl() + path);
  }

  public List<RecordedRequest> requests() {
    return List.copyOf(requests);
  }

  public List<RecordedRequest> requests(String path) {
    return requests.stream().filter(r -> r.path().equals(path)).toList();
  }

  public int callCount(String path) {
    return requests(path).size();
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      final String body =
          new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      final Map<String, List<String>> headers = new TreeMap<>();
      exchange
          .getRequestHeaders()
          .forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), List.copyOf(v)));
      final var recorded =
          new RecordedRequest(
              exchange.getRequestMethod(), exchange.getRequestURI(), headers, body);
      requests.add(recorded);

      final String path = exchange.getRequestURI().getPath();
      final Responder responder = responders.get(path);
      if (responder == null) {
        exchange.sendResponseHeaders(404, -1);
        return;
      }
      final int n = callCounters.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
      final Response response = responder.respond(recorded, n);
      if (!response.delay().isZero()) {
        try {
          Thread.sleep(response.delay());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
      response.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
      final byte[] bytes =
          response.body() == null ? new byte[0] : response.body().getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
      if (bytes.length > 0) {
        try (OutputStream out = exchange.getResponseBody()) {
          out.write(bytes);
        }
      }
    } catch (IOException e) {
      // client went away (e.g. timeout tests)
    }
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }
}
