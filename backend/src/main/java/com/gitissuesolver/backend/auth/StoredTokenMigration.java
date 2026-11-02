package com.gitissuesolver.backend.auth;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Re-encrypt legacy plaintext credentials once, without logging their values. */
@Component
@Profile("prod")
public class StoredTokenMigration implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final EncryptedTokenConverter converter;
    public StoredTokenMigration(JdbcTemplate jdbc, EncryptedTokenConverter converter) { this.jdbc = jdbc; this.converter = converter; }
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        for (String table : new String[]{"users", "repos"}) {
            var rows = jdbc.queryForList("SELECT id, github_token FROM " + table + " WHERE github_token IS NOT NULL AND github_token <> '' AND github_token NOT LIKE 'enc:v1:%'");
            for (var row : rows) {
                String original = (String) row.get("github_token");
                jdbc.update("UPDATE " + table + " SET github_token = ? WHERE id = ? AND github_token = ?",
                        converter.convertToDatabaseColumn(original), row.get("id"), original);
            }
        }
    }
}
