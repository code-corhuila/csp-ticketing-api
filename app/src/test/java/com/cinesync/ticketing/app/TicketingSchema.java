package com.cinesync.ticketing.app;

import org.flywaydb.core.Flyway;
import org.testcontainers.containers.JdbcDatabaseContainer;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Applies the migrations of {@code csp-ticketing-db} to the database of a test, with Flyway and the settings of that
 * repository ({@code flyway.conf}), so the tests run against the schema the service is deployed with and cannot drift
 * from it. The API never migrates (norm 5.2.1): only the tests do, and they read the {@code sql} folder of the sibling
 * checkout of {@code csp-ticketing-db} ({@code ../../csp-ticketing-db/sql} from a module), or the folder given in
 * {@code -Dticketing.db.dir}. The migrations assume the {@code postgres} administrator, so the container uses that user.
 */
public final class TicketingSchema {

    public static final String SCHEMA = "ticketing_fulfillment";

    private static final String DIRECTORY_PROPERTY = "ticketing.db.dir";
    private static final String SIBLING_CHECKOUT = "../../csp-ticketing-db/sql";

    private TicketingSchema() {
    }

    public static void migrate(JdbcDatabaseContainer<?> database) {
        Path migrations = Path.of(System.getProperty(DIRECTORY_PROPERTY, SIBLING_CHECKOUT)).toAbsolutePath().normalize();
        if (!Files.isDirectory(migrations)) {
            throw new IllegalStateException("The migrations of csp-ticketing-db are not in " + migrations
                    + ": clone csp-ticketing-db next to this repository or run with -D" + DIRECTORY_PROPERTY + "=<folder>");
        }
        Flyway.configure()
                .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .locations("filesystem:" + migrations)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .validateOnMigrate(true)
                .cleanDisabled(true)
                .load()
                .migrate();
    }
}
