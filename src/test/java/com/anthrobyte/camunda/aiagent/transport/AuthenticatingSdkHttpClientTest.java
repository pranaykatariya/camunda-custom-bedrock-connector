package com.anthrobyte.camunda.aiagent.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anthrobyte.camunda.aiagent.auth.BamToken;
import com.anthrobyte.camunda.aiagent.auth.BamTokenCache;
import com.anthrobyte.camunda.aiagent.auth.BamTokenClient;
import com.anthrobyte.camunda.aiagent.support.FakeHttpServer;
import com.anthrobyte.camunda.aiagent.support.FakeHttpServer.Response;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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

  /** Tokens issued by the mocked BAM client: token-1, token-2, ... */
  private final AtomicInteger issued = new AtomicInteger();

  private BamTokenCache cache(String headerName, String template, Map<String, String> staticHeaders) {
    final BamTokenClient bam = mock(BamTokenClient.class);
    when(bam.requestToken())
        .thenAnswer(
            invocation ->
                new BamToken("token-" + issued.incrementAndGet(), Instant.now().plus(Duration.ofHours(1))));
    return new BamTokenCache(
        bam, headerName, template, staticHeaders, Duration.ofSeconds(60), Duration.ofSeconds(5), Clock.systemUTC());
  }

  private BamTokenCache cache() {
    return cache("x-bam-token", "{token}", Map.of("Accept", "application/json"));
  }

  private AuthenticatingSdkHttpClient client(BamTokenCache cache, boolean retry) {
    return new AuthenticatingSdkHttpClient(apache, cache, retry);
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
    assertThat(call(client(cache(), true), request(gateway.baseUrl() + PATH + "?x=1"))).isEqualTo(200);

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
    call(client(cache("Authorization", "Bearer {token}", Map.of()), true), request(gateway.baseUrl() + PATH));

    assertThat(gateway.requests(PATH).getFirst().headerValues("Authorization")).containsExactly("Bearer token-1");
  }

  @Test
  void hostHeaderCanBeOverridden() throws Exception {
    call(
        client(cache("x-bam-token", "{token}", Map.of("Host", "gateway.internal:8443")), true),
        request(gateway.baseUrl() + PATH));

    assertThat(gateway.requests(PATH).getFirst().headerValues("Host")).containsExactly("gateway.internal:8443");
  }

  @Test
  void everyTargetGetsTheOrganizationHeaders() throws Exception {
    // There is no allow list: whatever endpoint the element configured is the gateway.
    final String otherPath = "/somewhere-else/model/m/converse";
    gateway.on(otherPath, Response.json(200, "{}"));

    assertThat(call(client(cache(), true), request(gateway.baseUrl() + otherPath)))
        .isEqualTo(200);

    assertThat(gateway.requests(otherPath).getFirst().header("x-bam-token")).isEqualTo("token-1");
  }

  @Test
  void unauthorizedIsRetriedOnceWithFreshCredentialsAndTheSameBody() throws Exception {
    gateway.on(PATH, (req, n) -> "token-1".equals(req.header("x-bam-token")) ? Response.json(401, "{}") : Response.json(200, "{}"));
    assertThat(call(client(cache(), true), request(gateway.baseUrl() + PATH))).isEqualTo(200);

    assertThat(gateway.requests(PATH)).extracting(r -> r.header("x-bam-token")).containsExactly("token-1", "token-2");
    assertThat(gateway.requests(PATH)).extracting(FakeHttpServer.RecordedRequest::body).containsOnly(BODY);
    assertThat(issued).hasValue(2);
  }

  @Test
  void secondUnauthorizedIsReturnedUnchangedAndInvalidated() throws Exception {
    gateway.on(PATH, Response.json(401, "{}"));
    final var cache = cache();

    assertThat(call(client(cache, true), request(gateway.baseUrl() + PATH))).isEqualTo(401);

    assertThat(gateway.callCount(PATH)).isEqualTo(2);
    // token-2 was invalidated too: the next request gets a fresh token
    assertThat(cache.getCredentials().headers()).containsEntry("x-bam-token", "token-3");
  }

  @Test
  void noRetryWhenDisabledButTheTokenIsStillInvalidated() throws Exception {
    gateway.on(PATH, Response.json(401, "{}"));
    final var cache = cache();

    assertThat(call(client(cache, false), request(gateway.baseUrl() + PATH))).isEqualTo(401);

    assertThat(gateway.callCount(PATH)).isEqualTo(1);
    assertThat(cache.getCredentials().headers()).containsEntry("x-bam-token", "token-2");
  }

  @Test
  void otherStatusesAreReturnedUnchangedWithoutRetry() throws Exception {
    gateway.on(PATH, Response.json(403, "{}"));
    assertThat(call(client(cache(), true), request(gateway.baseUrl() + PATH))).isEqualTo(403);
    assertThat(gateway.callCount(PATH)).isEqualTo(1);
  }

  @Test
  void closeClosesTheDelegate() {
    final SdkHttpClient delegate = mock(SdkHttpClient.class);
    new AuthenticatingSdkHttpClient(delegate, cache(), true).close();
    verify(delegate).close();
  }
}
