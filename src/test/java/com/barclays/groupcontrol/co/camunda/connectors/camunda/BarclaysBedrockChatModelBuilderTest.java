package com.barclays.groupcontrol.co.camunda.connectors.camunda;

import static com.barclays.groupcontrol.co.camunda.connectors.support.CamundaFixtures.BEDROCK_MODEL;
import static com.barclays.groupcontrol.co.camunda.connectors.support.CamundaFixtures.BEDROCK_REGION;
import static com.barclays.groupcontrol.co.camunda.connectors.support.CamundaFixtures.DEFAULT_TIMEOUT;
import static com.barclays.groupcontrol.co.camunda.connectors.support.CamundaFixtures.agenticAiHttpProxySupport;
import static com.barclays.groupcontrol.co.camunda.connectors.support.CamundaFixtures.agenticAiProperties;
import static com.barclays.groupcontrol.co.camunda.connectors.support.CamundaFixtures.bedrock;
import static com.barclays.groupcontrol.co.camunda.connectors.support.CamundaFixtures.standardChatModelFactory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.barclays.groupcontrol.co.camunda.connectors.auth.AuthenticationFailureReason;
import com.barclays.groupcontrol.co.camunda.connectors.auth.BamToken;
import com.barclays.groupcontrol.co.camunda.connectors.auth.BamTokenCache;
import com.barclays.groupcontrol.co.camunda.connectors.auth.BamTokenClient;
import com.barclays.groupcontrol.co.camunda.connectors.auth.BarclaysAuthenticationException;
import com.barclays.groupcontrol.co.camunda.connectors.auth.BarclaysAuthenticationUnavailableException;
import com.barclays.groupcontrol.co.camunda.connectors.support.BedrockResponses;
import com.barclays.groupcontrol.co.camunda.connectors.support.FakeHttpServer;
import com.barclays.groupcontrol.co.camunda.connectors.support.FakeHttpServer.RecordedRequest;
import com.barclays.groupcontrol.co.camunda.connectors.support.FakeHttpServer.Response;
import com.barclays.groupcontrol.co.camunda.connectors.support.MutableClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.camunda.connector.agenticai.aiagent.framework.langchain4j.CloseableChatModel;
import io.camunda.connector.agenticai.aiagent.framework.langchain4j.CloseableChatModelDelegate;
import io.camunda.connector.agenticai.aiagent.model.request.provider.BedrockProviderConfiguration.AwsAuthentication.AwsApiKeyAuthentication;
import io.camunda.connector.agenticai.aiagent.model.request.provider.BedrockProviderConfiguration.AwsAuthentication.AwsStaticCredentialsAuthentication;
import io.camunda.connector.agenticai.aiagent.model.request.provider.BedrockProviderConfiguration.BedrockModel.BedrockModelParameters;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

/**
 * Drives the real AWS SDK {@code BedrockRuntimeClient} and LangChain4j {@code BedrockChatModel}
 * built by {@link BarclaysBedrockChatModelBuilder} against a fake Barclays Bedrock gateway.
 */
@Timeout(60)
class BarclaysBedrockChatModelBuilderTest {

  private static final String BASE_PATH = "/bedrock";
  private static final String CONVERSE = BASE_PATH + BedrockResponses.conversePath(BEDROCK_MODEL);
  private static final String HOST_HEADER = "bedrock-gateway.internal.example";

  private final FakeHttpServer gateway = new FakeHttpServer();
  private final MutableClock clock = MutableClock.startingNow();
  private final AtomicInteger issuedTokens = new AtomicInteger();
  private final ObjectMapper json = new ObjectMapper();

  /** BAM stub: jwt-1, jwt-2, ... each valid for 5 minutes. */
  private final Supplier<BamToken> tokenSource =
      () -> new BamToken("jwt-" + issuedTokens.incrementAndGet(), clock.instant().plus(Duration.ofMinutes(5)));

  @AfterEach
  void tearDown() {
    gateway.close();
  }

  private String endpoint() {
    return gateway.baseUrl() + BASE_PATH;
  }

  private BamTokenCache provider(Supplier<BamToken> source) {
    final BamTokenClient bam = mock(BamTokenClient.class);
    when(bam.requestToken()).thenAnswer(invocation -> source.get());
    final Map<String, String> staticHeaders = new LinkedHashMap<>();
    staticHeaders.put("Accept", "application/json");
    staticHeaders.put("Host", HOST_HEADER);
    return new BamTokenCache(
        bam, "x-bam-token", "{token}", staticHeaders, Duration.ofSeconds(60), Duration.ofSeconds(5), clock);
  }

  private BarclaysBedrockChatModelBuilder builder(
      BamTokenCache provider, boolean retryOnUnauthorized) {
    return new BarclaysBedrockChatModelBuilder(
        agenticAiProperties(), agenticAiHttpProxySupport(), provider, retryOnUnauthorized);
  }

  private BarclaysBedrockChatModelBuilder builder() {
    return builder(provider(tokenSource), true);
  }

  private static ChatResponse chat(CloseableChatModel model) {
    return model.chat(ChatRequest.builder().messages(UserMessage.from("Hi")).build());
  }

  private void answerOk() {
    gateway.on(CONVERSE, Response.json(200, BedrockResponses.text("ok")));
  }

  // --- 1. what goes on the wire ----------------------------------------------------------------

  @Test
  void sendsBarclaysHeadersAndNoAwsSignature() {
    answerOk();
    // AWS keys configured on the element must be ignored, never used to sign.
    final var config =
        bedrock(
            endpoint(),
            new AwsStaticCredentialsAuthentication("AKIAELEMENTKEY", "element-secret-key"),
            null,
            null);

    try (var model = builder().create(config)) {
      assertThat(chat(model).aiMessage().text()).isEqualTo("ok");
    }

    final RecordedRequest request = gateway.requests(CONVERSE).getFirst();
    assertThat(request.method()).isEqualTo("POST");
    // the model id is URL-encoded on the wire (':' -> '%3A')
    assertThat(request.uri().getRawPath())
        .isEqualTo(BASE_PATH + "/model/anthropic.claude-3-5-sonnet-20240620-v1%3A0/converse");
    assertThat(request.header("x-bam-token")).isEqualTo("jwt-1");
    assertThat(request.headerValues("Accept")).containsExactly("application/json");
    assertThat(request.headerValues("Host")).containsExactly(HOST_HEADER);
    assertThat(request.header("Authorization")).isNull();
    assertThat(request.header("X-Amz-Date")).isNull();
    assertThat(request.header("X-Amz-Security-Token")).isNull();
    assertThat(request.header("X-Amz-Content-Sha256")).isNull();
    assertThat(request.toString()).doesNotContain("AKIAELEMENTKEY").doesNotContain("element-secret-key");
  }

  @Test
  void elementApiKeyIsNeverForwarded() {
    answerOk();
    final var config = bedrock(endpoint(), new AwsApiKeyAuthentication("element-api-key"), null, null);

    try (var model = builder().create(config)) {
      chat(model);
    }

    final RecordedRequest request = gateway.requests(CONVERSE).getFirst();
    assertThat(request.header("Authorization")).isNull();
    assertThat(request.headers().toString()).doesNotContain("element-api-key");
    assertThat(request.header("x-bam-token")).isEqualTo("jwt-1");
  }

  // --- 2. token lifecycle ----------------------------------------------------------------------

  @Test
  void cachedTokenIsReusedAndAFreshOneIsUsedAfterExpiry() {
    answerOk();
    try (var model = builder().create(bedrock(endpoint()))) {
      chat(model);
      chat(model);
      clock.advance(Duration.ofMinutes(5)); // past expiry (and the 60s refresh skew)
      chat(model);
    }

    assertThat(gateway.requests(CONVERSE))
        .extracting(r -> r.header("x-bam-token"))
        .containsExactly("jwt-1", "jwt-1", "jwt-2");
    assertThat(issuedTokens).hasValue(2);
  }

  // --- 3. 401 handling -------------------------------------------------------------------------

  @Test
  void unauthorizedInvalidatesTokenAndRetriesExactlyOnceWithANewOne() {
    gateway.on(
        CONVERSE,
        (req, n) ->
            "jwt-1".equals(req.header("x-bam-token"))
                ? Response.json(401, BedrockResponses.error("token expired"))
                : Response.json(200, BedrockResponses.text("ok")));

    try (var model = builder().create(bedrock(endpoint()))) {
      assertThat(chat(model).aiMessage().text()).isEqualTo("ok");
    }

    assertThat(gateway.requests(CONVERSE))
        .extracting(r -> r.header("x-bam-token"))
        .containsExactly("jwt-1", "jwt-2");
    // the retried request carries the same body
    assertThat(gateway.requests(CONVERSE).get(1).body()).isEqualTo(gateway.requests(CONVERSE).get(0).body());
  }

  @Test
  void persistentUnauthorizedFailsWithSanitizedExceptionAfterOneRetry() {
    gateway.on(CONVERSE, Response.json(401, BedrockResponses.error("jwt rejected: jwt-1")));

    try (var model = builder().create(bedrock(endpoint()))) {
      assertThatThrownBy(() -> chat(model))
          .isInstanceOf(BarclaysAuthenticationException.class)
          .hasMessage(
              "Barclays Bedrock gateway authentication failed: the Bedrock gateway rejected the "
                  + "Barclays credentials (HTTP 401).")
          .hasNoCause();
    }
    // original + one refresh-retry; LangChain4j and the SDK do not retry a 401
    assertThat(gateway.callCount(CONVERSE)).isEqualTo(2);
  }

  @Test
  void withRetryDisabledTheTokenIsStillInvalidatedSoTheNextCallRefreshes() {
    gateway.on(
        CONVERSE,
        (req, n) ->
            "jwt-1".equals(req.header("x-bam-token"))
                ? Response.json(401, "{}")
                : Response.json(200, BedrockResponses.text("ok")));

    try (var model = builder(provider(tokenSource), false).create(bedrock(endpoint()))) {
      assertThatThrownBy(() -> chat(model)).isInstanceOf(BarclaysAuthenticationException.class);
      assertThat(gateway.callCount(CONVERSE)).isEqualTo(1);

      assertThat(chat(model).aiMessage().text()).isEqualTo("ok");
    }
    assertThat(gateway.requests(CONVERSE)).extracting(r -> r.header("x-bam-token")).containsExactly("jwt-1", "jwt-2");
  }

  @Test
  void forbiddenIsNotRetriedAndIsSanitized() {
    gateway.on(CONVERSE, Response.json(403, BedrockResponses.error("denied")));

    try (var model = builder().create(bedrock(endpoint()))) {
      assertThatThrownBy(() -> chat(model))
          .isInstanceOf(BarclaysAuthenticationException.class)
          .extracting(e -> ((BarclaysAuthenticationException) e).reason())
          .isEqualTo(AuthenticationFailureReason.GATEWAY_ACCESS_DENIED);
    }
    assertThat(gateway.callCount(CONVERSE)).isEqualTo(1);
  }

  @Test
  void llmErrorsKeepStandardSemantics() {
    gateway.on(CONVERSE, Response.json(400, BedrockResponses.error("Input is too long for requested model.")));

    try (var model = builder().create(bedrock(endpoint()))) {
      assertThatThrownBy(() -> chat(model))
          .isNotInstanceOf(BarclaysAuthenticationException.class)
          .hasMessageContaining("Input is too long");
    }
    assertThat(gateway.callCount(CONVERSE)).isEqualTo(1);
  }

  // --- 4. fail closed --------------------------------------------------------------------------

  @Test
  void rejectedTokenRequestFailsClosedWithoutCallingTheGateway() {
    answerOk();
    final Supplier<BamToken> rejecting =
        () -> {
          throw new BarclaysAuthenticationException(
              AuthenticationFailureReason.TOKEN_REQUEST_REJECTED, 401, "invalid_client");
        };

    try (var model = builder(provider(rejecting), true).create(bedrock(endpoint()))) {
      assertThatThrownBy(() -> chat(model))
          .isInstanceOf(BarclaysAuthenticationException.class)
          .hasMessage(
              "Barclays Bedrock gateway authentication failed: the token endpoint rejected the "
                  + "client credentials (HTTP 401) [invalid_client].");
    }
    assertThat(gateway.requests()).isEmpty();
  }

  @Test
  void unavailableTokenEndpointFailsClosedWithoutCallingTheGateway() {
    answerOk();
    final Supplier<BamToken> unavailable =
        () -> {
          throw new BarclaysAuthenticationUnavailableException(
              AuthenticationFailureReason.TOKEN_ENDPOINT_UNREACHABLE);
        };

    try (var model = builder(provider(unavailable), true).create(bedrock(endpoint()))) {
      assertThatThrownBy(() -> chat(model))
          .isInstanceOf(BarclaysAuthenticationUnavailableException.class)
          .hasMessageContaining("the token endpoint could not be reached");
    }
    assertThat(gateway.requests()).isEmpty();
  }

  @Test
  void unexpectedTokenSourceErrorIsSanitizedAndFailsClosed() {
    answerOk();
    final Supplier<BamToken> buggy =
        () -> {
          throw new IllegalStateException("signing key sk-live-123 not found");
        };

    try (var model = builder(provider(buggy), true).create(bedrock(endpoint()))) {
      assertThatThrownBy(() -> chat(model))
          .isInstanceOf(BarclaysAuthenticationException.class)
          .hasMessageContaining("the Barclays token source failed")
          .hasMessageNotContaining("sk-live-123")
          .hasNoCause();
    }
    assertThat(gateway.requests()).isEmpty();
  }

  @Test
  void onlyMissingOrUnusableEndpointsAreRejectedBeforeAnyClientIsBuilt() {
    assertThatThrownBy(() -> builder().create(bedrock(null)))
        .isInstanceOf(BarclaysAuthenticationException.class)
        .hasMessageContaining("[custom endpoint is not set]");
    assertThatThrownBy(() -> builder().create(bedrock("   ")))
        .isInstanceOf(BarclaysAuthenticationException.class)
        .hasMessageContaining("[custom endpoint is not set]");
    assertThatThrownBy(() -> builder().create(bedrock("bedrock-gateway.example.com/bedrock")))
        .isInstanceOf(BarclaysAuthenticationException.class)
        .extracting(e -> ((BarclaysAuthenticationException) e).reason())
        .isEqualTo(AuthenticationFailureReason.ENDPOINT_NOT_CONFIGURED);
    assertThat(issuedTokens).hasValue(0);
    assertThat(gateway.requests()).isEmpty();
  }

  @Test
  void anyConfiguredEndpointIsUsedAsTheGateway() {
    // No allow list and no scheme check: a plain-http endpoint on another host is taken as it is.
    final String elsewhere = "http://127.0.0.1:1/somewhere-else";
    try (var model = builder().create(bedrock(elsewhere))) {
      assertThat(clientOf(model).serviceClientConfiguration().endpointOverride())
          .contains(URI.create(elsewhere));
    }
    assertThat(gateway.requests()).isEmpty();
  }

  // --- 6. parity with Camunda's built-in Bedrock client ----------------------------------------

  private static BedrockRuntimeClient clientOf(CloseableChatModel model) {
    return (BedrockRuntimeClient) ((CloseableChatModelDelegate) model).resource();
  }

  @Test
  void regionEndpointAndElementTimeoutAreApplied() {
    final var config =
        bedrock(
            endpoint(),
            null,
            Duration.ofSeconds(45),
            null);
    try (var model = builder().create(config)) {
      final var configuration = clientOf(model).serviceClientConfiguration();
      assertThat(configuration.region()).isEqualTo(Region.of(BEDROCK_REGION));
      assertThat(configuration.endpointOverride()).contains(URI.create(endpoint()));
      assertThat(configuration.overrideConfiguration().apiCallTimeout()).contains(Duration.ofSeconds(45));
    }
  }

  @Test
  void missingOrNonPositiveElementTimeoutFallsBackToTheDefault() {
    for (Duration elementTimeout : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-1)}) {
      try (var model = builder().create(bedrock(endpoint(), null, elementTimeout, null))) {
        assertThat(clientOf(model).serviceClientConfiguration().overrideConfiguration().apiCallTimeout())
            .as("element timeout %s", elementTimeout)
            .contains(DEFAULT_TIMEOUT);
      }
    }
  }

  @Test
  void modelParametersAreSentAsInferenceConfig() throws Exception {
    answerOk();
    final var config = bedrock(endpoint(), null, null, new BedrockModelParameters(256, 0.2, 0.9));

    try (var model = builder().create(config)) {
      chat(model);
    }

    final JsonNode inference = json.readTree(gateway.requests(CONVERSE).getFirst().body()).get("inferenceConfig");
    assertThat(inference.get("maxTokens").asInt()).isEqualTo(256);
    assertThat(inference.get("temperature").asDouble()).isCloseTo(0.2, within(1e-6));
    assertThat(inference.get("topP").asDouble()).isCloseTo(0.9, within(1e-6));
  }

  @Test
  void onlyPresentModelParametersAreSent() throws Exception {
    answerOk();
    final var config = bedrock(endpoint(), null, null, new BedrockModelParameters(null, 0.5, null));

    try (var model = builder().create(config)) {
      chat(model);
    }

    final JsonNode inference = json.readTree(gateway.requests(CONVERSE).getFirst().body()).get("inferenceConfig");
    assertThat(inference.get("temperature").asDouble()).isCloseTo(0.5, within(1e-6));
    assertThat(inference.has("maxTokens")).isFalse();
    assertThat(inference.has("topP")).isFalse();
  }

  @Test
  void requestBodyIsIdenticalToStandardConnector() {
    answerOk();
    // Camunda's own factory, API-key mode (no signing), against the same endpoint
    final var standardConfig =
        bedrock(endpoint(), new AwsApiKeyAuthentication("k"), null, new BedrockModelParameters(512, 0.3, 0.8));
    try (var standard = standardChatModelFactory().createChatModel(standardConfig)) {
      chat(standard);
    }
    final var barclaysConfig = bedrock(endpoint(), null, null, new BedrockModelParameters(512, 0.3, 0.8));
    try (var Barclays = builder().create(barclaysConfig)) {
      chat(Barclays);
    }

    final var requests = gateway.requests(CONVERSE);
    assertThat(requests).hasSize(2);
    assertThat(requests.get(1).uri()).isEqualTo(requests.get(0).uri());
    assertThat(requests.get(1).body()).isEqualTo(requests.get(0).body());
    assertThat(requests.get(0).header("Authorization")).isEqualTo("Bearer k");
    assertThat(requests.get(1).header("Authorization")).isNull();
  }

  // --- 7. lifecycle ----------------------------------------------------------------------------

  @Test
  void closingTheModelClosesTheBedrockClientAndItsHttpClient() {
    answerOk();
    final var model = builder().create(bedrock(endpoint()));
    chat(model);

    model.close();

    assertThatThrownBy(() -> chat(model)).isInstanceOf(RuntimeException.class);
    assertThat(gateway.callCount(CONVERSE)).isEqualTo(1);
  }
}
