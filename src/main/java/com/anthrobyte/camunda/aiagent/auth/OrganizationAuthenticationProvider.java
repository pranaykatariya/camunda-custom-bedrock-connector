package com.anthrobyte.camunda.aiagent.auth;

/**
 * Supplies the headers the organization Bedrock gateway expects on every request.
 *
 * <p>This is the only abstraction that knows how the organization authenticates. It knows nothing
 * about Camunda, the AI Agent, LangChain4j or the AWS SDK, and the transport layer knows nothing
 * about how credentials are obtained.
 *
 * <p>Built-in implementations (selected with {@code organization.ai-gateway.auth.mode}):
 *
 * <ul>
 *   <li>{@link CachingTokenAuthenticationProvider}: a token from an {@link AccessTokenSource}
 *       (placeholder JWT, OAuth 2.0 client credentials, or your own), with caching, expiry and
 *       single-flight refresh.
 *   <li>{@link StaticHeadersAuthenticationProvider}: fixed headers taken from the environment.
 * </ul>
 *
 * <p>To use a mechanism that is not token based, register your own Spring bean of this type. The
 * auto-configured one then backs off ({@code @ConditionalOnMissingBean}).
 *
 * <p><b>Contract.</b> Implementations must be thread-safe, since AI Agent jobs run concurrently.
 * They must never log credential values. Failures must be thrown as {@link
 * OrganizationAuthenticationException} (permanent) or {@link
 * OrganizationAuthenticationUnavailableException} (transient).
 */
public interface OrganizationAuthenticationProvider {

  /**
   * Returns the credentials to attach to the next gateway request. Called once per HTTP attempt,
   * so it should be cheap when valid cached credentials exist.
   */
  GatewayCredentials getCredentials();

  /**
   * Called when the gateway rejected {@code rejected} with HTTP 401. Implementations that cache
   * credentials should discard them, but only if they are still the current ones.
   */
  default void invalidate(GatewayCredentials rejected) {}

  /**
   * Whether a call to {@link #getCredentials()} after {@link #invalidate(GatewayCredentials)} can
   * produce different credentials. Retrying after a 401 only makes sense in that case.
   */
  default boolean supportsRefresh() {
    return false;
  }
}
