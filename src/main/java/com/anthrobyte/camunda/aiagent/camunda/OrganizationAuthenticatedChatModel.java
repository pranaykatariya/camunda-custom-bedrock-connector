package com.anthrobyte.camunda.aiagent.camunda;

import static com.anthrobyte.camunda.aiagent.auth.AuthenticationFailureReason.GATEWAY_ACCESS_DENIED;
import static com.anthrobyte.camunda.aiagent.auth.AuthenticationFailureReason.GATEWAY_REJECTED_CREDENTIALS;

import com.anthrobyte.camunda.aiagent.auth.BamTokenCache;
import com.anthrobyte.camunda.aiagent.auth.OrganizationAuthenticationException;
import com.anthrobyte.camunda.aiagent.auth.OrganizationAuthenticationUnavailableException;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkServiceException;

/**
 * Wraps LangChain4j's {@code BedrockChatModel} for the organization gateway.
 *
 * <ul>
 *   <li><b>Fail closed before sending.</b> Credentials are obtained before the model is called. If
 *       that fails, the sanitized organization exception is thrown and no request is made. It is
 *       thrown as it is, not wrapped by the AWS SDK, so LangChain4j's retry loop never runs for it.
 *   <li><b>Sanitized errors.</b> Organization exceptions that the AWS SDK wrapped are unwrapped
 *       (as fresh, cause-less copies). A final gateway 401/403 becomes an {@link
 *       OrganizationAuthenticationException} without the response payload. Every other failure
 *       (LLM 4xx, gateway 5xx, timeouts, ...) is rethrown unchanged, so LangChain4j and Camunda
 *       handle it exactly as in the standard connector.
 * </ul>
 *
 * <p>Everything else delegates to the wrapped model.
 */
final class OrganizationAuthenticatedChatModel implements ChatModel {

  private static final Logger LOG = LoggerFactory.getLogger(OrganizationAuthenticatedChatModel.class);
  private static final int MAX_CAUSE_DEPTH = 16;

  private final ChatModel delegate;
  private final BamTokenCache tokenCache;

  OrganizationAuthenticatedChatModel(
      ChatModel delegate, BamTokenCache tokenCache) {
    this.delegate = delegate;
    this.tokenCache = tokenCache;
  }

  @Override
  public ChatResponse chat(ChatRequest chatRequest) {
    final long started = System.nanoTime();
    final int messages = chatRequest.messages().size();
    final int tools = sizeOf(chatRequest.toolSpecifications());
    LOG.atDebug()
        .addKeyValue("messages", messages)
        .addKeyValue("tools", tools)
        .log("Bedrock chat call starting");
    try {
      // Throws the sanitized organization exception if no credentials can be obtained.
      tokenCache.getCredentials();
      final ChatResponse response = delegate.chat(chatRequest);
      logCompleted(response, messages, tools, elapsedMillis(started));
      return response;
    } catch (RuntimeException e) {
      final RuntimeException sanitized = sanitize(e);
      // Only the type and the sanitized reason: SDK messages can echo gateway response payloads.
      LOG.atWarn()
          .addKeyValue("error", sanitized.getClass().getSimpleName())
          .addKeyValue("reason", reasonOf(sanitized))
          .addKeyValue("httpStatus", statusOf(e))
          .addKeyValue("messages", messages)
          .addKeyValue("durationMs", elapsedMillis(started))
          .log("Bedrock chat call failed");
      throw sanitized;
    }
  }

  /** One line per model call: what the agent turn cost and how the model ended it. No content. */
  private static void logCompleted(ChatResponse response, int messages, int tools, long durationMs) {
    final TokenUsage usage = response.tokenUsage();
    final AiMessage aiMessage = response.aiMessage();
    LOG.atInfo()
        .addKeyValue("model", response.modelName())
        .addKeyValue("finishReason", response.finishReason())
        .addKeyValue("toolCallsRequested", aiMessage == null ? 0 : sizeOf(aiMessage.toolExecutionRequests()))
        .addKeyValue("inputTokens", usage == null ? null : usage.inputTokenCount())
        .addKeyValue("outputTokens", usage == null ? null : usage.outputTokenCount())
        .addKeyValue("totalTokens", usage == null ? null : usage.totalTokenCount())
        .addKeyValue("messages", messages)
        .addKeyValue("tools", tools)
        .addKeyValue("durationMs", durationMs)
        .log("Bedrock chat call completed");
  }

  private static Object reasonOf(RuntimeException e) {
    if (e instanceof OrganizationAuthenticationException auth) {
      return auth.reason();
    }
    if (e instanceof OrganizationAuthenticationUnavailableException unavailable) {
      return unavailable.reason();
    }
    return null;
  }

  private static Integer statusOf(RuntimeException e) {
    Throwable t = e;
    for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++, t = t.getCause()) {
      if (t instanceof SdkServiceException service) {
        return service.statusCode();
      }
    }
    return null;
  }

  private static int sizeOf(List<?> list) {
    return list == null ? 0 : list.size();
  }

  private static long elapsedMillis(long startedNanos) {
    return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
  }

  static RuntimeException sanitize(RuntimeException e) {
    Throwable t = e;
    for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++, t = t.getCause()) {
      if (t instanceof OrganizationAuthenticationException auth) {
        return auth.copy();
      }
      if (t instanceof OrganizationAuthenticationUnavailableException unavailable) {
        return unavailable.copy();
      }
      if (t instanceof SdkServiceException service
          && (service.statusCode() == 401 || service.statusCode() == 403)) {
        final int status = service.statusCode();
        LOG.atWarn()
            .addKeyValue("httpStatus", status)
            .addKeyValue("reason", status == 401 ? GATEWAY_REJECTED_CREDENTIALS : GATEWAY_ACCESS_DENIED)
            .log("Bedrock gateway authentication failed");
        return new OrganizationAuthenticationException(
            status == 401 ? GATEWAY_REJECTED_CREDENTIALS : GATEWAY_ACCESS_DENIED, status, null);
      }
    }
    return e;
  }

  @Override
  public ChatRequestParameters defaultRequestParameters() {
    return delegate.defaultRequestParameters();
  }

  @Override
  public List<ChatModelListener> listeners() {
    return delegate.listeners();
  }

  @Override
  public ModelProvider provider() {
    return delegate.provider();
  }

  @Override
  public Set<Capability> supportedCapabilities() {
    return delegate.supportedCapabilities();
  }
}
