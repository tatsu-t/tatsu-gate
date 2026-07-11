package dev.gate.modules.audit;

import dev.gate.core.Database;
import dev.gate.core.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.regex.Pattern;

/**
 * Writes admin operations to an audit table via {@link Database}. Failures are
 * logged and swallowed — auditing must never break the operation itself.
 *
 * <p>Expected table shape (create it in {@code schema.sql}):
 * <pre>
 * CREATE TABLE IF NOT EXISTS operation_logs (
 *     id            SERIAL PRIMARY KEY,
 *     "user"        VARCHAR(255) NOT NULL,
 *     action        VARCHAR(255) NOT NULL,
 *     target        VARCHAR(255),
 *     detail        TEXT,
 *     result        VARCHAR(32),
 *     error_message TEXT,
 *     timestamp     TIMESTAMPTZ NOT NULL DEFAULT NOW()
 * );
 * </pre>
 */
public final class AuditLog {

    private static final Logger logger = new Logger(AuditLog.class);
    private static final Pattern SAFE_IDENT = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private final String insertSql;

    /** Uses the default {@code operation_logs} table. */
    public AuditLog() {
        this("operation_logs");
    }

    /** @param tableName audit table name (validated against {@code ^[a-zA-Z_][a-zA-Z0-9_]*$}) */
    public AuditLog(String tableName) {
        if (!SAFE_IDENT.matcher(tableName).matches()) {
            throw new IllegalArgumentException("Unsafe audit table name: " + tableName);
        }
        this.insertSql = "INSERT INTO " + tableName +
                " (\"user\",action,target,detail,result,error_message,timestamp) VALUES (?,?,?,?,?,?,NOW())";
    }

    /**
     * Records one operation. Runs synchronously — a single-row INSERT is fast,
     * and staying off common pools avoids starving them under load.
     */
    public void write(String user, String action, String target, String detail, String result, String errorMsg) {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(insertSql)) {
            ps.setString(1, user != null ? user : "unknown");
            ps.setString(2, action);
            ps.setString(3, target != null ? target.substring(0, Math.min(255, target.length())) : "");
            ps.setString(4, detail);
            ps.setString(5, result);
            ps.setString(6, errorMsg);
            ps.executeUpdate();
        } catch (Exception e) {
            logger.warn("AuditLog.write failed: {}", e.getMessage());
        }
    }
}
