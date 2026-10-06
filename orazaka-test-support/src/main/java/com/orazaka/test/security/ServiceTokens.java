package com.orazaka.test.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Mints the tokens an {@code InternalSurfaceAuthIT} needs.
 *
 * <p>Here rather than copied into each service's tests: the three cases every such suite asserts —
 * anonymous, a user token, a {@code SERVICE} token — are the same three everywhere, and a per
 * service copy is a per-service opportunity to get the claim shape subtly wrong and prove nothing.
 *
 * <p>Test-support only. Production minting lives in each client's {@code ServiceTokenProvider}
 * until wave 3's {@code orazaka-service-kit} absorbs them.
 */
public final class ServiceTokens {

  /** The HS256 secret the slice tests configure. Long enough for {@code SessionJwtProperties}. */
  public static final String TEST_SECRET = "orazaka-test-secret-at-least-32-characters!";

  private ServiceTokens() {}

  /**
   * A token carrying {@code roles: ["SERVICE"]} — what a service-to-service caller presents.
   *
   * @return the compact JWS
   */
  public static String serviceToken() {
    return token("orazaka-test-client", "SERVICE");
  }

  /**
   * A token carrying {@code roles: ["ROLE_USER"]} — a real, valid session that must still be
   * refused on {@code /internal/v1/**}.
   *
   * @return the compact JWS
   */
  public static String userToken() {
    return token("11111111-1111-4111-8111-111111111111", "ROLE_USER");
  }

  private static String token(String subject, String role) {
    Instant now = Instant.now();
    String header = encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    String payload =
        encode(
            "{\"sub\":\"%s\",\"roles\":[\"%s\"],\"iat\":%d,\"exp\":%d}"
                .formatted(subject, role, now.getEpochSecond(), now.getEpochSecond() + 300)
                .getBytes(StandardCharsets.UTF_8));
    String signingInput = header + "." + payload;
    return signingInput + "." + encode(sign(signingInput));
  }

  private static byte[] sign(String signingInput) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(TEST_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("cannot sign the test token", e);
    }
  }

  private static String encode(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }
}
