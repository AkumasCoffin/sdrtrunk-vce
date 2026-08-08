/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.stats.activity.P25ActivityLogSchema;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The bundled release transition from v0.6.2-alpha-9 to the current main database: adds the cross-site
 * deduplicated call counter to hourly site activity buckets.
 *
 * <p>This class has no command-line entry point and never opens or copies a database. The Application Migrator owns
 * the immutable backup, staged copy, transaction, validation and promotion boundaries.</p>
 */
final class Alpha9DatabaseMigration
{
    private static final String ALPHA_9_P25_VERSION = "24";
    private static final String P25_VERSION_KEY = "p25_activity_schema_version";
    //Fresh Alpha 9 database, which is also the layout produced by the bundled Alpha 7 migration.
    private static final String PUBLISHED_ALPHA_9_SCHEMA_FINGERPRINT =
        "ef9197c7cee7261cdda03a395b6552754f3607f6c0053acbe21c273e4242ce3a";

    private Alpha9DatabaseMigration()
    {
    }

    static void validateSource(Connection connection) throws SQLException
    {
        SdrTrunkDatabaseStartup.requireMainTrackDatabase(connection);
        String fingerprint = SqliteSchemaValidator.fingerprint(connection);

        if(!PUBLISHED_ALPHA_9_SCHEMA_FINGERPRINT.equals(fingerprint))
        {
            throw new SQLException("Database schema is not the exact published Alpha 9 layout (" + fingerprint +
                ")");
        }
    }

    static String migrate(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            //SQLite appends the column text after the last column definition, which matches the current
            //CREATE TABLE statement once whitespace is canonicalized for fingerprinting.
            statement.executeUpdate("ALTER TABLE p25_site_activity_bucket " +
                "ADD COLUMN deduped_call_count INTEGER NOT NULL DEFAULT 0");
        }

        SdrTrunkDatabaseStartup.setMetadata(connection, P25_VERSION_KEY,
            Integer.toString(P25ActivityLogSchema.SCHEMA_VERSION));
        SdrTrunkDatabaseStartup.setMetadata(connection, P25ActivityLogSchema.DEDUPED_CALL_METRICS_STARTED_AT_KEY,
            Long.toString(System.currentTimeMillis()));
        P25ActivityLogSchema.validate(connection);
        return "Alpha 9 migration: added the deduplicated call counter; hourly history reports deduplicated calls " +
            "from the migration time forward.";
    }
}
