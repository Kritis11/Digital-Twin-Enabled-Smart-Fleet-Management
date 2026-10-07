package com.fleettwin.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

/**
 * Signs and checks the two kinds of JWT (HS256, secret from JWT_SECRET).
 * Access tokens are short-lived and carry the role; they are what the API and the WebSocket accept.
 * Refresh tokens only buy a new pair, and only while their "ver" still matches the user's token version.
 */
@Service
public class TokenService {

    private static final String TYPE = "typ";
    private static final String ACCESS = "access";
    private static final String REFRESH = "refresh";

    public record Tokens(String accessToken, String refreshToken, long expiresIn, String username, Role role) {
    }

    private final SecurityProperties props;
    private final NimbusJwtEncoder encoder;
    private final JwtDecoder accessDecoder;
    private final JwtDecoder refreshDecoder;

    public TokenService(SecurityProperties props) {
        this.props = props;
        SecretKey key = new SecretKeySpec(props.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.accessDecoder = decoder(key, ACCESS);
        this.refreshDecoder = decoder(key, REFRESH);
    }

    private static JwtDecoder decoder(SecretKey key, String type) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> ofType = new JwtClaimValidator<String>(TYPE, type::equals);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(), ofType));
        return decoder;
    }

    public Tokens issue(User user) {
        return new Tokens(
                sign(user, ACCESS, props.accessTokenTtl()), sign(user, REFRESH, props.refreshTokenTtl()),
                props.accessTokenTtl().toSeconds(), user.getUsername(), user.getRole());
    }

    /** Decoder for access tokens: the one Spring Security and the WebSocket use. Refresh tokens fail it. */
    public JwtDecoder accessDecoder() {
        return accessDecoder;
    }

    /** The username in a refresh token and the token version it was issued for. Throws JwtException if it is not valid. */
    public Jwt parseRefresh(String token) throws JwtException {
        return refreshDecoder.decode(token);
    }

    private String sign(User user, String type, Duration ttl) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().subject(user.getUsername()).issuedAt(now)
                .expiresAt(now.plus(ttl)).claim(TYPE, type);
        if (ACCESS.equals(type)) {
            claims.claim("role", user.getRole().name());
        } else {
            claims.claim("ver", user.getTokenVersion());
        }
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
    }
}
