/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrossSiteCallDeduplicatorTest
{
    private static final String SCOPE = "p25:BEE00:348";
    private static final String SITE_A = "aaaa4567-e89b-12d3-a456-426614174000";
    private static final String SITE_B = "bbbb4567-e89b-12d3-a456-426614174000";

    @TempDir
    private Path mTemporaryFolder;

    @Test
    void countsTheFirstSiteAndSuppressesOtherSitesWithinTheWindow()
    {
        CrossSiteCallDeduplicator deduplicator = new CrossSiteCallDeduplicator();
        assertTrue(deduplicator.isFirstObservation(SCOPE, call(1_000L, SITE_A, "56138")));
        assertFalse(deduplicator.isFirstObservation(SCOPE, call(3_000L, SITE_B, "56138")));
    }

    @Test
    void countsOtherSitesAfterTheWindowExpires()
    {
        CrossSiteCallDeduplicator deduplicator = new CrossSiteCallDeduplicator();
        assertTrue(deduplicator.isFirstObservation(SCOPE, call(1_000L, SITE_A, "56138")));
        assertTrue(deduplicator.isFirstObservation(SCOPE,
            call(1_000L + CrossSiteCallDeduplicator.SAME_CALL_WINDOW_MILLISECONDS + 1, SITE_B, "56138")));
    }

    @Test
    void owningSiteStaysCountableSoWriteRetriesReachTheSameDecision()
    {
        CrossSiteCallDeduplicator deduplicator = new CrossSiteCallDeduplicator();
        assertTrue(deduplicator.isFirstObservation(SCOPE, call(1_000L, SITE_A, "56138")));
        assertTrue(deduplicator.isFirstObservation(SCOPE, call(1_000L, SITE_A, "56138")));
        assertFalse(deduplicator.isFirstObservation(SCOPE, call(2_000L, SITE_B, "56138")));
    }

    @Test
    void ownerReobservationExtendsTheWindowForDuplicates()
    {
        CrossSiteCallDeduplicator deduplicator = new CrossSiteCallDeduplicator();
        assertTrue(deduplicator.isFirstObservation(SCOPE, call(1_000L, SITE_A, "56138")));
        assertTrue(deduplicator.isFirstObservation(SCOPE, call(4_000L, SITE_A, "56138")));
        assertFalse(deduplicator.isFirstObservation(SCOPE, call(8_000L, SITE_B, "56138")));
    }

    @Test
    void distinctTalkgroupsAndScopesCountIndependently()
    {
        CrossSiteCallDeduplicator deduplicator = new CrossSiteCallDeduplicator();
        assertTrue(deduplicator.isFirstObservation(SCOPE, call(1_000L, SITE_A, "56138")));
        assertTrue(deduplicator.isFirstObservation(SCOPE, call(1_000L, SITE_B, "56139")));
        assertTrue(deduplicator.isFirstObservation("p25:BEE00:349", call(1_000L, SITE_B, "56138")));
    }

    @Test
    void bucketsCountEverySiteWhileDedupedCountsTheLogicalCallOnce() throws Exception
    {
        Path database = mTemporaryFolder.resolve("deduped.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        CrossSiteCallDeduplicator deduplicator = new CrossSiteCallDeduplicator();

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.insertSite(connection, siteSnapshot(1_000L, SITE_A, 1));
            P25ActivityLogSchema.insertSite(connection, siteSnapshot(1_000L, SITE_B, 2));
            P25ActivityLogSchema.recordActivity(connection, call(10_000L, SITE_A, "56138"), true, deduplicator);
            P25ActivityLogSchema.recordActivity(connection, call(12_000L, SITE_B, "56138"), true, deduplicator);
            assertBucketSums(connection, 2, 1);

            P25ActivityLogSchema.recordActivity(connection, call(30_000L, SITE_B, "56138"), true, deduplicator);
            assertBucketSums(connection, 3, 2);
        }
    }

    private static P25ActivityLogRecords.SiteSnapshot siteSnapshot(long timestamp, String guid, int site)
    {
        return new P25ActivityLogRecords.SiteSnapshot(timestamp, guid,
            P25ActivityLogRecords.ContextKind.TRUNKED_SITE, "hash-" + site, "APCO25", "Site " + site,
            "Example System", "P25-" + site, 0xBEE00, 0x348, 0x348, 2, site, 0, true, null, 856_137_500L,
            856_137_500L, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static void assertBucketSums(Connection connection, int expectedCalls, int expectedDeduped)
        throws Exception
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(
                "SELECT SUM(call_count), SUM(deduped_call_count) FROM p25_site_activity_bucket"))
        {
            assertTrue(resultSet.next());
            assertEquals(expectedCalls, resultSet.getInt(1));
            assertEquals(expectedDeduped, resultSet.getInt(2));
        }
    }

    private static P25ActivityLogRecords.ActivityEvent call(long timestamp, String guid, String talkgroup)
    {
        return new P25ActivityLogRecords.ActivityEvent(timestamp, "GUID:" + guid, guid,
            P25ActivityLogRecords.ContextKind.TRUNKED_SITE, "APCO25", P25ActivityLogRecords.Action.CALL,
            "CALL_GROUP", "1811524", talkgroup, "TALKGROUP", 854_187_500L, "00-0509", 1, false, null, null,
            0xBEE00, 0x348, 0x348, 2, 1, "Example Site", null, null, true, null, null);
    }
}
