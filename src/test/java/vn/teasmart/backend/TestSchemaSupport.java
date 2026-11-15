package vn.teasmart.backend;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;

/** Adds the current checkout schema to synthetic integration-test databases only. */
public final class TestSchemaSupport {
    private TestSchemaSupport() {}
    public static List<String> applyCheckoutMigration(Connection connection) throws Exception {
        String sql=Files.readString(Path.of("database/migrations/1027_checkout_sessions_payment_attempts.sql"));
        try(var statement=connection.createStatement()) {
            for(String part:sql.replaceAll("(?m)^--.*$","").split(";"))
                if(!part.isBlank()) statement.execute(part.trim());
        }
        // Callers reverse this list when deleting child rows before parents.
        return List.of("checkout_sessions","checkout_session_items","payment_attempts");
    }
}
