package com.fleettwin.auth;

import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** Who did what and when. Written for every change a user makes through the API. */
@Service
@RequiredArgsConstructor
public class AuditService {

    private final JdbcTemplate jdbc;

    public void record(String action, String entity, Long entityId, String detail) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        jdbc.update("INSERT INTO audit_log (username, action, entity, entity_id, detail) VALUES (?, ?, ?, ?, ?)",
                auth != null ? auth.getName() : "system", action, entity, entityId, detail);
    }

    /** Newest first. */
    public List<Map<String, Object>> latest(int limit) {
        return jdbc.queryForList("""
                SELECT id AS "id", ts AS "ts", username AS "username", action AS "action", entity AS "entity",
                       entity_id AS "entityId", detail AS "detail"
                FROM audit_log ORDER BY ts DESC, id DESC LIMIT ?""", limit);
    }
}
