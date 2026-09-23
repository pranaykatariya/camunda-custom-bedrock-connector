package com.anthrobyte.camunda.aiagent.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.anthrobyte.camunda.aiagent.auth.GatewayCredentials;
import com.anthrobyte.camunda.aiagent.auth.OrganizationAuthenticationProvider;
import com.anthrobyte.camunda.aiagent.support.FakeHttpServer;
import com.anthrobyte.camunda.aiagent.support.FakeHttpServer.Response;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.apache.ApacheHttpClient;

@ExtendWith(OutputCaptureExtension.class)
class AuthenticatingSdkHttpClientTest {

  private static final String PATH = "/bedrock/model/m/converse";
  private static final String BODY = "{\"messages\":[]}";

  private final FakeHttpServer gateway = new FakeHttpServer().on(PATH, Response.json(200, "{}"));
  private final SdkHttpClient apache = ApacheHttpClient.builder().build();

  @AfterEach
  void tearDown() {
    apache.close();
    gateway.close();
  }

  /** Hands out token-1, token-2, ... and records invalidations. */
  private static final class RefreshingProvider implements OrganizationAuthenticationProvider {
    final AtomicInteger issued = new AtomicInteger();
    final List<GatewayCredentials> invalidated = new ArrayList<>();
    volatile GatewayCredentials current;

    @Override
    public synchronized GatewayCredentials getCredentials() {
      if (current == null) {
        final Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("x-bam-token", "token-" + issued.incrementAndGet());
        current = new GatewayCredentials(headers);
      }
      return current;
    }

    @Override
    public synchronized void invalidate(GatewayCredentials rejected) {
      invalidated.add(rejected);
      if (current == rejected) {
        current = null;
      }
    }

    @Override
    public boolean supportsRefresh() {
      return true;
    }
  }

  private AuthenticatingSdkHttpClient client(OrganizationAuthenticationProvider provider, boolean retry) {
    return new AuthenticatingSdkHttpClient(apache, provider, retry);
  }

  private HttpExecuteRequest request(String url) {
    final SdkHttpRequest http =
        SdkHttpRequest.builder()
            .method(SdkHttpMethod.POST)
            .uri(URI.create(url))
            .putHeader("Content-Type", "application/json")
            .putHeader("Accept", "*/*")
            .putHeader("Authorization", "AWS4-HMAC-SHA256 Credential=AKIA/20260101/eu-central-1/bedrock/aws4_request")
            .putHeader("X-Amz-Date", "20260101T000000Z")
            .putHeader("X-Amz-Security-Token", "session")
            .putHeader("amz-sdk-invocation-id", "abc")
            .build();
    final byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
    final ContentStreamProvider content = () -> new ByteArrayInputStream(body);
    return HttpExecuteRequest.builder().request(http).contentStreamProvider(content).build();
  }

  private static int call(SdkHttpClient client, HttpExecuteRequest request) throws Exception {
    final HttpExecuteResponse response = client.prepareRequest(request).call();
    response.responseBody().ifPresent(b -> { try { b.close(); } catch (Exception ignored) { } });
    return response.httpResponse().statusCode();
  }

  @Test
  void setsOrganizationHeadersReplacesClashesAndDropsAwsSigningHeaders(CapturedOutput output) throws Exception {
    final var provider = new RefreshingProvider();

    assertThat(call(client(provider, true), request(gateway.baseUrl() + PATH + "?x=1"))).isEqualTo(200);

    final var recorded = gateway.requests(PATH).getFirst();
    assertThat(recorded.header("x-bam-token")).isEqualTo("token-1");
    assertThat(recorded.headerValues("Accept")).containsExactly("application/json");
    assertThat(recorded.header("Authorization")).isNull();
    assertThat(recorded.header("X-Amz-Date")).isNull();
    assertThat(recorded.header("X-Amz-Security-Token")).isNull();
    // everything else unchanged
    assertThat(recorded.method()).isEqualTo("POST");
    assertThat(recorded.uri().getRawQuery()).isEqualTo("x=1");
    assertThat(recorded.header("amz-sdk-invocation-id")).isEqualTo("abc");
    assertThat(recorded.header("Content-Type")).isEqualTo("application/json");
    assertThat(recorded.body()).isEqualTo(BODY);
    assertThat(output.getAll()).doesNotContain("token-1");
  }

  @Test
  void organizationHeaderNamedAuthorizationIsKept() throws Exception {
    final OrganizationAuthenticationProvider provider = () -> GatewayCredentials.of("Authorization", "Bearer org");

    call(client(provider, true), request(gateway.baseUrl() + PATH));

    assertThat(gateway.requests(PATH).getFirst().headerValues("Authorization")).containsExactly("Bearer org");
  }

  @Test
  void hostHeaderCanBeOverridden() throws Exception {
    final OrganizationAuthenticationProvider provider =
        () -> new GatewayCredentials(Map.of("x-bam-token", "t", "Host", "gateway.internal:8443"));

    call(client(provider, true), request(gateway.baseUrl() + PATH));

    assertThat(gateway.requests(PATH).getFirst().headerValues("Host")).containsExactly("gateway.internal:8443");
  }

  @Test
  void everyTargetGetsTheOrganizationHeaders() throws Exception {
    // There is no allow list: whatever endpoint the element configured is the gateway.
    final String otherPath = "/somewhere-else/model/m/converse";
    gateway.on(otherPath, Response.json(200, "{}"));

    assertThat(call(client(new RefreshingProvider(), true), request(gateway.baseUrl() + otherPath)))
        .isEqualTo(200);

    assertThat(gateway.requests(otherPath).getFirst().header("x-bam-token")).isEqualTo("token-1");
  }

  @Test
  void unauthorizedIsRetriedOnceWithFreshCredentialsAndTheSameBody() throws Exception {
    gateway.on(PATH, (req, n) -> "token-1".equals(req.header("x-bam-token")) ? Response.json(401, "{}") : Response.json(200, "{}"));
    final var provider = new RefreshingProvider();

    assertThat(call(client(provider, true), request(gateway.baseUrl() + PATH))).isEqualTo(200);

    assertThat(gateway.requests(PATH)).extracting(r -> r.header("x-bam-token")).containsExactly("token-1", "token-2");
    assertThat(gateway.requests(PATH)).extracting(FakeHttpServer.RecordedRequest::body).containsOnly(BODY);
    assertThat(provider.invalidated).hasSize(1);
  }

  @Test
  void secondUnauthorizedIsReturnedUnchangedAndInvalidated() throws Exception {
    gateway.on(PATH, Response.json(401, "{}"));
    final var provider = new RefreshingProvider();

    assertThat(call(client(provider, true), request(gateway.baseUrl() + PATH))).isEqualTo(401);

    assertThat(gateway.callCount(PATH)).isEqualTo(2);
    assertThat(provider.invalidated).hasSize(2);
  }

  @Test
  void noRetryWhenDisabledOrProviderCannotRefresh() throws Exception {
    gateway.on(PATH, Response.json(401, "{}"));

    assertThat(call(client(new RefreshingProvider(), false), request(gateway.baseUrl() + PATH))).isEqualTo(401);
    assertThat(call(client(() -> GatewayCredentials.of("x-bam-token", "static"), true), request(gateway.baseUrl() + PATH)))
        .isEqualTo(401);

    assertThat(gateway.callCount(PATH)).isEqualTo(2);
  }

  @Test
  void otherStatusesAreReturnedUnchangedWithoutRetry() throws Exception {
    gateway.on(PATH, Response.json(403, "{}"));
    assertThat(call(client(new RefreshingProvider(), true), request(gateway.baseUrl() + PATH))).isEqualTo(403);
    assertThat(gateway.callCount(PATH)).isEqualTo(1);
  }

  @Test
  void closeClosesTheDelegate() {
    final SdkHttpClient delegate = mock(SdkHttpClient.class);
    new AuthenticatingSdkHttpClient(delegate, new RefreshingProvider(), true).close();
    verify(delegate).close();
  }
}
