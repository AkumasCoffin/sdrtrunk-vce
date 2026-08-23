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
import io.github.dsheirer.module.decode.event.DecodeEventType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
    /**
     * How long a READ-ONLY lookup waits on a locked database before giving up.
     *
     * Deliberately far below the writer's 30s (SdrTrunkDatabase). These queries
     * are served from a fixed HTTP thread pool and the node agent abandons them
     * after 4s, but com.sun.net.httpserver does not abort a handler when its
     * client disconnects — so a long wait here means a thread held for a
     * request nobody is reading any more, and enough of those park the server.
     */
    private static final int READ_BUSY_TIMEOUT_MILLISECONDS = 2000;

    private static final Logger mLog = LoggerFactory.getLogger(ControlActivityLookup.class);

    /**
     * Event-type codes (DecodeEventType ordinal + 1, matching the resolved view's event_type_code decoding) that
     * represent call traffic for the calls-only events filter - enum names containing CALL, DATA or PAGE:
     * CALL, CALL_ENCRYPTED, CALL_GROUP, CALL_GROUP_ENCRYPTED, CALL_PATCH_GROUP, CALL_PATCH_GROUP_ENCRYPTED,
     * CALL_ALERT, CALL_DETECT, CALL_IN_PROGRESS, CALL_DO_NOT_MONITOR, CALL_END, CALL_INTERCONNECT,
     * CALL_INTERCONNECT_ENCRYPTED, CALL_UNIQUE_ID, CALL_UNIT_TO_UNIT, CALL_UNIT_TO_UNIT_ENCRYPTED, CALL_NO_TUNER,
     * CALL_TIMEOUT, DATA_CALL, DATA_CALL_ENCRYPTED, DATA_PACKET, PAGE.
     */
    private static final String CALL_EVENT_TYPE_CODES = Arrays.stream(DecodeEventType.values())
            .filter(type -> type.name().contains("CALL") || type.name().contains("DATA") ||
                    type.name().contains("PAGE"))
            .map(type -> Integer.toString(type.ordinal() + 1))
            .collect(Collectors.joining(","));

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
     * Parses the group_concat of an event's patched member talkgroups into a list.
     *
     * <p>Always a list, never null: an ordinary call has no members and an empty list says exactly that, where a null
     * would be indistinguishable from an older server that does not report members at all.</p>
     *
     * @param concatenated comma-separated talkgroup ids, or null/empty when the call is not patched.
     * @return member talkgroup ids, ascending and without repeats.
     */
    private static List<Integer> parsePatchMembers(String concatenated)
    {
        if(concatenated == null || concatenated.isBlank())
        {
            return List.of();
        }

        List<Integer> members = new ArrayList<>();

        for(String part: concatenated.split(","))
        {
            try
            {
                int value = Integer.parseInt(part.trim());

                if(value > 0 && !members.contains(value))
                {
                    members.add(value);
                }
            }
            catch(NumberFormatException e)
            {
                //Ignore - a malformed id costs one member, never the event.
            }
        }

        members.sort(Integer::compareTo);

        return members;
    }

    /**
     * Returns detailed activity events newer than the supplied id, in id order, for the node agent's activity feed.
     *
     * <p>Detail rows only exist when the detailed-history preference is enabled.  A busy/locked/missing database
     * yields the empty result rather than an error.</p>
     *
     * @param sinceId exclusive lower bound on the event id (0 for the oldest retained events).
     * @param limit maximum events to return, clamped to 1..500.
     * @param callsOnly true to restrict to call traffic (voice/encrypted/data calls and pages - see
     *        {@link #CALL_EVENT_TYPE_CODES}), false for all event types.
     * @return result map: {@code {events:[...], lastId:<max id returned, or sinceId when empty>}}.
     */
    public Map<String,Object> recentEvents(long sinceId, int limit, boolean callsOnly)
    {
        List<Map<String,Object>> events = new ArrayList<>();
        long lastId = sinceId;

        Map<String,Object> result = new LinkedHashMap<>();
        result.put("events", events);
        result.put("lastId", lastId);

        if(!Files.isRegularFile(mDatabasePath))
        {
            return result;
        }

        int clamped = Math.max(1, Math.min(500, limit));

        //systemName: the channel's configured system name (e.g. "NSWPSN") the operator sees, joined from the
        //event's receiver context guid to configuration_channel.radres_guid.  sourceAlias: the over-the-air talker
        //alias last captured for the source radio, joined via trunked_identity_scope_context (context -> scope) to
        //the per-radio identity summary (identity_kind_code 2 = RADIO, see TrunkedIdentityPolicy.IDENTITY_KIND_RADIO).
        //Both are correlated scalar subqueries so each event yields exactly one row and an unresolvable join is null.
        String sql = "SELECT v.id, v.observed_at_ms, v.action, v.event_type, v.source_radio_id, v.target_id, " +
                "v.frequency_hz, v.timeslot, v.encrypted, v.resolved_rfss, v.resolved_site, v.resolved_nac, " +
                "v.resolved_wacn, v.resolved_system_id, v.resolved_channel_name, " +
                "(SELECT cc.system_name FROM configuration_channel cc WHERE cc.radres_guid = v.guid LIMIT 1) " +
                "AS system_name, " +
                "(SELECT tis.last_talker_alias FROM trunked_identity_scope_context tsc " +
                "JOIN trunked_identity_summary tis ON tis.scope_id = tsc.scope_id AND tis.identity_kind_code = 2 " +
                "AND tis.identity_id = v.source_radio_id " +
                "WHERE tsc.context_id = v.context_id AND tis.last_talker_alias IS NOT NULL " +
                "ORDER BY tis.last_talker_alias_seen_ms DESC LIMIT 1) AS source_alias, " +
                //patchMembers: the talkgroups patched into this call.  A patched transmission carries the PATCH
                //GROUP as its target_id - a supergroup that is not a talkgroup anyone is scanning - while the real
                //member talkgroups live one row each in activity_event_talkgroup_member, written by
                //P25ActivityLogSchema.insertActivityEventTalkgroupMembers when the target kind is PATCH_GROUP.
                //Without this the supergroup is all a downstream consumer ever sees, and a patched call cannot say
                //which channels actually carried it.  Empty string (not null) for an ordinary call, since the
                //aggregate returns NULL, not an empty string, when there are no
                //member rows — parsePatchMembers handles both, and the null
                //branch is the one that actually fires.
                "(SELECT group_concat(m.talkgroup_id) FROM activity_event_talkgroup_member m " +
                "WHERE m.event_id = v.id) AS patch_members " +
                "FROM p25_activity_event_resolved v WHERE v.id > ?" +
                (callsOnly ? " AND v.event_type_code IN (" + CALL_EVENT_TYPE_CODES + ")" : "") +
                " ORDER BY v.id ASC LIMIT " + clamped;

        try(Connection connection = openReadOnly();
            PreparedStatement statement = connection.prepareStatement(sql))
        {
            statement.setLong(1, sinceId);

            try(ResultSet results = statement.executeQuery())
            {
                while(results.next())
                {
                    long id = results.getLong(1);
                    lastId = Math.max(lastId, id);

                    Map<String,Object> event = new LinkedHashMap<>();
                    event.put("id", id);
                    event.put("atMs", results.getObject(2));
                    event.put("action", results.getObject(3));
                    event.put("eventType", results.getObject(4));
                    event.put("source", results.getObject(5));
                    event.put("target", results.getObject(6));
                    event.put("frequencyHz", results.getObject(7));
                    event.put("timeslot", results.getObject(8));
                    Object encrypted = results.getObject(9);
                    event.put("encrypted", encrypted != null ? ((Number)encrypted).intValue() != 0 : null);
                    event.put("rfss", results.getObject(10));
                    event.put("site", results.getObject(11));
                    event.put("nac", results.getObject(12));
                    event.put("wacn", results.getObject(13));
                    event.put("systemId", results.getObject(14));
                    event.put("channelName", results.getObject(15));
                    event.put("systemName", results.getObject(16));
                    event.put("sourceAlias", results.getObject(17));
                    event.put("patchMembers", parsePatchMembers(results.getString(18)));
                    events.add(event);
                }
            }

            result.put("lastId", lastId);
        }
        catch(Exception e)
        {
            //Busy/locked/missing database - report the empty result so the caller retries on the next poll,
            //but SAY SO. Returning a bare empty list made a persistently locked or corrupt activity database
            //indistinguishable from a quiet network: the agent saw a valid 200 with no events, logged nothing,
            //and activity shipping stopped silently and indefinitely with no evidence at either end.
            mLog.debug("Recent-events lookup failed - returning empty result", e);
            events.clear();
            result.put("lastId", sinceId);
            result.put("error", e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
        }

        return result;
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

        //Close the connection ourselves if the PRAGMA setup throws.  The caller writes
        //try(Connection c = openReadOnly()), which never binds the resource when the initializer throws — so the
        //connection leaked exactly when the database was locked, corrupt or mid-migration, which is the failure this
        //class is built to tolerate.  Callers swallow the exception and return an empty result, so the node agent just
        //retries on its next poll and leaks another connection plus SQLite file handle: fd exhaustion over hours with
        //only a debug log line as evidence.
        try
        {
            try(Statement statement = connection.createStatement())
            {
                // A SHORT wait, not the writer's 30s. These are read-only
                // lookups served on a bounded HTTP pool, and the agent gives up
                // after 4s — a handler still waiting at 30s is holding a thread
                // for a client that stopped listening 26 seconds ago.
                statement.execute("PRAGMA busy_timeout=" + READ_BUSY_TIMEOUT_MILLISECONDS);
                statement.execute("PRAGMA query_only=ON");
            }
        }
        catch(Exception e)
        {
            try
            {
                connection.close();
            }
            catch(Exception suppressed)
            {
                e.addSuppressed(suppressed);
            }

            throw e;
        }

        return connection;
    }
}
