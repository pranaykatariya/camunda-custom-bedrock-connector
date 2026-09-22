package com.anthrobyte.camunda.aiagent.transport;

import static com.anthrobyte.camunda.aiagent.auth.AuthenticationFailureReason.ENDPOINT_NOT_PERMITTED;

import com.anthrobyte.camunda.aiagent.auth.GatewayCredentials;
import com.anthrobyte.camunda.aiagent.auth.OrganizationAuthenticationException;
import com.anthrobyte.camunda.aiagent.auth.OrganizationAuthenticationProvider;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.SdkHttpResponse;

/**
 * An AWS SDK {@link SdkHttpClient} that adds the organization headers to every HTTP attempt and
 * leaves the request otherwise untouched.
 *
 * <p>It decorates the Apache client that {@code OrganizationBedrockChatModelBuilder} configures
 * exactly like Camunda does (proxy, connection and socket timeouts). The AWS SDK calls {@link
 * #prepareRequest(HttpExecuteRequest)} once per attempt, <i>after</i> the (disabled) signing step,
 * so SDK retries also get fresh credentials, and no signer can overwrite them.
 *
 * <p>Behaviour:
 *
 * <ul>
 *   <li><b>Fail closed.</b> Credentials are only attached when the target URL matches the gateway
 *       allow-list. Any other URL throws without sending anything. If the credentials cannot be
 *       obtained, nothing is sent.
 *   <li><b>Header mode, never SigV4.</b> Headers with the same name as a credential header are
 *       replaced, and AWS signing headers ({@code Authorization}, {@code X-Amz-Date}, {@code
 *       X-Amz-Security-Token}, {@code X-Amz-Content-Sha256}) are removed unless the organization
 *       itself sends one of them.
 *   <li><b>One retry on 401.</b> The rejected credentials are always invalidated. If the provider
 *       can refresh and retries are enabled, the request is sent once more with fresh ones. The
 *       request body is re-read from the SDK's repeatable content provider.
 *   <li><b>Statuses unchanged.</b> Every response, including a final 401/403, is returned to the
 *       SDK as it is. The chat model turns 401/403 into a sanitized exception.
 *   <li><b>No secrets in logs.</b> Only the gateway host, path, status codes and header
 *       <i>names</i> are logged.
 * </ul>
 */
public final class AuthenticatingSdkHttpClient implements SdkHttpClient {

  private static final Logger LOG = LoggerFactory.getLogger(AuthenticatingSdkHttpClient.class);

  static final Set<String> AWS_SIGNING_HEADERS =
      Set.of("authorization", "x-amz-date", "x-amz-security-token", "x-amz-content-sha256");

  private final SdkHttpClient delegate;
  private final OrganizationAuthenticationProvider authenticationProvider;
  private final GatewayEndpointMatcher endpointMatcher;
  private final boolean retryOnUnauthorized;

  public AuthenticatingSdkHttpClient(
      SdkHttpClient delegate,
      OrganizationAuthenticationProvider authenticationProvider,
      GatewayEndpointMatcher endpointMatcher,
      boolean retryOnUnauthorized) {
    this.delegate = delegate;
    this.authenticationProvider = authenticationProvider;
    this.endpointMatcher = endpointMatcher;
    this.retryOnUnauthorized = retryOnUnauthorized;
  }

  @Override
  public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
    final SdkHttpRequest httpRequest = request.httpRequest();
    if (!endpointMatcher.matches(httpRequest.getUri())) {
      LOG.atError()
          .addKeyValue("targetHost", httpRequest.host())
          .log("Refusing to send organization credentials to a non-gateway endpoint");
      throw new OrganizationAuthenticationException(ENDPOINT_NOT_PERMITTED);
    }
    return new AuthenticatedCall(request);
  }

  /** One SDK attempt: up to two HTTP exchanges (original + one retry after 401). */
  private final class AuthenticatedCall implements ExecutableHttpRequest {

    private final HttpExecuteRequest request;
    private volatile ExecutableHttpRequest inFlight;
    private volatile boolean aborted;

    AuthenticatedCall(HttpExecuteRequest request) {
      this.request = request;
    }

    @Override
    public HttpExecuteResponse call() throws IOException {
      final GatewayCredentials credentials = authenticationProvider.getCredentials();
      HttpExecuteResponse response = send(credentials, 1);
      if (response.httpResponse().statusCode() != 401) {
        return response;
      }

      authenticationProvider.invalidate(credentials);
      if (!retryOnUnauthorized || !authenticationProvider.supportsRefresh() || aborted) {
        LOG.atDebug()
            .addKeyValue("gatewayHost", request.httpRequest().host())
            .addKeyValue("retryOnUnauthorized", retryOnUnauthorized)
            .addKeyValue("providerSupportsRefresh", authenticationProvider.supportsRefresh())
            .log("Bedrock gateway returned HTTP 401; not retrying");
        return response;
      }

      discard(response);
      LOG.atInfo()
          .addKeyValue("gatewayHost", request.httpRequest().host())
          .log("Bedrock gateway returned HTTP 401; refreshing organization credentials and retrying once");
      final GatewayCredentials refreshed = authenticationProvider.getCredentials();
      response = send(refreshed, 2);
      if (response.httpResponse().statusCode() == 401) {
        authenticationProvider.invalidate(refreshed);
        LOG.atWarn()
            .addKeyValue("gatewayHost", request.httpRequest().host())
            .log("Bedrock gateway rejected freshly obtained organization credentials with HTTP 401 again");
      } else {
        LOG.atInfo()
            .addKeyValue("gatewayHost", request.httpRequest().host())
            .addKeyValue("httpStatus", response.httpResponse().statusCode())
            .log("Retry with refreshed organization credentials completed");
      }
      return response;
    }

    private HttpExecuteResponse send(GatewayCredentials credentials, int attempt) throws IOException {
      final SdkHttpRequest httpRequest = request.httpRequest();
      LOG.atDebug()
          .addKeyValue("gatewayHost", httpRequest.host())
          .addKeyValue("method", httpRequest.method())
          .addKeyValue("path", httpRequest.encodedPath())
          .addKeyValue("authHeaders", credentials.headers().keySet())
          .addKeyValue("attempt", attempt)
          .log("Calling organization Bedrock gateway");
      final long started = System.nanoTime();
      try {
        final ExecutableHttpRequest executable = delegate.prepareRequest(authorize(request, credentials));
        inFlight = executable;
        final HttpExecuteResponse response = executable.call();
        logResponse(httpRequest, response.httpResponse(), elapsedMillis(started), attempt);
        return response;
      } catch (IOException | RuntimeException e) {
        // Transport exceptions describe host/port/timeouts only: no credentials, no payload.
        LOG.atWarn()
            .addKeyValue("gatewayHost", httpRequest.host())
            .addKeyValue("error", e.getClass().getSimpleName())
            .addKeyValue("durationMs", elapsedMillis(started))
            .addKeyValue("attempt", attempt)
            .log("Call to organization Bedrock gateway failed");
        throw e;
      }
    }

    @Override
    public void abort() {
      aborted = true;
      LOG.atInfo()
          .addKeyValue("gatewayHost", request.httpRequest().host())
          .addKeyValue("inFlight", inFlight != null)
          .log("Organization Bedrock gateway call aborted by the AWS SDK (API call timeout or cancellation)");
      final ExecutableHttpRequest current = inFlight;
      if (current != null) {
        current.abort();
      }
    }
  }

  /**
   * Returns the request with the credential headers set, same-named headers replaced and AWS
   * signing headers removed. Method, URI, body and all other headers are unchanged.
   */
  static HttpExecuteRequest authorize(HttpExecuteRequest request, GatewayCredentials credentials) {
    final SdkHttpRequest httpRequest = request.httpRequest();
    final SdkHttpRequest.Builder builder = httpRequest.toBuilder();
    final List<String> removed =
        httpRequest.headers().keySet().stream()
            .filter(
                name ->
                    credentials.containsHeader(name)
                        || AWS_SIGNING_HEADERS.contains(name.toLowerCase(Locale.ROOT)))
            .toList();
    removed.forEach(builder::removeHeader);
    credentials.headers().forEach(builder::putHeader);
    if (!removed.isEmpty()) {
      LOG.atDebug()
          .addKeyValue("gatewayHost", httpRequest.host())
          .addKeyValue("replacedHeaders", removed)
          .log("Request headers replaced by organization credential headers");
    }

    final HttpExecuteRequest.Builder authorized = HttpExecuteRequest.builder().request(builder.build());
    request.contentStreamProvider().ifPresent(authorized::contentStreamProvider);
    request.metricCollector().ifPresent(authorized::metricCollector);
    return authorized.build();
  }

  /**
   * One access-log style line per HTTP exchange. 401/403 stay at DEBUG here: the retry path or the
   * chat model logs them with more context.
   */
  private static void logResponse(
      SdkHttpRequest request, SdkHttpResponse response, long durationMs, int attempt) {
    final int status = response.statusCode();
    final var event =
        status == 429 || status >= 500
            ? LOG.atWarn()
            : status == 401 || status == 403 ? LOG.atDebug() : LOG.atInfo();
    event
        .addKeyValue("gatewayHost", request.host())
        .addKeyValue("method", request.method())
        .addKeyValue("path", request.encodedPath())
        .addKeyValue("httpStatus", status)
        .addKeyValue("durationMs", durationMs)
        .addKeyValue("attempt", attempt)
        // Correlates with gateway / AWS-side logs; an opaque id, not a credential.
        .addKeyValue("requestId", requestId(response))
        .log(
            status >= 200 && status < 300
                ? "Organization Bedrock gateway call succeeded"
                : "Organization Bedrock gateway returned an error status");
  }

  private static String requestId(SdkHttpResponse response) {
    return response
        .firstMatchingHeader("x-amzn-RequestId")
        .or(() -> response.firstMatchingHeader("x-amz-request-id"))
        .or(() -> response.firstMatchingHeader("x-request-id"))
        .orElse(null);
  }

  /** Releases the connection of a response whose body we will not hand to the SDK. */
  private static void discard(HttpExecuteResponse response) {
    response
        .responseBody()
        .ifPresent(
            body -> {
              try {
                body.close();
              } catch (IOException ignored) {
                // best effort
              }
            });
  }

  private static long elapsedMillis(long startedNanos) {
    return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
  }

  @Override
  public String clientName() {
    return delegate.clientName();
  }

  @Override
  public void close() {
    delegate.close();
    LOG.debug("Authenticating Bedrock HTTP client closed");
  }

  @Override
  public String toString() {
    return "AuthenticatingSdkHttpClient{" + endpointMatcher + "}";
  }
}
