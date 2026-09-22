package com.anthrobyte.camunda.aiagent.auth;

/**
 * Obtains one new organization token. It does no caching: {@link
 * CachingTokenAuthenticationProvider} caches the token until shortly before {@link
 * AccessToken#expiresAt()} and makes sure only one thread requests a new one at a time.
 *
 * <p>Built-in implementations (selected with {@code organization.ai-gateway.auth.mode}):
 *
 * <ul>
 *   <li>{@link PlaceholderJwtTokenSource}: a random, unsigned JWT for wiring tests. Replace it.
 *   <li>{@link com.anthrobyte.camunda.aiagent.auth.oauth2.ClientCredentialsTokenClient}: OAuth 2.0
 *       client-credentials grant.
 * </ul>
 *
 * <p>To plug in the organization's own token generation, register a Spring bean of this type and
 * set {@code mode: CUSTOM}.
 *
 * <p><b>Contract.</b> Implementations are called by one thread at a time, but from different
 * threads over time. They must never log the token. Failures should be thrown as {@link
 * OrganizationAuthenticationException} (permanent) or {@link
 * OrganizationAuthenticationUnavailableException} (transient). Any other runtime exception is
 * turned into a permanent {@link AuthenticationFailureReason#TOKEN_SOURCE_FAILED} failure without
 * its message, since that message could contain secrets.
 */
@FunctionalInterface
public interface AccessTokenSource {

  AccessToken requestToken();
}
