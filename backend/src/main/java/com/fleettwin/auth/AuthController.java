package com.fleettwin.auth;

import java.security.Principal;

import com.fleettwin.auth.TokenService.Tokens;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    public record Login(@NotBlank String username, @NotBlank String password) {
    }

    public record Refresh(@NotBlank String refreshToken) {
    }

    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final TokenService tokens;

    @PostMapping("/login")
    public Tokens login(@Valid @RequestBody Login login) {
        // One answer for "no such user", "wrong password" and "disabled", so usernames cannot be probed.
        return users.findByUsername(login.username())
                .filter(u -> u.isEnabled() && passwords.matches(login.password(), u.getPasswordHash()))
                .map(tokens::issue)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Wrong username or password"));
    }

    /** A new access and refresh token for a refresh token that is still valid. */
    @PostMapping("/refresh")
    public Tokens refresh(@Valid @RequestBody Refresh refresh) {
        try {
            Jwt jwt = tokens.parseRefresh(refresh.refreshToken());
            return users.findByUsername(jwt.getSubject())
                    .filter(u -> u.isEnabled() && ((Number) jwt.getClaim("ver")).intValue() == u.getTokenVersion())
                    .map(tokens::issue)
                    .orElseThrow(() -> new JwtException("revoked"));
        } catch (JwtException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session expired, log in again");
        }
    }

    /** Ends every session of this user: their refresh tokens stop working, access tokens run out within minutes. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(Principal principal) {
        users.findByUsername(principal.getName()).ifPresent(u -> {
            u.setTokenVersion(u.getTokenVersion() + 1);
            users.save(u);
        });
    }

    @GetMapping("/me")
    public User me(Principal principal) {
        return users.findByUsername(principal.getName()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }
}
