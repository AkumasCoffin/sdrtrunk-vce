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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read-only lookups against the stats/activity SQLite database that project the deep P25 site metadata SDR-Trunk
 * has stabilized (the {@code p25_site_*} summary tables) into a flat, ingest-ready JSON shape.  Modeled on
 * {@link ControlActivityLookup}: every query opens a short-lived read-only connection ({@code PRAGMA query_only=ON},
 * bounded busy timeout) because the activity writer owns the database.  A busy/locked/missing database yields an
 * empty result rather than an error.
 *
 * <p><b>Ingest contract</b> - {@link #siteSnapshots()} returns {@code {"sites":[ <site> ]}} where each {@code <site>}
 * is one known P25 site (a {@code p25_site_snapshot} row with a non-null site id).  The node agent forwards the
 * {@code sites} array verbatim to the site's {@code POST /api/node-ingest/site-snapshots}.  Every {@code <site>}
 * object has the following fields (a field is {@code null}/absent only when the underlying column is null - none are
 * invented; the source view/table for each is named):</p>
 *
 * <pre>
 * {
 *   // ---- identity (p25_site_snapshot s, joined) ----
 *   "guid":            String,   // s.guid (receiver GUID - stable per physical site generation)
 *   "systemId":        int|null, // p25_system.system_id  (numeric P25 system id, via s.system_key)
 *   "wacn":            int|null, // p25_system.wacn        (via s.system_key)
 *   "systemName":      String|null, // configuration_channel.system_name via radres_guid=s.guid (friendly, joined)
 *   "siteId":          int|null, // s.site
 *   "rfss":            int|null, // s.rfss
 *   "nac":             int|null, // s.nac
 *   "lra":             int|null, // s.lra
 *   "channelName":     String|null, // s.channel_name (site's own configured channel/site name)
 *   "observationCount":int|null, // s.observation_count
 *   "firstSeenMs":     long|null, // s.first_seen_ms
 *   "lastSeenMs":      long|null, // s.last_seen_ms  (site "updated" timestamp, epoch ms)
 *
 *   // ---- control channel (p25_site_snapshot + p25_site_channel_summary) ----
 *   "controlFrequencyMhz": double|null, // s.current_control_hz / 1e6
 *   "controlLcn":          String|null, // p25_site_channel_summary.channel_key where downlink_hz=current_control_hz
 *
 *   // ---- site status flags (p25_site_snapshot) ----
 *   "status": {
 *     "dataService":         bool|null, // s.data_service
 *     "dataAccess":          String|null, // s.data_access
 *     "voiceService":        bool|null, // s.voice_service
 *     "registrationService": bool|null, // s.registration_service
 *     "tdma":                bool|null, // s.tdma
 *     "wuidLeaseMinutes":    int|null, // s.wuid_lease_minutes
 *     "mfid":                int|null, // s.mfid
 *     "microSlots":          int|null, // s.micro_slots
 *     "broadcastClockMs":    long|null // s.broadcast_clock_ms
 *   },
 *
 *   // ---- affiliated/registered radios (p25_radio_affiliation, system-scoped) ----
 *   "affiliatedRadioCount": int, // COUNT(DISTINCT radio_id) WHERE system_key=s.system_key (0 when none/unknown)
 *
 *   // ---- channels (p25_site_channel_summary c + p25_site_channel_tag_summary) ----
 *   "channels": [ {
 *       "type":         String,   // derived from tags: primary_control|alternate_control|control|data|
 *                                 //   base_station|conventional|configured|traffic (default traffic)
 *       "tags":         [String], // raw ChannelTag names from p25_site_channel_tag_summary (may be empty)
 *       "lcn":          String|null, // c.channel_key (P25 "band-number" logical channel number)
 *       "frequencyMhz": double|null, // c.downlink_hz / 1e6
 *       "uplinkMhz":    double|null, // c.uplink_hz / 1e6
 *       "slot":         int|null, // c.timeslots (P25p2 TDMA slot count; null/1 for FDMA)
 *       "tdma":         bool|null, // c.tdma
 *       "lastSeenMs":   long|null  // c.last_seen_ms
 *   } ],
 *
 *   // ---- neighbors (p25_site_neighbor_summary) ----
 *   //  NOTE: the neighbor table does NOT persist NAC, so a neighbor has no "nac" field.
 *   "neighbors": [ {
 *       "systemId":            int|null, // system_id
 *       "rfss":                int|null, // rfss
 *       "siteId":              int|null, // site
 *       "lra":                 int|null, // lra
 *       "controlFrequencyMhz": double|null, // downlink_hz / 1e6
 *       "status":              String|null  // status
 *   } ],
 *
 *   // ---- frequency bands (p25_site_frequency_band_summary) ----
 *   "bands": [ {
 *       "bandId":       int|null, // band
 *       "baseMhz":      double|null, // base_hz / 1e6
 *       "spacingKhz":   double|null, // spacing_hz / 1e3
 *       "txOffsetMhz":  double|null, // transmit_offset_hz / 1e6
 *       "bandwidthKhz": double|null, // bandwidth / 1e3
 *       "tdma":         bool|null, // tdma
 *       "timeslots":    int|null  // timeslots
 *   } ],
 *
 *   // ---- signal/decode quality (p25_control_channel_quality, latest bucket for the site GUID) ----
 *   //  NOTE: no bit-error-rate (BER) column is stored; decodeHealthPct is the decode %, signalDbfs the RSSI proxy.
 *   "quality": {
 *     "observedAtMs":       long|null,
 *     "decodeHealthPct":    double|null, // decode_health_pct
 *     "signalDbfs":         double|null, // signal_dbfs
 *     "averageSignalDbfs":  double|null, // average_signal_dbfs
 *     "minimumSignalDbfs":  double|null, // minimum_signal_dbfs
 *     "maximumSignalDbfs":  double|null, // maximum_signal_dbfs
 *     "validFrames":        long|null, // valid_frames
 *     "invalidFrames":      long|null, // invalid_frames
 *     "correctedBits":      long|null, // corrected_bits
 *     "syncLossBits":       long|null, // sync_loss_bits
 *     "droppedBits":        long|null, // dropped_bits
 *     "lastValidDecodeMs":  long|null  // last_valid_decode_ms
 *   } | null
 * }
 * </pre>
 */
public class ControlSiteLookup
{
    private static final Logger mLog = LoggerFactory.getLogger(ControlSiteLookup.class);

    private final Path mDatabasePath;

    /**
     * Constructs the lookup.
     * @param databasePath to the shared sdrtrunk.sqlite database.
     */
    public ControlSiteLookup(Path databasePath)
    {
        mDatabasePath = databasePath;
    }

    /**
     * Projects every known P25 site into the ingest contract described in the class doc.  A busy/locked/missing
     * database yields {@code {"sites":[]}} rather than an error.
     *
     * @return result map: {@code {sites:[ <site> ]}}.
     */
    public Map<String,Object> siteSnapshots()
    {
        List<Map<String,Object>> sites = new ArrayList<>();
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("sites", sites);

        if(!Files.isRegularFile(mDatabasePath))
        {
            return result;
        }

        //One row per known P25 site.  system_id/wacn come from p25_system (joined by system_key); the friendly
        //system name and the control-channel LCN are correlated scalar subqueries so each site yields exactly one
        //row and any unresolvable join is null - the same pattern the activity lookup uses.
        String sql = "SELECT s.guid, s.system_key, sys.system_id, sys.wacn, s.nac, s.rfss, s.site, s.lra, " +
                "s.channel_name, s.current_control_hz, s.data_service, s.data_access, s.voice_service, " +
                "s.registration_service, s.tdma, s.wuid_lease_minutes, s.mfid, s.micro_slots, s.broadcast_clock_ms, " +
                "s.observation_count, s.first_seen_ms, s.last_seen_ms, " +
                "(SELECT cc.system_name FROM configuration_channel cc WHERE cc.radres_guid = s.guid " +
                "ORDER BY cc.sort_order LIMIT 1) AS system_name, " +
                "(SELECT csum.channel_key FROM p25_site_channel_summary csum WHERE csum.guid = s.guid " +
                "AND csum.downlink_hz IS NOT NULL AND csum.downlink_hz = s.current_control_hz LIMIT 1) AS control_lcn, " +
                "(SELECT COUNT(DISTINCT aff.radio_id) FROM p25_radio_affiliation aff " +
                "WHERE aff.system_key = s.system_key) AS affiliated_radio_count " +
                "FROM p25_site_snapshot s " +
                "LEFT JOIN p25_system sys ON sys.system_key = s.system_key " +
                "WHERE s.site IS NOT NULL " +
                "ORDER BY s.rfss, s.site";

        try(Connection connection = openReadOnly();
            PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet results = statement.executeQuery())
        {
            while(results.next())
            {
                String guid = results.getString("guid");

                Map<String,Object> site = new LinkedHashMap<>();
                site.put("guid", guid);
                site.put("systemId", nullableInt(results, "system_id"));
                site.put("wacn", nullableInt(results, "wacn"));
                site.put("systemName", results.getString("system_name"));
                site.put("siteId", nullableInt(results, "site"));
                site.put("rfss", nullableInt(results, "rfss"));
                site.put("nac", nullableInt(results, "nac"));
                site.put("lra", nullableInt(results, "lra"));
                site.put("channelName", results.getString("channel_name"));
                site.put("observationCount", nullableInt(results, "observation_count"));
                site.put("firstSeenMs", nullableLong(results, "first_seen_ms"));
                site.put("lastSeenMs", nullableLong(results, "last_seen_ms"));

                Long controlHz = nullableLong(results, "current_control_hz");
                site.put("controlFrequencyMhz", mhz(controlHz));
                site.put("controlLcn", results.getString("control_lcn"));

                Map<String,Object> status = new LinkedHashMap<>();
                status.put("dataService", nullableBool(results, "data_service"));
                status.put("dataAccess", results.getString("data_access"));
                status.put("voiceService", nullableBool(results, "voice_service"));
                status.put("registrationService", nullableBool(results, "registration_service"));
                status.put("tdma", nullableBool(results, "tdma"));
                status.put("wuidLeaseMinutes", nullableInt(results, "wuid_lease_minutes"));
                status.put("mfid", nullableInt(results, "mfid"));
                status.put("microSlots", nullableInt(results, "micro_slots"));
                status.put("broadcastClockMs", nullableLong(results, "broadcast_clock_ms"));
                site.put("status", status);

                long affiliated = results.getLong("affiliated_radio_count");
                site.put("affiliatedRadioCount", results.wasNull() ? 0L : affiliated);

                site.put("channels", channels(connection, guid));
                site.put("neighbors", neighbors(connection, guid));
                site.put("bands", bands(connection, guid));
                site.put("quality", quality(connection, guid));

                sites.add(site);
            }
        }
        catch(Exception e)
        {
            //Busy/locked/missing database - report the empty result so the caller retries on the next poll.
            mLog.debug("Site-snapshot lookup failed - returning empty result", e);
            sites.clear();
        }

        return result;
    }

    /**
     * Site channel inventory with the derived channel type from its stabilized service tags.
     */
    private List<Map<String,Object>> channels(Connection connection, String guid) throws Exception
    {
        List<Map<String,Object>> channels = new ArrayList<>();

        String sql = "SELECT c.channel_key, c.descriptor, c.downlink_hz, c.uplink_hz, c.tdma, c.timeslots, " +
                "c.last_seen_ms, (SELECT group_concat(t.tag) FROM p25_site_channel_tag_summary t " +
                "WHERE t.guid = c.guid AND t.channel_key = c.channel_key) AS tags " +
                "FROM p25_site_channel_summary c WHERE c.guid = ? ORDER BY c.downlink_hz";

        try(PreparedStatement statement = connection.prepareStatement(sql))
        {
            statement.setString(1, guid);

            try(ResultSet results = statement.executeQuery())
            {
                while(results.next())
                {
                    String rawTags = results.getString("tags");
                    List<String> tags = new ArrayList<>();

                    if(rawTags != null && !rawTags.isBlank())
                    {
                        for(String tag : rawTags.split(","))
                        {
                            if(!tag.isBlank())
                            {
                                tags.add(tag.trim());
                            }
                        }
                    }

                    Map<String,Object> channel = new LinkedHashMap<>();
                    channel.put("type", channelType(tags));
                    channel.put("tags", tags);
                    channel.put("lcn", results.getString("channel_key"));
                    channel.put("frequencyMhz", mhz(nullableLong(results, "downlink_hz")));
                    channel.put("uplinkMhz", mhz(nullableLong(results, "uplink_hz")));
                    channel.put("slot", nullableInt(results, "timeslots"));
                    channel.put("tdma", nullableBool(results, "tdma"));
                    channel.put("lastSeenMs", nullableLong(results, "last_seen_ms"));
                    channels.add(channel);
                }
            }
        }

        return channels;
    }

    /**
     * Neighboring sites advertised by this site.  NAC is not persisted for neighbors, so no nac field is emitted.
     */
    private List<Map<String,Object>> neighbors(Connection connection, String guid) throws Exception
    {
        List<Map<String,Object>> neighbors = new ArrayList<>();

        String sql = "SELECT system_id, rfss, site, lra, downlink_hz, status FROM p25_site_neighbor_summary " +
                "WHERE guid = ? ORDER BY rfss, site";

        try(PreparedStatement statement = connection.prepareStatement(sql))
        {
            statement.setString(1, guid);

            try(ResultSet results = statement.executeQuery())
            {
                while(results.next())
                {
                    Map<String,Object> neighbor = new LinkedHashMap<>();
                    neighbor.put("systemId", nullableInt(results, "system_id"));
                    neighbor.put("rfss", nullableInt(results, "rfss"));
                    neighbor.put("siteId", nullableInt(results, "site"));
                    neighbor.put("lra", nullableInt(results, "lra"));
                    neighbor.put("controlFrequencyMhz", mhz(nullableLong(results, "downlink_hz")));
                    neighbor.put("status", results.getString("status"));
                    neighbors.add(neighbor);
                }
            }
        }

        return neighbors;
    }

    /**
     * P25 frequency band plans advertised by this site.
     */
    private List<Map<String,Object>> bands(Connection connection, String guid) throws Exception
    {
        List<Map<String,Object>> bands = new ArrayList<>();

        String sql = "SELECT band, base_hz, spacing_hz, transmit_offset_hz, bandwidth, tdma, timeslots " +
                "FROM p25_site_frequency_band_summary WHERE guid = ? ORDER BY band";

        try(PreparedStatement statement = connection.prepareStatement(sql))
        {
            statement.setString(1, guid);

            try(ResultSet results = statement.executeQuery())
            {
                while(results.next())
                {
                    Map<String,Object> band = new LinkedHashMap<>();
                    band.put("bandId", nullableInt(results, "band"));
                    band.put("baseMhz", mhz(nullableLong(results, "base_hz")));
                    band.put("spacingKhz", khz(nullableLong(results, "spacing_hz")));
                    band.put("txOffsetMhz", mhz(nullableLong(results, "transmit_offset_hz")));
                    band.put("bandwidthKhz", khz(nullableLong(results, "bandwidth")));
                    band.put("tdma", nullableBool(results, "tdma"));
                    band.put("timeslots", nullableInt(results, "timeslots"));
                    bands.add(band);
                }
            }
        }

        return bands;
    }

    /**
     * Latest control-channel decode/signal quality bucket for the site GUID, or null when none has been recorded.
     */
    private Map<String,Object> quality(Connection connection, String guid) throws Exception
    {
        String sql = "SELECT observed_at_ms, signal_dbfs, average_signal_dbfs, minimum_signal_dbfs, " +
                "maximum_signal_dbfs, decode_health_pct, valid_frames, invalid_frames, corrected_bits, " +
                "sync_loss_bits, dropped_bits, last_valid_decode_ms FROM p25_control_channel_quality " +
                "WHERE guid = ? ORDER BY observed_at_ms DESC LIMIT 1";

        try(PreparedStatement statement = connection.prepareStatement(sql))
        {
            statement.setString(1, guid);

            try(ResultSet results = statement.executeQuery())
            {
                if(!results.next())
                {
                    return null;
                }

                Map<String,Object> quality = new LinkedHashMap<>();
                quality.put("observedAtMs", nullableLong(results, "observed_at_ms"));
                quality.put("decodeHealthPct", nullableDouble(results, "decode_health_pct"));
                quality.put("signalDbfs", nullableDouble(results, "signal_dbfs"));
                quality.put("averageSignalDbfs", nullableDouble(results, "average_signal_dbfs"));
                quality.put("minimumSignalDbfs", nullableDouble(results, "minimum_signal_dbfs"));
                quality.put("maximumSignalDbfs", nullableDouble(results, "maximum_signal_dbfs"));
                quality.put("validFrames", nullableLong(results, "valid_frames"));
                quality.put("invalidFrames", nullableLong(results, "invalid_frames"));
                quality.put("correctedBits", nullableLong(results, "corrected_bits"));
                quality.put("syncLossBits", nullableLong(results, "sync_loss_bits"));
                quality.put("droppedBits", nullableLong(results, "dropped_bits"));
                quality.put("lastValidDecodeMs", nullableLong(results, "last_valid_decode_ms"));
                return quality;
            }
        }
    }

    /**
     * Maps the non-exclusive site channel service tags to a single presentation type, in descending priority.
     * Falls back to "traffic" - a site channel with no control/base tag is a voice/data traffic channel.
     */
    private static String channelType(List<String> tags)
    {
        if(tags.contains("CURRENT_CONTROL"))
        {
            return "primary_control";
        }

        if(tags.contains("ALTERNATE_CONTROL"))
        {
            return "alternate_control";
        }

        if(tags.contains("CONTROL"))
        {
            return "control";
        }

        if(tags.contains("DATA_ANNOUNCED"))
        {
            return "data";
        }

        if(tags.contains("CWID"))
        {
            return "base_station";
        }

        if(tags.contains("CONVENTIONAL"))
        {
            return "conventional";
        }

        if(tags.contains("CONFIGURED") && !tags.contains("VOICE") && !tags.contains("DATA"))
        {
            return "configured";
        }

        return "traffic";
    }

    private static Double mhz(Long hz)
    {
        return hz != null ? hz / 1_000_000.0d : null;
    }

    private static Double khz(Long hz)
    {
        return hz != null ? hz / 1_000.0d : null;
    }

    private static Integer nullableInt(ResultSet results, String column) throws Exception
    {
        int value = results.getInt(column);
        return results.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet results, String column) throws Exception
    {
        long value = results.getLong(column);
        return results.wasNull() ? null : value;
    }

    private static Double nullableDouble(ResultSet results, String column) throws Exception
    {
        double value = results.getDouble(column);
        return results.wasNull() ? null : value;
    }

    private static Boolean nullableBool(ResultSet results, String column) throws Exception
    {
        int value = results.getInt(column);
        return results.wasNull() ? null : value != 0;
    }

    /**
     * Opens a short-lived read-only connection with a bounded busy timeout.  Mirrors {@link ControlActivityLookup}
     * so lookups never write and never block the activity writer for long.
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
