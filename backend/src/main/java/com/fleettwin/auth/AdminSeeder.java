package com.fleettwin.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Creates the first admin from ADMIN_USERNAME / ADMIN_PASSWORD when there are no users at all. */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminSeeder implements ApplicationRunner {

    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final SecurityProperties props;

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }
        if (!StringUtils.hasText(props.adminUsername()) || !StringUtils.hasText(props.adminPassword())) {
            log.warn("There are no users and ADMIN_USERNAME / ADMIN_PASSWORD are not set: nobody can log in");
            return;
        }
        if (props.adminPassword().length() < props.minPasswordLength()) {
            throw new IllegalStateException("ADMIN_PASSWORD must be at least " + props.minPasswordLength() + " characters");
        }
        User admin = new User();
        admin.setUsername(props.adminUsername());
        admin.setPasswordHash(passwords.encode(props.adminPassword()));
        admin.setRole(Role.ADMIN);
        users.save(admin);
        log.info("Created the first admin user '{}'", admin.getUsername());
    }
}
