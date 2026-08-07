/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */
package io.github.dsheirer.control;

import io.github.dsheirer.database.SdrTrunkDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read-only lookups against the stats/activity SQLite database for the control server, primarily resolving which
 * P25 site a voice call was received on so the node agent can attribute uploaded calls to sites.
 *
 * <p>All queries open a short-lived read-only connection (PRAGMA query_only=ON, busy_timeout) because the activity
 * writer owns the database.  A busy/locked database yields a not-found result rather than an error.</p>
 */
public class ControlActivityLookup
{
    private static final Logger mLog = LoggerFactory.getLogger(ControlActivityLookup.class);

    private final Path mDatabasePath;

    /**
     * Constructs the lookup.
     * @param databasePath to the shared sdrtrunk.sqlite database.
     */
    public ControlActivityLookup(Path databasePath)
    {
        mDatabasePath = databasePath;
    }

    /**
     * Resolves the P25 site (RFSS/site/NAC + system identity) a call was received on.
     *
     * <p>Resolution order:</p>
     * <ol>
     *   <li>Detailed activity events ({@code p25_activity_event_resolved}) matching the target talkgroup within the
     *       time window - first with source radio and frequency, then progressively relaxed.</li>
     *   <li>Frequency-to-site mapping via {@code p25_site_channel} downlink frequencies (works without detailed
     *       history).</li>
     *   <li>A single distinct site across all receiver contexts (the common single-site node).</li>
     * </ol>
     *
     * @param talkgroup target talkgroup id (0 to skip event matching).
     * @param sourceRadio source radio id, or 0 when unknown.
     * @param frequencyHz voice channel frequency in Hz, or 0 when unknown.
     * @param timestampMs call timestamp in epoch milliseconds.
     * @param windowMs half-width of the event match window in milliseconds.
     * @return result map: {@code {found:true, rfss, site, nac, wacn, systemId, source:"event|channel|context"}} or
     *         {@code {found:false}}.
     */
    public Map<String,Object> findCallSite(int talkgroup, int sourceRadio, long frequencyHz, long timestampMs,
                                           long windowMs)
    {
        Map<String,Object> notFound = new LinkedHashMap<>();
        notFound.put("found", false);

        if(!Files.isRegularFile(mDatabasePath))
        {
            return notFound;
        }

        try(Connection connection = openReadOnly())
        {
            if(talkgroup > 0 && timestampMs > 0)
            {
                Map<String,Object> event = findByEvent(connection, talkgroup, sourceRadio, frequencyHz, timestampMs,
                        windowMs);

                if(event != null)
                {
                    return event;
                }
            }

            if(frequencyHz > 0)
            {
                Map<String,Object> channel = findByChannelFrequency(connection, frequencyHz);

                if(channel != null)
                {
                    return channel;
                }
            }

            Map<String,Object> single = findSingleContextSite(connection);

            if(single != null)
            {
                return single;
            }
        }
        catch(Exception e)
        {
            //Busy/locked/missing database - report not-found so the caller uploads without attribution.
            mLog.debug("Call-site lookup failed - returning not found", e);
        }

        return notFound;
    }

    /**
     * Matches a detailed activity event by talkgroup within the window, preferring rows that also match the source
     * radio and frequency, and always preferring the row closest in time.
     */
    private Map<String,Object> findByEvent(Connection connection, int talkgroup, int sourceRadio, long frequencyHz,
                                           long timestampMs, long windowMs) throws Exception
    {
        String sql = """
            SELECT resolved_rfss, resolved_site, resolved_nac, resolved_wacn, resolved_system_id
            FROM p25_activity_event_resolved
            WHERE target_id = ?
              AND observed_at_ms BETWEEN ? AND ?
              AND resolved_site IS NOT NULL
              AND (? = 0 OR source_radio_id = ?)
              AND (? = 0 OR frequency_hz = ?)
            ORDER BY ABS(observed_at_ms - ?)
            LIMIT 1
            """;

        //Pass 1: strict (source + frequency), pass 2: source only, pass 3: talkgroup only.
        long[][] passes = {
                {sourceRadio, frequencyHz},
                {sourceRadio, 0},
                {0, 0}
        };

        for(long[] pass : passes)
        {
            try(PreparedStatement statement = connection.prepareStatement(sql))
            {
                statement.setInt(1, talkgroup);
                statement.setLong(2, timestampMs - windowMs);
                statement.setLong(3, timestampMs + windowMs);
                statement.setLong(4, pass[0]);
                statement.setLong(5, pass[0]);
                statement.setLong(6, pass[1]);
                statement.setLong(7, pass[1]);
                statement.setLong(8, timestampMs);

                try(ResultSet results = statement.executeQuery())
                {
                    if(results.next())
                    {
                        return siteResult(results.getObject(1), results.getObject(2), results.getObject(3),
                                results.getObject(4), results.getObject(5), "event");
                    }
                }
            }
        }

        return null;
    }

    /**
     * Maps a voice frequency to a site via the site channel plans (downlink frequencies), joined to the site
     * snapshot for the site identity.  Prefers the most recently confirmed channel.
     */
    private Map<String,Object> findByChannelFrequency(Connection connection, long frequencyHz) throws Exception
    {
        String sql = """
            SELECT ps.rfss, ps.site, ps.nac, NULL, NULL
            FROM p25_site_channel sc
            JOIN p25_site_snapshot ps ON ps.guid = sc.guid
            WHERE sc.downlink_hz = ? AND ps.site IS NOT NULL
            ORDER BY sc.confirmed_at_ms DESC
            LIMIT 1
            """;

        try(PreparedStatement statement = connection.prepareStatement(sql))
        {
            statement.setLong(1, frequencyHz);

            try(ResultSet results = statement.executeQuery())
            {
                if(results.next())
                {
                    return siteResult(results.getObject(1), results.getObject(2), results.getObject(3), null, null,
                            "channel");
                }
            }
        }

        return null;
    }

    /**
     * When every receiver context that knows its site agrees on a single (rfss, site), the node only monitors one
     * site and any call can be attributed to it.
     */
    private Map<String,Object> findSingleContextSite(Connection connection) throws Exception
    {
        String sql = """
            SELECT rfss, site, nac
            FROM receiver_context
            WHERE rfss IS NOT NULL AND site IS NOT NULL
            GROUP BY rfss, site
            """;

        try(Statement statement = connection.createStatement();
            ResultSet results = statement.executeQuery(sql))
        {
            Map<String,Object> only = null;

            while(results.next())
            {
                if(only != null)
                {
                    //More than one distinct site - ambiguous.
                    return null;
                }

                only = siteResult(results.getObject(1), results.getObject(2), results.getObject(3), null, null,
                        "context");
            }

            return only;
        }
    }

    private static Map<String,Object> siteResult(Object rfss, Object site, Object nac, Object wacn, Object systemId,
                                                 String source)
    {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("found", true);
        result.put("rfss", rfss);
        result.put("site", site);
        result.put("nac", nac);
        result.put("wacn", wacn);
        result.put("systemId", systemId);
        result.put("source", source);
        return result;
    }

    /**
     * Opens a short-lived read-only connection with a bounded busy timeout.  Mirrors the stats web database
     * access pattern so lookups never write and never block the activity writer for long.
     */
    private Connection openReadOnly() throws Exception
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);

        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA busy_timeout=" + SdrTrunkDatabase.BUSY_TIMEOUT_MILLISECONDS);
            statement.execute("PRAGMA query_only=ON");
        }

        return connection;
    }
}
