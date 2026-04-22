package app.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.gate.core.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class Database {
    private static final Logger logger = new Logger(Database.class);
    private static volatile HikariDataSource dataSource;

    /**
     * Initialises the connection pool from environment variables.
     *
     * Direct TCP (local / Cloud SQL Auth Proxy):
     *   DB_HOST  (default: localhost)
     *   DB_PORT  (default: 5432)
     *   DB_NAME  (default: rsai)
     *   DB_USER  (default: postgres)
     *   DB_PASSWORD
     *
     * Cloud SQL connector (no proxy):
     *   CLOUD_SQL_INSTANCE=project:region:instance  ← triggers connector mode
     *   DB_NAME, DB_USER, DB_PASSWORD as above
     *
     * DB_POOL_SIZE (default: 10)
     */
    public static void init() throws Exception {
        HikariConfig hikari = new HikariConfig();

        String cloudSqlInstance = env("CLOUD_SQL_INSTANCE", "");
        String dbName  = env("DB_NAME",     "rsai");
        String user    = env("DB_USER",     "postgres");
        String password = env("DB_PASSWORD", "");
        int    poolSize = Integer.parseInt(env("DB_POOL_SIZE", "10"));

        if (!cloudSqlInstance.isBlank()) {
            hikari.setJdbcUrl(String.format(
                "jdbc:postgresql:///%s?cloudSqlInstance=%s&socketFactory=com.google.cloud.sql.postgres.SocketFactory",
                dbName, cloudSqlInstance
            ));
            logger.info("Connecting to Cloud SQL: {}/{}", cloudSqlInstance, dbName);
        } else {
            String host = env("DB_HOST", "localhost");
            int    port = Integer.parseInt(env("DB_PORT", "5432"));
            hikari.setJdbcUrl(String.format("jdbc:postgresql://%s:%d/%s", host, port, dbName));
            logger.info("Connecting to PostgreSQL at {}:{}/{}", host, port, dbName);
        }

        hikari.setUsername(user);
        hikari.setPassword(password);
        hikari.setMaximumPoolSize(poolSize);
        hikari.setPoolName("rsai-pool");
        hikari.setInitializationFailTimeout(10_000);

        dataSource = new HikariDataSource(hikari);
        logger.info("Database connection pool initialized");

        runSchema();
    }

    public static Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public static void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    private static void runSchema() throws Exception {
        try (InputStream is = Database.class.getResourceAsStream("/schema.sql")) {
            if (is == null) return;
            String sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute(sql);
            }
        }
        logger.info("Schema applied");
    }

    private static String env(String key, String defaultValue) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }
}
