package com.fleettwin.auth;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fleettwin.config.FleetProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** ADMIN only (see SecurityConfig): users, the audit log and the settings in force. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class UserController {

    public record NewUser(@NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{3,50}") String username,
                          @NotBlank String password, @NotNull Role role) {
    }

    /** Any field left out stays as it is. */
    public record UserChange(Role role, Boolean enabled, String password) {
    }

    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final SecurityProperties security;
    private final AuditService audit;
    private final FleetProperties settings;

    @GetMapping("/users")
    public List<User> list() {
        return users.findAll(Sort.by("username"));
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public User create(@Valid @RequestBody NewUser req) {
        if (users.findByUsername(req.username()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That username is taken");
        }
        User user = new User();
        user.setUsername(req.username());
        user.setPasswordHash(hash(req.password()));
        user.setRole(req.role());
        user = users.save(user);
        audit.record("CREATE", "user", user.getId(), user.getUsername() + " as " + user.getRole());
        return user;
    }

    @PatchMapping("/users/{id}")
    public User update(@PathVariable long id, @RequestBody UserChange change, Principal principal) {
        User user = users.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        boolean losesAdmin = user.getRole() == Role.ADMIN && user.isEnabled()
                && (Boolean.FALSE.equals(change.enabled()) || (change.role() != null && change.role() != Role.ADMIN));
        if (losesAdmin && users.countByRoleAndEnabledTrue(Role.ADMIN) <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This is the last active admin");
        }
        List<String> changed = new ArrayList<>();
        if (change.role() != null && change.role() != user.getRole()) {
            changed.add("role " + user.getRole() + " -> " + change.role());
            user.setRole(change.role());
        }
        if (change.enabled() != null && change.enabled() != user.isEnabled()) {
            changed.add(change.enabled() ? "enabled" : "disabled");
            user.setEnabled(change.enabled());
        }
        if (change.password() != null) {
            changed.add("password reset");
            user.setPasswordHash(hash(change.password()));
        }
        if (!changed.isEmpty()) {
            // Whatever changed, existing sessions must not outlive it.
            user.setTokenVersion(user.getTokenVersion() + 1);
            user = users.save(user);
            audit.record("UPDATE", "user", id, user.getUsername() + ": " + String.join(", ", changed));
        }
        return user;
    }

    @GetMapping("/audit-log")
    public List<Map<String, Object>> auditLog(@RequestParam(defaultValue = "200") int limit) {
        return audit.latest(Math.min(Math.max(limit, 1), 1000));
    }

    /** The thresholds, weights and schedules in force (fleet.* in application.yml). Read-only: change them there. */
    @GetMapping("/admin/settings")
    public FleetProperties settings() {
        return settings;
    }

    private String hash(String password) {
        if (password.length() < security.minPasswordLength()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Password must be at least " + security.minPasswordLength() + " characters");
        }
        return passwords.encode(password);
    }
}
