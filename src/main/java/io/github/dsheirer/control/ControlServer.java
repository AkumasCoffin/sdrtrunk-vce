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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.application.ApplicationInfo;
import io.github.dsheirer.channel.metadata.ChannelMetadata;
import io.github.dsheirer.channel.metadata.ChannelMetadataModel;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.configuration.ConfigurationState;
import io.github.dsheirer.channel.quality.ControlChannelQualitySnapshot;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.controller.channel.ChannelException;
import io.github.dsheirer.controller.channel.ChannelModel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.configuration.ConfigurationSnapshotDatabaseStore;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerController;
import io.github.dsheirer.source.tuner.airspy.AirspySampleRate;
import io.github.dsheirer.source.tuner.airspy.AirspyTunerController;
import io.github.dsheirer.source.tuner.airspy.hf.AirspyHfSampleRate;
import io.github.dsheirer.source.tuner.airspy.hf.AirspyHfTunerController;
import io.github.dsheirer.source.tuner.airspy.hf.Attenuation;
import io.github.dsheirer.source.tuner.hackrf.HackRFTunerController;
import io.github.dsheirer.source.tuner.hydrasdr.HydraSdrTunerController;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.source.tuner.rtl.EmbeddedTuner;
import io.github.dsheirer.source.tuner.rtl.RTL2832TunerController;
import io.github.dsheirer.source.tuner.rtl.e4k.E4KEmbeddedTuner;
import io.github.dsheirer.source.tuner.rtl.fc0013.FC0013EmbeddedTuner;
import io.github.dsheirer.source.tuner.rtl.r8x.R8xEmbeddedTuner;
import io.github.dsheirer.source.tuner.sdrplay.RspSampleRate;
import io.github.dsheirer.source.tuner.sdrplay.RspTunerController;
import io.github.dsheirer.stats.activity.P25ActivityLogService;
import io.github.dsheirer.stats.activity.P25ActivityLogStatus;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Headless control server exposing a loopback-only REST API (control-port) plus a spectrum WebSocket (control-port + 1)
 * for remote monitoring and control of a headless SDR-Trunk instance.  All REST requests require a
 * {@code Authorization: Bearer &lt;token&gt;} header validated with a constant-time compare.
 */
public class ControlServer
{
    private static final Logger mLog = LoggerFactory.getLogger(ControlServer.class);
    private static final String CONTENT_TYPE_JSON = "application/json";

    private final TunerManager mTunerManager;
    private final ConfigurationManager mConfigurationManager;
    private final P25ActivityLogService mActivityLogService;
    private final io.github.dsheirer.preference.UserPreferences mUserPreferences;
    private final BooleanSupplier mDecodeReadyGate;
    private final boolean mHeadless;
    private final int mPort;
    private final String mToken;
    private final Path mDatabasePath;
    private final ControlActivityLookup mActivityLookup;
    private final ControlSiteLookup mSiteLookup;
    private final ObjectMapper mMapper = new ObjectMapper();

    private HttpServer mHttpServer;
    private ControlWebSocketServer mWsServer;
    private ExecutorService mExecutor;
    private EventBuffer mEventBuffer;
    private long mStartTime;

    //---------------------------------------------------------------------------------------------------------------
    // Self-healing auto-start (headless only): a periodic guarded pass that (re)starts auto-start channels that are
    // not processing, and force-restarts a trunking channel that has been PROCESSING but never locked (IDLE) past a
    // threshold. Conservative + rate-limited so it never churns a channel that is merely quiet.
    //---------------------------------------------------------------------------------------------------------------

    /** Interval between self-heal passes. */
    private static final long SELF_HEAL_INTERVAL_SECONDS = 30;

    /** A trunking channel PROCESSING but never locked (never CONTROL/CALL) for at least this long is restarted once. */
    private static final long IDLE_RESTART_THRESHOLD_MS = 90_000L;

    /** Minimum time between forced restarts of the SAME channel, so a persistently-unlucky channel isn't churned. */
    private static final long IDLE_RESTART_COOLDOWN_MS = 300_000L;

    /**
     * Trunking decoders whose healthy resting state is CONTROL (locked on the control channel).  The IDLE force-
     * restart heuristic applies ONLY to these - a conventional channel (NBFM/AM) sits IDLE at rest with no traffic,
     * which is normal and must never trigger a restart.
     */
    private static final Set<DecoderType> CONTROL_LOCKING_DECODERS =
            java.util.EnumSet.of(DecoderType.P25_PHASE1, DecoderType.P25_PHASE2, DecoderType.DMR);

    private ScheduledExecutorService mSelfHealExecutor;
    private ScheduledFuture<?> mSelfHealFuture;

    /** channelId -&gt; epoch ms first observed PROCESSING-but-not-locked in the current unlocked streak (else absent). */
    private final Map<Integer,Long> mChannelUnlockedSince = new ConcurrentHashMap<>();

    /** channelId -&gt; epoch ms of the last forced restart, for cooldown rate-limiting. */
    private final Map<Integer,Long> mChannelLastRestart = new ConcurrentHashMap<>();

    /**
     * Channel NAMES (trimmed) the node agent has deliberately stopped for low decode health.  Self-heal branch (a)
     * skips these - without this, any agent stop of an autoStart channel is undone within one 30s sweep and the
     * agent's management loop degenerates into a 30-second restart fight.  Runtime-only by design: names, not ids
     * (ids are reassigned every reload), never persisted, and cleared whenever a reload/import rebuilds the channel
     * set and restarts everything anyway - the agent re-evaluates from live state and re-stops after its own dwell.
     */
    private final Set<String> mSelfHealSuppressed = ConcurrentHashMap.newKeySet();

    /** channel name -&gt; epoch ms of the last "suppressed, leaving stopped" notice, to keep the 30s sweep log quiet. */
    private final Map<String,Long> mSuppressedNoticeAt = new ConcurrentHashMap<>();

    /** Interval between repeated "suppressed, leaving stopped" notices for the same channel. */
    private static final long SUPPRESSED_NOTICE_INTERVAL_MS = 600_000L;

    /**
     * Last gain value applied to each tuner via this control API, keyed by tuner id.  Most SDR-Trunk tuner
     * controllers do not expose a getter for the current composite gain (it is persisted only in the tuner
     * configuration), so buildTunerList reports the last value set through this server as a fallback.  A value
     * only appears here after {@code POST /tuners/{id}/gain} has been called at least once since startup.
     */
    private final Map<String,Object> mLastGain = new ConcurrentHashMap<>();

    /**
     * Latest control-channel quality snapshot per configured channel, keyed by the Channel object the
     * {@link ControlChannelQualityMonitor} was attached to.  Fed by {@link #mQualityListener} (registered on the
     * ChannelProcessingManager in {@link #start()}); read by buildChannelList / buildActiveCalls to emit each
     * channel's live decode-health % ({@code syncPercent}), signal level ({@code signalDbfs}), how long it has been
     * decoding ({@code decodingForMs}) and how many frames back the health figure ({@code syncFrames}).  Only STANDARD
     * (configured/control) channels get a monitor — dynamically-allocated traffic channels do not, so a live voice
     * grant has no snapshot here.
     */
    private final Map<Channel,ControlChannelQualitySnapshot> mQualityByChannel = new ConcurrentHashMap<>();

    /** Caches each incoming quality snapshot by its Channel. */
    private final Listener<ControlChannelQualitySnapshot> mQualityListener = snapshot -> {
        if(snapshot != null && snapshot.channel() != null)
        {
            mQualityByChannel.put(snapshot.channel(), snapshot);
        }
    };

    /** A quality snapshot older than this (channel stopped / no heartbeat) is treated as absent. */
    private static final long QUALITY_FRESHNESS_MS = 5_000L;

    /**
     * Constructs the control server.
     * @param tunerManager for tuner discovery and control.
     * @param configurationManager for channel/alias/stream discovery, control and configuration reload.
     * @param activityLogService for P25 activity log status reporting.
     * @param userPreferences for database path and JMBE library resolution.
     * @param decodeReadyGate headless decode readiness gate (calibration + JMBE), or null to disable gating.
     * @param headless whether the application is running headless.
     * @param port REST port (WebSocket binds to port + 1).
     * @param token bearer token from env SDRTRUNK_CONTROL_TOKEN.
     */
    public ControlServer(TunerManager tunerManager, ConfigurationManager configurationManager,
                         P25ActivityLogService activityLogService,
                         io.github.dsheirer.preference.UserPreferences userPreferences,
                         BooleanSupplier decodeReadyGate, boolean headless, int port, String token)
    {
        mTunerManager = tunerManager;
        mConfigurationManager = configurationManager;
        mActivityLogService = activityLogService;
        mUserPreferences = userPreferences;
        mDecodeReadyGate = decodeReadyGate;
        mHeadless = headless;
        mPort = port;
        mToken = token;
        mDatabasePath = SdrTrunkDatabasePath.getDatabasePath(userPreferences);
        mActivityLookup = new ControlActivityLookup(mDatabasePath);
        mSiteLookup = new ControlSiteLookup(mDatabasePath);
    }

    /**
     * Object mapper shared with the WebSocket server.
     */
    public ObjectMapper getMapper()
    {
        return mMapper;
    }

    /**
     * Tuner manager for WebSocket spectrum tuner resolution.
     */
    public TunerManager getTunerManager()
    {
        return mTunerManager;
    }

    /**
     * Starts the REST server (loopback) and the spectrum WebSocket server (loopback, port + 1).
     */
    public void start() throws IOException
    {
        if(mToken == null || mToken.isEmpty())
        {
            mLog.warn("Control server starting WITHOUT a token (env SDRTRUNK_CONTROL_TOKEN not set) - all requests " +
                    "will require an empty bearer token");
        }

        mStartTime = System.currentTimeMillis();

        mEventBuffer = new EventBuffer(mConfigurationManager.getAliasModel());
        mConfigurationManager.getChannelProcessingManager().addDecodeEventListener(mEventBuffer);
        //Live per-channel decode-health % + signal level for /channels + /status activeCalls.
        mConfigurationManager.getChannelProcessingManager().addControlChannelQualityListener(mQualityListener);

        // Four threads was exactly the number of concurrent request streams the
        // node agent already generates on its own (statusLoop's four sequential
        // calls at 1Hz while a staff member watches Live, activityship every
        // 4s, siteship every 60s, and a per-call /activity/call-site). Any
        // slow handler therefore parked the whole server: a maintenance VACUUM
        // takes an exclusive SQLite lock, each blocked reader then sits on its
        // busy timeout, and com.sun.net.httpserver does NOT abort a handler
        // when the client gives up — so the agent's 4s timeout expired while
        // the thread stayed parked. The node reported components.sdrtrunk
        // "unreachable" with nothing server-side to explain it.
        mExecutor = Executors.newFixedThreadPool(12);

        mHttpServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), mPort), 0);
        mHttpServer.setExecutor(mExecutor);
        mHttpServer.createContext("/status", this::handleStatus);
        mHttpServer.createContext("/tuners", this::handleTuners);
        mHttpServer.createContext("/channels", this::handleChannels);
        mHttpServer.createContext("/events", this::handleEvents);
        mHttpServer.createContext("/playlist", this::handleConfig);
        mHttpServer.createContext("/config", this::handleConfig);
        mHttpServer.createContext("/activity", this::handleActivity);
        mHttpServer.createContext("/site", this::handleSite);
        mHttpServer.start();

        mWsServer = new ControlWebSocketServer(new InetSocketAddress(InetAddress.getLoopbackAddress(), mPort + 1),
                this, mToken);
        mWsServer.start();

        //Headless self-healing auto-start timer. It self-skips while the decode-readiness gate is closed (first-run
        //calibration/JMBE) and while not headless, so it is safe to arm here even though it starts before first-run
        //setup completes.
        if(mHeadless)
        {
            mSelfHealExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sdrtrunk-control-selfheal");
                t.setDaemon(true);
                return t;
            });
            mSelfHealFuture = mSelfHealExecutor.scheduleWithFixedDelay(this::selfHealChannels,
                    SELF_HEAL_INTERVAL_SECONDS, SELF_HEAL_INTERVAL_SECONDS, TimeUnit.SECONDS);
            mLog.info("Control server self-healing auto-start armed (every " + SELF_HEAL_INTERVAL_SECONDS + "s)");
        }

        mLog.info("Control server started - REST on 127.0.0.1:" + mPort + ", spectrum WS on 127.0.0.1:" + (mPort + 1));
    }

    /**
     * Stops the REST + WebSocket servers and detaches all spectrum streamers.
     */
    public void stop()
    {
        mLog.info("Stopping control server ...");

        if(mSelfHealFuture != null)
        {
            mSelfHealFuture.cancel(true);
            mSelfHealFuture = null;
        }

        if(mSelfHealExecutor != null)
        {
            mSelfHealExecutor.shutdownNow();
            mSelfHealExecutor = null;
        }

        if(mWsServer != null)
        {
            try
            {
                mWsServer.detachAll();
                mWsServer.stop(1000);
            }
            catch(Exception e)
            {
                mLog.warn("Error stopping spectrum WebSocket server", e);
            }

            mWsServer = null;
        }

        if(mHttpServer != null)
        {
            mHttpServer.stop(0);
            mHttpServer = null;
        }

        if(mEventBuffer != null)
        {
            mConfigurationManager.getChannelProcessingManager().removeDecodeEventListener(mEventBuffer);
            mEventBuffer = null;
        }

        mConfigurationManager.getChannelProcessingManager().removeControlChannelQualityListener(mQualityListener);
        mQualityByChannel.clear();

        if(mExecutor != null)
        {
            mExecutor.shutdownNow();
            mExecutor = null;
        }
    }

    //---------------------------------------------------------------------------------------------------------------
    // REST handlers
    //---------------------------------------------------------------------------------------------------------------

    private void handleStatus(HttpExchange exchange)
    {
        try
        {
            if(!authorize(exchange))
            {
                return;
            }

            ChannelModel cm = mConfigurationManager.getChannelModel();

            int tunerCount = mTunerManager.getDiscoveredTunerModel().getAvailableTuners().size();
            // Snapshot the model's (JavaFX, non-thread-safe) channel list before iterating
            // off the HTTP pool thread — traffic-channel add/remove + configuration reload mutate
            // it concurrently, which would otherwise throw ConcurrentModificationException.
            List<Channel> channels = new ArrayList<>(cm.getChannels());
            int channelCount = channels.size();
            int processing = 0;

            for(Channel channel : channels)
            {
                if(channel.isProcessing())
                {
                    processing++;
                }
            }

            double load = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();

            if(load < 0)
            {
                load = -1;
            }

            Runtime runtime = Runtime.getRuntime();
            long usedMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
            long maxMB = runtime.maxMemory() / (1024 * 1024);

            Map<String,Object> body = new LinkedHashMap<>();
            body.put("version", ApplicationInfo.getDisplayName() + " " + ApplicationInfo.getVersion());
            body.put("uptimeMs", System.currentTimeMillis() - mStartTime);
            body.put("headless", mHeadless);
            body.put("tuners", tunerCount);
            body.put("channels", channelCount);
            body.put("processing", processing);
            body.put("cpuLoad", load);
            body.put("cores", runtime.availableProcessors());
            body.put("memUsedMB", usedMB);
            body.put("memMaxMB", maxMB);

            // Node readiness: CPU calibration + the JMBE (AMBE/IMBE) voice codec.
            // Both must be present before channels decode voice with audio.
            boolean calibrated = io.github.dsheirer.vector.calibrate.CalibrationManager.getInstance().isCalibrated();
            java.nio.file.Path jmbePath = mUserPreferences != null
                    ? mUserPreferences.getJmbeLibraryPreference().getPathJmbeLibrary() : null;
            boolean jmbeInstalled = jmbePath != null && java.nio.file.Files.exists(jmbePath);
            body.put("calibrated", calibrated);
            body.put("jmbeInstalled", jmbeInstalled);

            sendJson(exchange, 200, body);
        }
        catch(Exception e)
        {
            sendError(exchange, e);
        }
        finally
        {
            exchange.close();
        }
    }

    private void handleTuners(HttpExchange exchange)
    {
        try
        {
            if(!authorize(exchange))
            {
                return;
            }

            String path = exchange.getRequestURI().getPath();

            if(path.equals("/tuners") || path.equals("/tuners/"))
            {
                sendJson(exchange, 200, buildTunerList());
                return;
            }

            //Expect /tuners/{id}/{action}
            String[] parts = path.split("/");

            if(parts.length >= 4)
            {
                String id = URLDecoder.decode(parts[2], StandardCharsets.UTF_8);
                String action = parts[3];
                handleTunerControl(exchange, id, action);
                return;
            }

            sendJson(exchange, 404, error("not found"));
        }
        catch(Exception e)
        {
            sendError(exchange, e);
        }
        finally
        {
            exchange.close();
        }
    }

    private Map<String,Object> buildTunerList()
    {
        List<DiscoveredTuner> tuners = mTunerManager.getDiscoveredTunerModel().getAvailableTuners();
        List<Map<String,Object>> list = new ArrayList<>();

        for(int index = 0; index < tuners.size(); index++)
        {
            DiscoveredTuner dt = tuners.get(index);
            Map<String,Object> entry = new LinkedHashMap<>();
            entry.put("index", index);
            entry.put("id", dt.getId());
            entry.put("tunerClass", String.valueOf(dt.getTunerClass()));
            entry.put("status", String.valueOf(dt.getTunerStatus()));
            entry.put("enabled", dt.isEnabled());
            entry.put("available", dt.isAvailable());

            if(dt.hasTuner())
            {
                Tuner t = dt.getTuner();
                TunerController c = t.getTunerController();
                entry.put("name", t.getPreferredName());
                entry.put("type", t.getTunerType() != null ? t.getTunerType().getLabel() : null);
                entry.put("frequency", c.getFrequency());
                entry.put("sampleRate", c.getSampleRate());
                entry.put("ppm", c.getFrequencyCorrection());

                //Measured frequency error (ppm). c.getPPMFrequencyError() is a transient tuner-tick average that
                //resets to 0 between ticks and, once Auto-PPM converges, sits at ~0 (the offset has been absorbed
                //into the ppm above), so it almost always reads 0.00 in a REST snapshot. Prefer the persistent value
                //the correction loops are actually holding (Auto-PPM baseline delta, else the largest locked-channel
                //AFC residual); fall back to the legacy transient value if the loop hasn't produced one yet.
                double measuredPpmError = c.getTunerFrequencyErrorManager().getMeasuredPPMError();

                if(measuredPpmError == 0.0d)
                {
                    measuredPpmError = c.getPPMFrequencyError();
                }

                entry.put("measuredPpmError", measuredPpmError);

                //Current gain: real value where the controller can read it, else the last value set via this API.
                Object realGain = readCurrentGain(c);
                entry.put("gain", realGain != null ? realGain : mLastGain.get(dt.getId()));

                //Auto-PPM: the tuner's automatic frequency-error correction manager, fed by the
                //decoders' measured error. Report its real enabled state (defaults on).
                entry.put("autoPpm", c.getTunerFrequencyErrorManager().isEnabled());

                //Per-device capabilities so the UI can build correct controls without hardcoding per-type.
                entry.put("capabilities", buildTunerCapabilities(c));
            }
            else
            {
                entry.put("name", null);
                entry.put("type", null);
                entry.put("frequency", 0L);
                entry.put("sampleRate", 0d);
                entry.put("ppm", 0d);
                entry.put("measuredPpmError", 0d);
                entry.put("gain", null);
                entry.put("autoPpm", false);
                entry.put("capabilities", null);
            }

            entry.put("error", dt.hasErrorMessage() ? dt.getErrorMessage() : null);
            list.add(entry);
        }

        Map<String,Object> body = new LinkedHashMap<>();
        body.put("tuners", list);
        return body;
    }

    //---------------------------------------------------------------------------------------------------------------
    // Per-tuner capabilities (device-typed dispatch, mirrors applyGain / applySampleRate)
    //---------------------------------------------------------------------------------------------------------------

    /**
     * Builds a best-effort {@code capabilities} object for a specific tuner controller describing what it can be
     * set to, so the UI can render correct controls instead of hardcoding per device type.  Dispatches on the
     * concrete controller (and, for RTL, the embedded tuner) type - the same dispatch used by {@link #applyGain}
     * and {@link #applySampleRate}.  Each axis is populated only when the device actually has it; anything the
     * controller does not cleanly expose is simply omitted.
     *
     * <p>Top-level fields:</p>
     * <ul>
     *   <li>{@code sampleRates} - array of settable sample rates in Hz (omitted/empty for fixed-rate devices).</li>
     *   <li>{@code gain} - object describing the device's gain model (see below).</li>
     * </ul>
     *
     * <p>The {@code gain.mode} is one of {@code "master"} (single composite axis), {@code "multi"} (independent
     * axes such as LNA + VGA), {@code "toggle"} (a single boolean) or {@code "fixed"}.  For the RTL master gain
     * the {@code masterGainUnit} field disambiguates whether the {@code /gain} endpoint's {@code gain} number is
     * a raw device value, a dB figure, or a 0-based step index - the value is always snapped to the nearest entry
     * in the {@code masterGain} list.</p>
     */
    private Map<String,Object> buildTunerCapabilities(TunerController c)
    {
        Map<String,Object> caps = new LinkedHashMap<>();

        try
        {
            caps.put("sampleRates", buildSampleRates(c));
        }
        catch(Exception e)
        {
            //best effort - omit on failure
        }

        try
        {
            Map<String,Object> gain = buildGainCapabilities(c);

            if(gain != null)
            {
                caps.put("gain", gain);
            }
        }
        catch(Exception e)
        {
            //best effort - omit on failure
        }

        return caps;
    }

    /**
     * Best-effort list of the device's settable sample rates in Hz.  Returns an empty list when the device does not
     * expose an enumerable set.  Mirrors {@link #applySampleRate} exactly so the advertised rates are the same ones
     * the endpoint will accept.
     */
    private List<Long> buildSampleRates(TunerController c)
    {
        List<Long> rates = new ArrayList<>();

        if(c instanceof RTL2832TunerController)
        {
            for(RTL2832TunerController.SampleRate sr : RTL2832TunerController.SampleRate.values())
            {
                rates.add((long)sr.getRate());
            }
        }
        else if(c instanceof HackRFTunerController)
        {
            for(HackRFTunerController.HackRFSampleRate sr : HackRFTunerController.HackRFSampleRate.VALID_SAMPLE_RATES)
            {
                rates.add((long)sr.getRate());
            }
        }
        else if(c instanceof AirspyTunerController airspy)
        {
            for(AirspySampleRate sr : airspy.getSampleRates())
            {
                rates.add((long)sr.getRate());
            }
        }
        else if(c instanceof HydraSdrTunerController hydra)
        {
            for(io.github.dsheirer.source.tuner.hydrasdr.HydraSdrSampleRate sr : hydra.getSampleRates())
            {
                rates.add((long)sr.getRate());
            }
        }
        else if(c instanceof RspTunerController)
        {
            for(RspSampleRate sr : RspSampleRate.values())
            {
                rates.add(sr.getSampleRate());
            }
        }
        else if(c instanceof AirspyHfTunerController hf)
        {
            for(AirspyHfSampleRate sr : hf.getAvailableSampleRates())
            {
                rates.add((long)sr.getSampleRate());
            }
        }

        return rates;
    }

    /**
     * Best-effort description of the device's gain model.  Returns null when the tuner type has no settable gain
     * via this API.  The axes populated here mirror the ones {@link #applyGain} actually reads for each device.
     */
    private Map<String,Object> buildGainCapabilities(TunerController c)
    {
        //---------------------------------------------------------------------------------- RTL2832 family
        if(c instanceof RTL2832TunerController rtl && rtl.hasEmbeddedTuner())
        {
            EmbeddedTuner embedded = rtl.getEmbeddedTuner();

            if(embedded instanceof R8xEmbeddedTuner)
            {
                //R820T/R828D master gain labels are the raw composite gain values (tenths of a dB); the /gain
                //endpoint snaps the requested {gain} number to the nearest of these - it is NOT a 0-based index.
                Map<String,Object> gain = new LinkedHashMap<>();
                gain.put("mode", "master");
                gain.put("masterGainUnit", "value");
                gain.put("masterGain", enumNumbers(R8xEmbeddedTuner.MasterGain.values()));
                gain.put("agc", true);
                return gain;
            }

            if(embedded instanceof E4KEmbeddedTuner)
            {
                //E4K master gain labels are dB figures (e.g. "16.5 db"); the /gain endpoint expects a dB value.
                Map<String,Object> gain = new LinkedHashMap<>();
                gain.put("mode", "master");
                gain.put("masterGainUnit", "dB");
                gain.put("masterGain", enumNumbers(E4KEmbeddedTuner.E4KGain.values()));
                gain.put("agc", true);
                return gain;
            }

            if(embedded instanceof FC0013EmbeddedTuner)
            {
                //FC0013 LNA gain labels are 0..23 step indexes; the /gain endpoint expects that index.
                Map<String,Object> gain = new LinkedHashMap<>();
                gain.put("mode", "master");
                gain.put("masterGainUnit", "index");
                gain.put("masterGain", enumNumbers(FC0013EmbeddedTuner.LNAGain.values()));
                gain.put("agc", true);
                return gain;
            }

            return null;
        }

        //---------------------------------------------------------------------------------- Airspy / HydraSDR
        if(c instanceof AirspyTunerController || c instanceof HydraSdrTunerController)
        {
            //Both take a 1..22 gain level plus a LINEARITY/SENSITIVITY curve (see applyGain).
            Map<String,Object> gain = new LinkedHashMap<>();
            gain.put("mode", "master");
            gain.put("masterGainUnit", "index");
            gain.put("min", 1);
            gain.put("max", 22);
            gain.put("step", 1);
            gain.put("gainModes", List.of("LINEARITY", "SENSITIVITY"));
            return gain;
        }

        //---------------------------------------------------------------------------------- HackRF (two-axis + amp)
        if(c instanceof HackRFTunerController)
        {
            List<Integer> lna = new ArrayList<>();

            for(HackRFTunerController.HackRFLNAGain g : HackRFTunerController.HackRFLNAGain.values())
            {
                lna.add(g.getValue());
            }

            List<Integer> vga = new ArrayList<>();

            for(HackRFTunerController.HackRFVGAGain g : HackRFTunerController.HackRFVGAGain.values())
            {
                vga.add(g.getValue());
            }

            Map<String,Object> gain = new LinkedHashMap<>();
            gain.put("mode", "multi");
            gain.put("unit", "dB");
            gain.put("lnaGain", lna);
            gain.put("vgaGain", vga);
            gain.put("amp", true);
            return gain;
        }

        //---------------------------------------------------------------------------------- SDRplay RSP
        if(c instanceof RspTunerController rsp)
        {
            Map<String,Object> gain = new LinkedHashMap<>();
            gain.put("mode", "multi");

            Map<String,Object> lnaState = new LinkedHashMap<>();
            lnaState.put("min", 0);

            try
            {
                lnaState.put("max", rsp.getControlRsp().getMaximumLNASetting());
            }
            catch(Exception e)
            {
                //best effort - omit max if unavailable
            }

            gain.put("lnaState", lnaState);

            Map<String,Object> gr = new LinkedHashMap<>();
            gr.put("min", 20);
            gr.put("max", 59);
            gr.put("unit", "dB");
            gain.put("gainReduction", gr);
            return gain;
        }

        //---------------------------------------------------------------------------------- Airspy HF+ (attenuation + LNA)
        if(c instanceof AirspyHfTunerController)
        {
            //applyGain snaps {attenuation} to Attenuation.getValue(), which is the 0..N index (label is the dB).
            List<Integer> attValues = new ArrayList<>();
            List<String> attLabels = new ArrayList<>();

            for(Attenuation a : Attenuation.values())
            {
                attValues.add((int)a.getValue());
                attLabels.add(a.toString());
            }

            Map<String,Object> att = new LinkedHashMap<>();
            att.put("unit", "index");
            att.put("min", attValues.isEmpty() ? 0 : attValues.get(0));
            att.put("max", attValues.isEmpty() ? 0 : attValues.get(attValues.size() - 1));
            att.put("values", attValues);
            att.put("labels", attLabels);

            Map<String,Object> gain = new LinkedHashMap<>();
            gain.put("mode", "multi");
            gain.put("attenuation", att);
            gain.put("lna", true);
            return gain;
        }

        return null;
    }

    /**
     * Extracts the leading numeric label from each enum constant's {@code toString()} (via {@link #parseLeadingNumber}),
     * in declaration order, skipping constants with no numeric label (e.g. AUTOMATIC / MANUAL).  Used to publish the
     * discrete gain steps a device accepts.
     */
    private static List<Double> enumNumbers(Object[] values)
    {
        List<Double> numbers = new ArrayList<>();

        for(Object v : values)
        {
            Double number = parseLeadingNumber(String.valueOf(v));

            if(number != null)
            {
                numbers.add(number);
            }
        }

        return numbers;
    }

    private void handleTunerControl(HttpExchange exchange, String id, String action) throws IOException
    {
        DiscoveredTuner dt = mTunerManager.getDiscoveredTunerModel().getDiscoveredTuner(id);

        if(dt == null || !dt.hasTuner())
        {
            sendJson(exchange, 200, error("tuner not available"));
            return;
        }

        TunerController c = dt.getTuner().getTunerController();
        JsonNode body = readBody(exchange);

        switch(action)
        {
            case "frequency":
            {
                long frequency = body.has("frequency") ? body.get("frequency").asLong() : 0L;

                try
                {
                    c.setFrequency(frequency);
                    Map<String,Object> ok = new LinkedHashMap<>();
                    ok.put("ok", true);
                    ok.put("frequency", frequency);
                    sendJson(exchange, 200, ok);
                }
                catch(SourceException se)
                {
                    sendJson(exchange, 200, error(se.getMessage()));
                }
                break;
            }
            case "ppm":
            {
                double ppm = body.has("ppm") ? body.get("ppm").asDouble() : 0d;

                try
                {
                    c.setFrequencyCorrection(ppm);
                    Map<String,Object> ok = new LinkedHashMap<>();
                    ok.put("ok", true);
                    ok.put("ppm", ppm);
                    sendJson(exchange, 200, ok);
                }
                catch(SourceException se)
                {
                    sendJson(exchange, 200, error(se.getMessage()));
                }
                break;
            }
            case "gain":
            {
                Map<String,Object> result = applyGain(c, body);

                //Cache the applied numeric gain for reporting in buildTunerList (most controllers can't read it back).
                if(Boolean.TRUE.equals(result.get("ok")) && result.get("gain") != null)
                {
                    mLastGain.put(id, result.get("gain"));
                }

                sendJson(exchange, 200, result);
                break;
            }
            case "samplerate":
            {
                long sampleRate = body.has("sampleRate") ? body.get("sampleRate").asLong() : 0L;
                sendJson(exchange, 200, applySampleRate(c, sampleRate));
                break;
            }
            case "autoppm":
            {
                //Enable/disable the tuner's automatic frequency-error (PPM) correction manager.
                //Runtime-only: the node agent persists the setting (config_override.tuners[]) and
                //re-applies it on boot, so no config write is needed here.
                boolean enabled = body.has("enabled") && body.get("enabled").asBoolean();
                c.getTunerFrequencyErrorManager().setEnabled(enabled);
                Map<String,Object> ok = new LinkedHashMap<>();
                ok.put("ok", true);
                ok.put("autoPpm", c.getTunerFrequencyErrorManager().isEnabled());
                sendJson(exchange, 200, ok);
                break;
            }
            default:
                sendJson(exchange, 404, error("not found"));
                break;
        }
    }

    //---------------------------------------------------------------------------------------------------------------
    // Tuner gain (device-typed dispatch)
    //---------------------------------------------------------------------------------------------------------------

    /**
     * Best-effort read of the current composite gain for controllers that expose a getter.  Returns null when the
     * controller has no readable current-gain value (the common case - gain state lives in the tuner configuration).
     */
    private Object readCurrentGain(TunerController c)
    {
        try
        {
            if(c instanceof RspTunerController rsp)
            {
                return (double)rsp.getControlRsp().getCurrentGain();
            }
        }
        catch(Exception e)
        {
            //best effort
        }

        return null;
    }

    /**
     * Applies gain to the tuner, dispatching on the concrete controller (and, for RTL, the embedded tuner) type.
     * SDR-Trunk has no common gain interface, so each device is handled explicitly.
     *
     * <p>Request body fields (all optional - each device reads what applies to it):</p>
     * <ul>
     *   <li>{@code gain} (number) - desired gain in device units (dB-ish); the server snaps to the nearest
     *       supported discrete step.</li>
     *   <li>{@code auto} (boolean) - request automatic/AGC gain where the device supports it.</li>
     *   <li>{@code gainMode} (string, "LINEARITY"|"SENSITIVITY") - Airspy / HydraSDR gain curve (default LINEARITY).</li>
     *   <li>{@code lnaGain}, {@code vgaGain} (number) - HackRF LNA/VGA axes (dB).</li>
     *   <li>{@code amp} (boolean) - HackRF RF amplifier enable.</li>
     *   <li>{@code lnaState}, {@code gainReduction} (number) - SDRplay LNA state index and baseband gain reduction (20-59).</li>
     *   <li>{@code attenuation} (number), {@code lna} (boolean) - Airspy HF attenuation (dB) and LNA enable.</li>
     * </ul>
     *
     * @return response map: {@code {ok, tunerType, gain, gainLabel, ...device echoes...}} on success, or
     *         {@code {ok:false, error}} when the tuner type has no settable gain via this API.
     */
    private Map<String,Object> applyGain(TunerController c, JsonNode body)
    {
        String tunerType = c.getClass().getSimpleName();
        double requested = body.has("gain") ? body.get("gain").asDouble() : Double.NaN;
        boolean auto = body.has("auto") && body.get("auto").asBoolean();

        try
        {
            //---------------------------------------------------------------------------------- RTL2832 family
            if(c instanceof RTL2832TunerController rtl && rtl.hasEmbeddedTuner())
            {
                EmbeddedTuner embedded = rtl.getEmbeddedTuner();

                if(embedded instanceof R8xEmbeddedTuner r8x)
                {
                    if(auto)
                    {
                        r8x.setGain(R8xEmbeddedTuner.MasterGain.AUTOMATIC, true);
                        return gainOk("R820T/R828D", null, "Automatic");
                    }

                    R8xEmbeddedTuner.MasterGain chosen = R8xEmbeddedTuner.MasterGain.MANUAL;
                    double best = Double.MAX_VALUE;

                    for(R8xEmbeddedTuner.MasterGain g : R8xEmbeddedTuner.MasterGain.values())
                    {
                        Double value = parseLeadingNumber(g.toString());

                        if(value != null && Math.abs(value - requested) < best)
                        {
                            best = Math.abs(value - requested);
                            chosen = g;
                        }
                    }

                    r8x.setGain(chosen, true);
                    return gainOk("R820T/R828D", parseLeadingNumber(chosen.toString()), chosen.toString());
                }

                if(embedded instanceof E4KEmbeddedTuner e4k)
                {
                    if(auto)
                    {
                        e4k.setGain(E4KEmbeddedTuner.E4KGain.AUTOMATIC, true);
                        return gainOk("E4K", null, "Automatic");
                    }

                    E4KEmbeddedTuner.E4KGain chosen = E4KEmbeddedTuner.E4KGain.MANUAL;
                    double best = Double.MAX_VALUE;

                    for(E4KEmbeddedTuner.E4KGain g : E4KEmbeddedTuner.E4KGain.values())
                    {
                        //Match against the dB label (e.g. "16.5 db"); AUTOMATIC/MANUAL have no numeric label.
                        Double value = parseLeadingNumber(g.toString());

                        if(value != null && Math.abs(value - requested) < best)
                        {
                            best = Math.abs(value - requested);
                            chosen = g;
                        }
                    }

                    e4k.setGain(chosen, true);
                    return gainOk("E4K", parseLeadingNumber(chosen.toString()), chosen.toString());
                }

                if(embedded instanceof FC0013EmbeddedTuner fc)
                {
                    if(auto)
                    {
                        fc.setGain(true, FC0013EmbeddedTuner.LNAGain.values()[0]);
                        return gainOk("FC0013", null, "AGC");
                    }

                    FC0013EmbeddedTuner.LNAGain chosen = FC0013EmbeddedTuner.LNAGain.values()[0];
                    double best = Double.MAX_VALUE;

                    for(FC0013EmbeddedTuner.LNAGain g : FC0013EmbeddedTuner.LNAGain.values())
                    {
                        Double value = parseLeadingNumber(g.toString());

                        if(value != null && Math.abs(value - requested) < best)
                        {
                            best = Math.abs(value - requested);
                            chosen = g;
                        }
                    }

                    fc.setGain(false, chosen);
                    return gainOk("FC0013", parseLeadingNumber(chosen.toString()), chosen.toString());
                }

                return error("gain control not supported for RTL embedded tuner type [" +
                        embedded.getClass().getSimpleName() + "]");
            }

            //---------------------------------------------------------------------------------- Airspy / HydraSDR
            if(c instanceof AirspyTunerController airspy)
            {
                boolean sensitivity = "SENSITIVITY".equalsIgnoreCase(body.path("gainMode").asText(""));
                int level = clamp((int)Math.round(Double.isNaN(requested) ? 0 : requested), 1, 22);
                AirspyTunerController.GainMode mode = sensitivity ?
                        AirspyTunerController.GainMode.SENSITIVITY : AirspyTunerController.GainMode.LINEARITY;
                airspy.setGain(AirspyTunerController.Gain.getGain(mode, level));
                Map<String,Object> ok = gainOk("Airspy", (double)level, mode.name() + "_" + level);
                ok.put("gainMode", mode.name());
                return ok;
            }

            if(c instanceof HydraSdrTunerController hydra)
            {
                boolean sensitivity = "SENSITIVITY".equalsIgnoreCase(body.path("gainMode").asText(""));
                int level = clamp((int)Math.round(Double.isNaN(requested) ? 0 : requested), 1, 22);
                HydraSdrTunerController.GainMode mode = sensitivity ?
                        HydraSdrTunerController.GainMode.SENSITIVITY : HydraSdrTunerController.GainMode.LINEARITY;
                hydra.setGain(HydraSdrTunerController.Gain.getGain(mode, level));
                Map<String,Object> ok = gainOk("HydraSDR", (double)level, mode.name() + "_" + level);
                ok.put("gainMode", mode.name());
                return ok;
            }

            //---------------------------------------------------------------------------------- HackRF (two-axis)
            if(c instanceof HackRFTunerController hackrf)
            {
                Double lnaReq = body.has("lnaGain") ? body.get("lnaGain").asDouble() :
                        (Double.isNaN(requested) ? null : requested);
                Double vgaReq = body.has("vgaGain") ? body.get("vgaGain").asDouble() : null;
                Map<String,Object> ok = new LinkedHashMap<>();
                ok.put("ok", true);
                ok.put("tunerType", "HackRF");

                if(lnaReq != null)
                {
                    HackRFTunerController.HackRFLNAGain chosen = HackRFTunerController.HackRFLNAGain.values()[0];
                    double best = Double.MAX_VALUE;

                    for(HackRFTunerController.HackRFLNAGain g : HackRFTunerController.HackRFLNAGain.values())
                    {
                        if(Math.abs(g.getValue() - lnaReq) < best)
                        {
                            best = Math.abs(g.getValue() - lnaReq);
                            chosen = g;
                        }
                    }

                    hackrf.setLNAGain(chosen);
                    ok.put("lnaGain", chosen.getValue());
                    ok.put("gain", chosen.getValue());
                }

                if(vgaReq != null)
                {
                    HackRFTunerController.HackRFVGAGain chosen = HackRFTunerController.HackRFVGAGain.values()[0];
                    double best = Double.MAX_VALUE;

                    for(HackRFTunerController.HackRFVGAGain g : HackRFTunerController.HackRFVGAGain.values())
                    {
                        if(Math.abs(g.getValue() - vgaReq) < best)
                        {
                            best = Math.abs(g.getValue() - vgaReq);
                            chosen = g;
                        }
                    }

                    hackrf.setVGAGain(chosen);
                    ok.put("vgaGain", chosen.getValue());
                }

                if(body.has("amp"))
                {
                    hackrf.setAmplifierEnabled(body.get("amp").asBoolean());
                    ok.put("amp", body.get("amp").asBoolean());
                }

                return ok;
            }

            //---------------------------------------------------------------------------------- SDRplay RSP
            if(c instanceof RspTunerController rsp)
            {
                int lna = body.has("lnaState") ? body.get("lnaState").asInt() : rsp.getControlRsp().getLNA();
                int gr = body.has("gainReduction") ? body.get("gainReduction").asInt() :
                        rsp.getControlRsp().getBasebandGainReduction();
                rsp.getControlRsp().setGain(lna, gr);
                Map<String,Object> ok = new LinkedHashMap<>();
                ok.put("ok", true);
                ok.put("tunerType", "SDRplay RSP");
                ok.put("lnaState", lna);
                ok.put("gainReduction", gr);
                ok.put("gain", (double)rsp.getControlRsp().getCurrentGain());
                return ok;
            }

            //---------------------------------------------------------------------------------- Airspy HF+ (attenuation)
            if(c instanceof AirspyHfTunerController hf)
            {
                Map<String,Object> ok = new LinkedHashMap<>();
                ok.put("ok", true);
                ok.put("tunerType", "Airspy HF+");

                if(body.has("attenuation") || !Double.isNaN(requested))
                {
                    double attReq = body.has("attenuation") ? body.get("attenuation").asDouble() : requested;
                    Attenuation chosen = Attenuation.values()[0];
                    double best = Double.MAX_VALUE;

                    for(Attenuation a : Attenuation.values())
                    {
                        if(Math.abs(a.getValue() - attReq) < best)
                        {
                            best = Math.abs(a.getValue() - attReq);
                            chosen = a;
                        }
                    }

                    hf.setAttenuation(chosen);
                    ok.put("attenuation", (int)chosen.getValue());
                    ok.put("gain", (int)chosen.getValue());
                }

                if(body.has("lna"))
                {
                    hf.setLna(body.get("lna").asBoolean());
                    ok.put("lna", body.get("lna").asBoolean());
                }

                return ok;
            }
        }
        catch(Exception e)
        {
            return error("gain error on [" + tunerType + "]: " +
                    (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }

        return error("gain control not supported for tuner type [" + tunerType + "]");
    }

    /**
     * Builds a standard successful gain response.
     */
    private Map<String,Object> gainOk(String tunerType, Double gain, String gainLabel)
    {
        Map<String,Object> ok = new LinkedHashMap<>();
        ok.put("ok", true);
        ok.put("tunerType", tunerType);
        ok.put("gain", gain);
        ok.put("gainLabel", gainLabel);
        return ok;
    }

    //---------------------------------------------------------------------------------------------------------------
    // Tuner sample rate (device-typed dispatch, validated against the device's allowed rates)
    //---------------------------------------------------------------------------------------------------------------

    /**
     * Sets the tuner sample rate, validating the requested rate (Hz) against the device's allowed set.  Returns a
     * clear error listing the allowed rates when the request is not an exact supported value.
     *
     * @return {@code {ok:true, tunerType, sampleRate}} on success, else {@code {ok:false, error, allowedSampleRates}}.
     */
    private Map<String,Object> applySampleRate(TunerController c, long requested)
    {
        String tunerType = c.getClass().getSimpleName();

        try
        {
            if(c instanceof RTL2832TunerController rtl)
            {
                List<Long> allowed = new ArrayList<>();

                for(RTL2832TunerController.SampleRate sr : RTL2832TunerController.SampleRate.values())
                {
                    allowed.add((long)sr.getRate());

                    if(sr.getRate() == requested)
                    {
                        rtl.setSampleRate(sr);
                        return sampleRateOk("RTL2832", sr.getRate());
                    }
                }

                return unsupportedSampleRate(tunerType, allowed);
            }

            if(c instanceof HackRFTunerController hackrf)
            {
                List<Long> allowed = new ArrayList<>();

                for(HackRFTunerController.HackRFSampleRate sr : HackRFTunerController.HackRFSampleRate.VALID_SAMPLE_RATES)
                {
                    allowed.add((long)sr.getRate());

                    if((long)sr.getRate() == requested)
                    {
                        hackrf.setSampleRate(sr);
                        return sampleRateOk("HackRF", (long)sr.getRate());
                    }
                }

                return unsupportedSampleRate(tunerType, allowed);
            }

            if(c instanceof AirspyTunerController airspy)
            {
                List<Long> allowed = new ArrayList<>();

                for(AirspySampleRate sr : airspy.getSampleRates())
                {
                    allowed.add((long)sr.getRate());
                }

                AirspySampleRate match = airspy.getSampleRate((int)requested);

                if(match != null && match.getRate() == requested)
                {
                    airspy.setSampleRate(match);
                    return sampleRateOk("Airspy", match.getRate());
                }

                return unsupportedSampleRate(tunerType, allowed);
            }

            if(c instanceof HydraSdrTunerController hydra)
            {
                List<Long> allowed = new ArrayList<>();

                for(io.github.dsheirer.source.tuner.hydrasdr.HydraSdrSampleRate sr : hydra.getSampleRates())
                {
                    allowed.add((long)sr.getRate());
                }

                io.github.dsheirer.source.tuner.hydrasdr.HydraSdrSampleRate match = hydra.getSampleRate((int)requested);

                if(match != null && match.getRate() == requested)
                {
                    hydra.setSampleRate(match);
                    return sampleRateOk("HydraSDR", match.getRate());
                }

                return unsupportedSampleRate(tunerType, allowed);
            }

            if(c instanceof RspTunerController rsp)
            {
                List<Long> allowed = new ArrayList<>();

                for(RspSampleRate sr : RspSampleRate.values())
                {
                    allowed.add(sr.getSampleRate());

                    if(sr.getSampleRate() == requested)
                    {
                        rsp.setSampleRate(sr);
                        return sampleRateOk("SDRplay RSP", sr.getSampleRate());
                    }
                }

                return unsupportedSampleRate(tunerType, allowed);
            }

            if(c instanceof AirspyHfTunerController hf)
            {
                List<Long> allowed = new ArrayList<>();

                for(AirspyHfSampleRate sr : hf.getAvailableSampleRates())
                {
                    allowed.add((long)sr.getSampleRate());

                    if(sr.getSampleRate() == requested)
                    {
                        hf.setSampleRate(sr);
                        return sampleRateOk("Airspy HF+", sr.getSampleRate());
                    }
                }

                return unsupportedSampleRate(tunerType, allowed);
            }
        }
        catch(Exception e)
        {
            return error("sample rate error on [" + tunerType + "]: " +
                    (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }

        return error("sample rate control not supported for tuner type [" + tunerType + "]");
    }

    private Map<String,Object> sampleRateOk(String tunerType, long sampleRate)
    {
        Map<String,Object> ok = new LinkedHashMap<>();
        ok.put("ok", true);
        ok.put("tunerType", tunerType);
        ok.put("sampleRate", sampleRate);
        return ok;
    }

    private Map<String,Object> unsupportedSampleRate(String tunerType, List<Long> allowed)
    {
        Map<String,Object> err = error("unsupported sample rate for tuner type [" + tunerType + "]");
        err.put("allowedSampleRates", allowed);
        return err;
    }

    /**
     * Clamps an integer to the inclusive range [min, max].
     */
    private static int clamp(int value, int min, int max)
    {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Parses the leading number from a label such as "248", "23 HIGH" or "0.240 MHz".  Returns null if none.
     */
    private static Double parseLeadingNumber(String text)
    {
        if(text == null)
        {
            return null;
        }

        java.util.regex.Matcher m = java.util.regex.Pattern.compile("-?\\d+(?:\\.\\d+)?").matcher(text);
        return m.find() ? Double.valueOf(m.group()) : null;
    }

    private void handleChannels(HttpExchange exchange)
    {
        try
        {
            if(!authorize(exchange))
            {
                return;
            }

            String path = exchange.getRequestURI().getPath();

            if(path.equals("/channels") || path.equals("/channels/"))
            {
                sendJson(exchange, 200, buildChannelList());
                return;
            }

            //Expect /channels/{id}/{action}
            String[] parts = path.split("/");

            if(parts.length >= 4)
            {
                int id;

                try
                {
                    id = Integer.parseInt(parts[2]);
                }
                catch(NumberFormatException nfe)
                {
                    sendJson(exchange, 200, error("invalid channel id"));
                    return;
                }

                String action = parts[3];
                handleChannelControl(exchange, id, action);
                return;
            }

            sendJson(exchange, 404, error("not found"));
        }
        catch(Exception e)
        {
            sendError(exchange, e);
        }
        finally
        {
            exchange.close();
        }
    }

    /**
     * Emits the live decode-health and signal fields for a channel/call entry from the cached quality snapshot:
     * {@code syncPercent} (0-100 decode health) and {@code signalDbfs} (smoothed signal level, dBFS).  Both are
     * null when the channel has no fresh snapshot (not a standard/control channel, stopped, or stale &gt;5s) so the
     * UI can omit the readout rather than show a stale number.
     */
    private void putQuality(Map<String,Object> entry, Channel channel)
    {
        ControlChannelQualitySnapshot q = channel != null ? mQualityByChannel.get(channel) : null;

        if(q != null && (System.currentTimeMillis() - q.observedAtMs()) <= QUALITY_FRESHNESS_MS)
        {
            entry.put("syncPercent", q.decodeHealthPercent());
            //averageSignalDbfs is steadier than the instantaneous value for a UI bar; fall back to instant.
            entry.put("signalDbfs", q.averageSignalDbfs() != null ? q.averageSignalDbfs() : q.signalDbfs());
            /*
             * How long this channel has been decoding, in milliseconds, or null if it has not decoded anything on
             * this run.  A client measuring a frequency it just started needs to know whether syncPercent is backed
             * by real decoding yet; without this the only safe answer was to wait out the whole rolling window.
             * Reported as a DURATION rather than a timestamp so a caller on another machine needs no agreement with
             * this JVM's clock.  Absent on older runtimes, which is how a client tells it must wait instead.
             */
            entry.put("decodingForMs", q.decodingSinceMs() > 0
                ? Math.max(0, q.observedAtMs() - q.decodingSinceMs()) : null);
            entry.put("syncFrames", q.validFrames() + q.invalidFrames());
        }
        else
        {
            entry.put("syncPercent", null);
            entry.put("signalDbfs", null);
            entry.put("decodingForMs", null);
            entry.put("syncFrames", null);
        }
    }

    private Map<String,Object> buildChannelList()
    {
        ChannelProcessingManager cpm = mConfigurationManager.getChannelProcessingManager();
        ChannelModel cm = mConfigurationManager.getChannelModel();
        ChannelMetadataModel mm = cpm.getChannelMetadataModel();

        //Build a channel -> metadata lookup from the live metadata model.
        Map<Channel,ChannelMetadata> metaByChannel = new HashMap<>();
        //One locked snapshot instead of walking the live list index-by-index.  The old loop read a plain ArrayList
        //while the EDT (and the decode threads, via updateChannelMetadataToChannelMap) mutated it, and the catch
        //below never actually fired — getRowCount()/getChannelMetadata() are bare size()/get() calls with no
        //modCount check, so the race produced wrong rows rather than an exception.
        for(Map.Entry<ChannelMetadata,Channel> entry: mm.snapshot())
        {
            ChannelMetadata meta = entry.getKey();
            Channel ch = entry.getValue();

            if(meta != null && ch != null)
            {
                metaByChannel.putIfAbsent(ch, meta);
            }
        }

        List<Map<String,Object>> list = new ArrayList<>();

        // Snapshot before iterating off-thread (JavaFX list, mutated by decode/reload).
        for(Channel channel : new ArrayList<>(cm.getChannels()))
        {
            Map<String,Object> entry = new LinkedHashMap<>();
            entry.put("id", channel.getChannelID());
            entry.put("name", channel.getName());
            entry.put("system", channel.getSystem());
            entry.put("site", channel.getSite());
            entry.put("type", String.valueOf(channel.getChannelType()));
            //isAutoStart(), not getAutoStart(): the getter pair exists for the XML/JSON round-trip quirk (see the
            //import handler); isAutoStart() is the live flag.  "suppressed" lets a freshly restarted (stateless)
            //node agent recover which channels it auto-stopped from live reality alone.
            entry.put("autoStart", channel.isAutoStart());
            entry.put("suppressed",
                    mSelfHealSuppressed.contains(channel.getName() != null ? channel.getName().trim() : ""));

            boolean processing = channel.isProcessing();
            entry.put("processing", processing);

            if(processing)
            {
                ChannelMetadata meta = metaByChannel.get(channel);

                if(meta != null)
                {
                    Identifier state = meta.getChannelStateIdentifier();
                    String stateText = state != null ? state.toString() : "PROCESSING";
                    entry.put("state", stateText);
                    //CONTROL indicates the channel is locked on a trunking control channel (P25/DMR/etc.).
                    entry.put("control", "CONTROL".equals(stateText));

                    Identifier from = meta.getFromIdentifier();
                    entry.put("from", from != null ? from.toString() : null);
                    entry.put("fromAlias", aliasNames(meta.getFromIdentifierAliases()));

                    Identifier to = meta.getToIdentifier();
                    entry.put("to", to != null ? to.toString() : null);
                    entry.put("toAlias", aliasNames(meta.getToIdentifierAliases()));

                    Identifier talker = meta.getTalkerAliasIdentifier();
                    entry.put("talkerAlias", talker != null ? talker.toString() : null);

                    entry.put("timeslot", meta.getTimeslot());
                    entry.put("frequency", frequencyOf(meta));
                }
                else
                {
                    entry.put("state", "PROCESSING");
                    entry.put("control", false);
                    entry.put("from", null);
                    entry.put("fromAlias", null);
                    entry.put("to", null);
                    entry.put("toAlias", null);
                    entry.put("talkerAlias", null);
                    entry.put("timeslot", null);
                    entry.put("frequency", null);
                }
            }
            else
            {
                entry.put("state", "STOPPED");
                entry.put("control", false);
                entry.put("from", null);
                entry.put("fromAlias", null);
                entry.put("to", null);
                entry.put("toAlias", null);
                entry.put("talkerAlias", null);
                entry.put("timeslot", null);
                entry.put("frequency", null);
            }

            putQuality(entry, channel);
            list.add(entry);
        }

        Map<String,Object> body = new LinkedHashMap<>();
        body.put("channels", list);
        body.put("activeCalls", buildActiveCalls(mm));
        return body;
    }

    /**
     * Builds a "Now Playing" list from the live channel metadata model - one entry per metadata row that is in an
     * active state (CALL, ENCRYPTED, DATA, CONTROL or ACTIVE).  This captures dynamically-allocated trunking traffic
     * channels that are NOT in the configured channel list, so a UI can render active calls (talkgroup, from -&gt; to
     * with aliases, control-channel lock) without guessing.  Includes CONTROL rows so the control channel is visible.
     */
    private List<Map<String,Object>> buildActiveCalls(ChannelMetadataModel mm)
    {
        List<Map<String,Object>> calls = new ArrayList<>();

        //Locked snapshot, same reasoning as buildChannelList(): walking the live model here attributed calls to the
        //wrong channel rather than throwing, because the accessors do no modCount checking.
        for(Map.Entry<ChannelMetadata,Channel> entry: mm.snapshot())
        {
            {
                ChannelMetadata meta = entry.getKey();

                if(meta == null)
                {
                    continue;
                }

                Identifier state = meta.getChannelStateIdentifier();
                String stateText = state != null ? state.toString() : null;

                //Only emit rows that reflect active decode (call / control / data / active).
                if(stateText == null || "IDLE".equals(stateText) || "FADE".equals(stateText) ||
                        "RESET".equals(stateText) || "TEARDOWN".equals(stateText))
                {
                    continue;
                }

                Map<String,Object> call = new LinkedHashMap<>();
                call.put("state", stateText);
                call.put("control", "CONTROL".equals(stateText));

                Channel ch = entry.getValue();
                call.put("channelId", ch != null ? ch.getChannelID() : null);
                call.put("channelName", ch != null ? ch.getName() : null);

                Identifier from = meta.getFromIdentifier();
                call.put("from", from != null ? from.toString() : null);
                call.put("fromAlias", aliasNames(meta.getFromIdentifierAliases()));

                Identifier to = meta.getToIdentifier();
                //For group calls the TO identifier is the talkgroup.
                call.put("to", to != null ? to.toString() : null);
                call.put("talkgroup", to != null ? to.toString() : null);
                call.put("toAlias", aliasNames(meta.getToIdentifierAliases()));

                Identifier talker = meta.getTalkerAliasIdentifier();
                call.put("talkerAlias", talker != null ? talker.toString() : null);

                call.put("timeslot", meta.getTimeslot());
                call.put("frequency", frequencyOf(meta));

                //Quality is only tracked for standard (control) channels; a traffic-call row resolves to null.
                putQuality(call, ch);

                calls.add(call);
            }
        }

        return calls;
    }

    /**
     * Best-effort comma-joined alias names, or null if the list is null/empty.
     */
    private static String aliasNames(List<Alias> aliases)
    {
        if(aliases == null || aliases.isEmpty())
        {
            return null;
        }

        StringBuilder sb = new StringBuilder();

        for(Alias alias : aliases)
        {
            if(alias != null && alias.getName() != null && !alias.getName().isEmpty())
            {
                if(sb.length() > 0)
                {
                    sb.append(", ");
                }

                sb.append(alias.getName());
            }
        }

        return sb.length() > 0 ? sb.toString() : null;
    }

    private Long frequencyOf(ChannelMetadata meta)
    {
        try
        {
            if(meta.getFrequencyConfigurationIdentifier() != null)
            {
                Object value = meta.getFrequencyConfigurationIdentifier().getValue();

                if(value instanceof Number number)
                {
                    return number.longValue();
                }
            }
        }
        catch(Exception e)
        {
            //ignore - best effort
        }

        return null;
    }

    private void handleChannelControl(HttpExchange exchange, int id, String action) throws IOException
    {
        ChannelProcessingManager cpm = mConfigurationManager.getChannelProcessingManager();
        ChannelModel cm = mConfigurationManager.getChannelModel();

        Channel target = null;

        // Snapshot before iterating off-thread (JavaFX list, mutated by decode/reload).
        for(Channel channel : new ArrayList<>(cm.getChannels()))
        {
            if(channel.getChannelID() == id)
            {
                target = channel;
                break;
            }
        }

        if(target == null)
        {
            sendJson(exchange, 200, error("channel not found"));
            return;
        }

        try
        {
            switch(action)
            {
                case "start":
                    cpm.start(target);
                    sendJson(exchange, 200, ok());
                    break;
                case "stop":
                    cpm.stop(target);
                    sendJson(exchange, 200, ok());
                    break;
                //Agent-stop suppression (see mSelfHealSuppressed).  Orthogonal to start/stop on purpose: the agent
                //suppresses BEFORE stopping (so a self-heal sweep can't restart the channel in the gap) and keeps the
                //suppression through its probe restarts (so a sweep can't interfere mid-probe).
                case "suppress":
                    mSelfHealSuppressed.add(target.getName() != null ? target.getName().trim() : "");
                    sendJson(exchange, 200, ok());
                    break;
                case "unsuppress":
                    mSelfHealSuppressed.remove(target.getName() != null ? target.getName().trim() : "");
                    mSuppressedNoticeAt.remove(target.getName() != null ? target.getName().trim() : "");
                    sendJson(exchange, 200, ok());
                    break;
                default:
                    sendJson(exchange, 404, error("not found"));
                    break;
            }
        }
        catch(ChannelException ce)
        {
            sendJson(exchange, 200, error(ce.getMessage()));
        }
    }

    private void handleEvents(HttpExchange exchange)
    {
        try
        {
            if(!authorize(exchange))
            {
                return;
            }

            int limit = 50;
            String query = exchange.getRequestURI().getQuery();

            if(query != null)
            {
                for(String pair : query.split("&"))
                {
                    if(pair.startsWith("limit="))
                    {
                        try
                        {
                            limit = Integer.parseInt(pair.substring("limit=".length()));
                        }
                        catch(NumberFormatException nfe)
                        {
                            //keep default
                        }
                    }
                }
            }

            Map<String,Object> body = new LinkedHashMap<>();
            // stop() nulls this; an in-flight /events during shutdown would
            // otherwise NPE into a 500 rather than an empty result.
            EventBuffer buffer = mEventBuffer;
            body.put("events", buffer != null ? buffer.getEvents(limit) : java.util.List.of());
            sendJson(exchange, 200, body);
        }
        catch(Exception e)
        {
            sendError(exchange, e);
        }
        finally
        {
            exchange.close();
        }
    }

    //---------------------------------------------------------------------------------------------------------------
    // Configuration reload / import (registered at both /playlist and /config)
    //---------------------------------------------------------------------------------------------------------------

    private void handleConfig(HttpExchange exchange)
    {
        try
        {
            if(!authorize(exchange))
            {
                return;
            }

            String path = exchange.getRequestURI().getPath();

            if(path.equals("/playlist/reload") || path.equals("/config/reload"))
            {
                //Reloads channel/alias/stream models from the shared SQLite database.
                mConfigurationManager.applyExternalConfigurationSnapshotHeadless(() -> null);

                //Reload restarts every auto-start channel, same as import: stale suppression would just lie.
                mSelfHealSuppressed.clear();
                mSuppressedNoticeAt.clear();

                boolean started = startAutoStartChannels();

                Map<String,Object> body = ok();

                if(!started)
                {
                    body.put("decodePending", true);
                }

                sendJson(exchange, 200, body);
                return;
            }

            if(path.equals("/config/import"))
            {
                byte[] bytes;

                try(InputStream is = exchange.getRequestBody())
                {
                    bytes = readLimited(is);
                }

                ConfigurationState state;

                try
                {
                    //Lenient mapper - the same configuration used by the database configuration store; polymorphism
                    //is driven by the Jackson annotations on Channel/Alias/BroadcastConfiguration.
                    ObjectMapper lenient = new ObjectMapper()
                            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                    state = lenient.readValue(bytes, ConfigurationState.class);

                    if(state != null)
                    {
                        JsonNode root = lenient.readTree(bytes);
                        //ConfigurationState marks aliasListDefinitions @JsonIgnore (and AliasListDefinition has no
                        //default constructor), so Jackson drops the property - recover it from the raw tree and
                        //install via the setter.  Channels/aliases reference these list names, and the snapshot
                        //replace persists them, so losing them would fail validation on reload.
                        state.setAliasListDefinitions(parseAliasListDefinitions(root));
                        //Alias persists its matcher, stream-as-talkgroup, broadcast routes, priority and recordable
                        //flag in dedicated DB columns, so those getters are @JsonIgnore and Jackson drops them on
                        //import - every alias would arrive matcher-less and the snapshot store rejects it ("must
                        //have exactly one match identifier"). Recover them from the raw tree onto the parsed aliases.
                        applyIgnoredAliasFields(state.getAliases(), root, lenient);
                        //Channel.autoStart does NOT round-trip through JSON: getAutoStart() carries only the XML
                        //annotation (localName="enabled"), isAutoStart() is @JsonIgnore and setAutoStart() only has
                        //@JsonAlias("enabled"), so Jackson marks the whole property ignored and DROPS the node's
                        //"autoStart": true on import. It then persists as auto_start=0 and on EVERY restart the
                        //channel reloads with isAutoStart()==false -> getAutoStartChannels() is empty -> nothing
                        //auto-starts (manual start ignores the flag, which is why that always works). Recover the
                        //flag from the raw tree onto each parsed Channel before persisting, mirroring the alias fix.
                        applyIgnoredChannelFields(state.getChannels(), root);
                    }
                }
                catch(Exception e)
                {
                    Map<String,Object> err = error("invalid configuration: " +
                            (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                    sendJson(exchange, 400, err);
                    return;
                }

                if(state == null)
                {
                    sendJson(exchange, 400, error("empty configuration"));
                    return;
                }

                //Basic sanity - null lists become empty lists.
                if(state.getAliases() == null)
                {
                    state.setAliases(new ArrayList<>());
                }

                //Belt-and-braces: an alias that still has no match identifier after recovery can never match and
                //would fail the snapshot store's "exactly one match identifier" check, aborting the ENTIRE import.
                //Drop those individually instead so one bad alias can't block the whole config.
                int beforeAliases = state.getAliases().size();
                state.getAliases().removeIf(a -> a == null || a.getMatchIdentifier() == null);
                int droppedAliases = beforeAliases - state.getAliases().size();
                if(droppedAliases > 0)
                {
                    mLog.warn("config import: dropped " + droppedAliases + " alias(es) with no match identifier");
                }

                if(state.getAliasListDefinitions() == null)
                {
                    state.setAliasListDefinitions(new ArrayList<>());
                }

                if(state.getChannels() == null)
                {
                    state.setChannels(new ArrayList<>());
                }

                if(state.getBroadcastConfigurations() == null)
                {
                    state.setBroadcastConfigurations(new ArrayList<>());
                }

                final ConfigurationState toApply = state;

                //Transactional full overwrite of the configuration tables; the headless apply reloads the live
                //models from the database afterwards (transferStateToModels).
                mConfigurationManager.applyExternalConfigurationSnapshotHeadless(() -> {
                    new ConfigurationSnapshotDatabaseStore(mDatabasePath).replace(toApply);
                    return null;
                });

                //A config apply rebuilds the channel model with NEW Channel instances and can retire channel ids
                //outright, so anything keyed on either is now unreachable but still strongly referenced.
                //mQualityByChannel is keyed by the Channel OBJECT, so every prior generation's entries were pinned
                //until stop(); the id-keyed maps were only ever pruned for ids that survived into the new
                //configuration. Slow growth on a node that gets pushed config often, but growth that never stops.
                mQualityByChannel.clear();
                mChannelUnlockedSince.clear();
                mChannelLastRestart.clear();
                //The import restarts every auto-start channel below, so carrying suppression across it would only
                //misreport running channels as suppressed.  The agent re-stops after a fresh dwell if still warranted.
                mSelfHealSuppressed.clear();
                mSuppressedNoticeAt.clear();

                boolean started = startAutoStartChannels();

                Map<String,Object> body = ok();
                body.put("channels", toApply.getChannels().size());
                body.put("aliases", toApply.getAliases().size());
                body.put("streams", toApply.getBroadcastConfigurations().size());

                if(!started)
                {
                    body.put("decodePending", true);
                }

                sendJson(exchange, 200, body);
                return;
            }

            sendJson(exchange, 404, error("not found"));
        }
        catch(Exception e)
        {
            sendError(exchange, e);
        }
        finally
        {
            exchange.close();
        }
    }

    /**
     * Parses the {@code aliasListDefinitions} array from the raw import JSON: {@code [{"name":..., "family":...}]}.
     * Family accepts the enum constant name (P25/DMR/NXDN/NBFM), defaulting to P25 when absent.  Entries without a
     * name are rejected.
     */
    private static List<io.github.dsheirer.alias.AliasListDefinition> parseAliasListDefinitions(JsonNode root)
    {
        List<io.github.dsheirer.alias.AliasListDefinition> definitions = new ArrayList<>();

        if(root == null || !root.has("aliasListDefinitions") || !root.get("aliasListDefinitions").isArray())
        {
            return definitions;
        }

        for(JsonNode node : root.get("aliasListDefinitions"))
        {
            String name = node.has("name") ? node.get("name").asText(null) : null;

            if(name == null || name.isBlank())
            {
                throw new IllegalArgumentException("alias list definition requires a name");
            }

            String family = node.has("family") ? node.get("family").asText("P25") : "P25";
            io.github.dsheirer.alias.AliasListFamily parsedFamily;

            try
            {
                parsedFamily = io.github.dsheirer.alias.AliasListFamily.valueOf(family.trim().toUpperCase());
            }
            catch(IllegalArgumentException e)
            {
                throw new IllegalArgumentException("unknown alias list family [" + family + "]");
            }

            definitions.add(new io.github.dsheirer.alias.AliasListDefinition(name, parsedFamily));
        }

        return definitions;
    }

    /**
     * Recovers the alias fields that {@link io.github.dsheirer.alias.Alias} keeps out of its JSON form (they are
     * {@code @JsonIgnore} because vce persists them in dedicated alias-table columns): the single match identifier,
     * the stream-as-talkgroup override, broadcast routes, call priority and the recordable flag.  These are read from
     * the raw {@code aliases[]} nodes (index-aligned with the Jackson-parsed alias list, whose order Jackson
     * preserves) and installed via the setters, exactly as the DB loader does.  Sub-objects are converted with the
     * lenient mapper so the {@code AliasID}/{@code StreamAsTalkgroup}/{@code BroadcastChannel} polymorphic
     * {@code @JsonTypeInfo} discriminators resolve.
     */
    private static void applyIgnoredAliasFields(List<io.github.dsheirer.alias.Alias> aliases, JsonNode root,
                                                ObjectMapper mapper) throws IOException
    {
        if(aliases == null || root == null || !root.has("aliases") || !root.get("aliases").isArray())
        {
            return;
        }

        JsonNode arr = root.get("aliases");

        for(int i = 0; i < aliases.size() && i < arr.size(); i++)
        {
            io.github.dsheirer.alias.Alias alias = aliases.get(i);
            JsonNode node = arr.get(i);

            if(alias == null || node == null)
            {
                continue;
            }

            JsonNode matcher = node.get("matchIdentifier");
            if(matcher != null && !matcher.isNull())
            {
                alias.setMatchIdentifier(mapper.treeToValue(matcher, io.github.dsheirer.alias.id.AliasID.class));
            }

            JsonNode stream = node.get("streamTalkgroupAlias");
            if(stream != null && !stream.isNull())
            {
                alias.setStreamTalkgroupAlias(mapper.treeToValue(stream,
                        io.github.dsheirer.alias.id.talkgroup.StreamAsTalkgroup.class));
            }

            JsonNode broadcasts = node.get("broadcastChannels");
            if(broadcasts != null && broadcasts.isArray())
            {
                for(JsonNode bc : broadcasts)
                {
                    io.github.dsheirer.alias.id.broadcast.BroadcastChannel channel =
                            mapper.treeToValue(bc, io.github.dsheirer.alias.id.broadcast.BroadcastChannel.class);
                    if(channel != null && channel.getChannelName() != null && !channel.getChannelName().isBlank())
                    {
                        alias.addBroadcastChannel(channel);
                    }
                }
            }

            JsonNode priority = node.get("callPriority");
            if(priority != null && priority.isNumber())
            {
                alias.setCallPriority(priority.asInt());
            }

            JsonNode recordable = node.get("recordable");
            if(recordable != null && recordable.isBoolean())
            {
                alias.setRecordable(recordable.asBoolean());
            }
        }
    }

    /**
     * Recovers the {@code autoStart} flag (and {@code autoStartOrder}) from the raw import JSON onto each parsed
     * {@link io.github.dsheirer.controller.channel.Channel}.  Jackson drops {@code autoStart} on deserialization
     * because the property is split across a {@code @JsonIgnore} {@code isAutoStart()} getter and inert XML-only
     * annotations on {@code getAutoStart()}/{@code setAutoStart()} - see the call site.  Without this, every imported
     * channel is persisted with {@code auto_start=0} and never auto-starts on restart.  Index-aligned with the parsed
     * channel list exactly like {@link #applyIgnoredAliasFields}.  Accepts both the node's {@code "autoStart"} and the
     * legacy XML {@code "enabled"} spelling.
     */
    private static void applyIgnoredChannelFields(List<io.github.dsheirer.controller.channel.Channel> channels,
                                                  JsonNode root)
    {
        if(channels == null || root == null || !root.has("channels") || !root.get("channels").isArray())
        {
            return;
        }

        JsonNode arr = root.get("channels");

        for(int i = 0; i < channels.size() && i < arr.size(); i++)
        {
            io.github.dsheirer.controller.channel.Channel channel = channels.get(i);
            JsonNode node = arr.get(i);

            if(channel == null || node == null)
            {
                continue;
            }

            JsonNode autoStart = node.has("autoStart") ? node.get("autoStart") : node.get("enabled");
            if(autoStart != null && autoStart.isBoolean())
            {
                channel.setAutoStart(autoStart.asBoolean());
            }

            //autoStartOrder already round-trips via its @JsonAlias("order"), but recover it defensively too.
            JsonNode order = node.has("autoStartOrder") ? node.get("autoStartOrder") : node.get("order");
            if(order != null && order.isNumber())
            {
                channel.setAutoStartOrder(order.asInt());
            }
        }
    }

    /**
     * Starts all auto-start channels unless the headless decode-readiness gate (CPU calibration + JMBE codec)
     * blocks it.  Per-channel start failures are logged and do not abort the remaining channels.
     *
     * @return true when channel starting was attempted, false when the decode gate blocked it (decode pending).
     */
    public boolean startAutoStartChannels()
    {
        if(mHeadless && mDecodeReadyGate != null && !mDecodeReadyGate.getAsBoolean())
        {
            mLog.warn("Configuration applied but headless decode readiness (calibration + JMBE) is not satisfied - " +
                    "channels will start after the node completes first-run setup");
            return false;
        }

        ChannelProcessingManager cpm = mConfigurationManager.getChannelProcessingManager();

        for(Channel channel : mConfigurationManager.getChannelModel().getAutoStartChannels())
        {
            try
            {
                cpm.start(channel);
            }
            catch(ChannelException | RuntimeException e)
            {
                mLog.warn("Auto-start failed for channel [" + channel.getName() + "] during configuration reload - " +
                        e.getMessage());
            }
        }

        return true;
    }

    /**
     * Periodic self-healing pass for headless nodes (armed from {@link #start()} on a ~30s scheduler).  Channels
     * sometimes fail to come up or come up but never lock; this makes auto-start self-healing without any operator
     * action.  Two conservative, clearly-logged actions, both gated on the headless decode-readiness gate so nothing
     * fights first-run calibration/JMBE:
     *
     * <ol>
     *   <li><b>Restart of a not-processing auto-start channel.</b> Any {@code getAutoStartChannels()} entry that is
     *       currently not processing (never started, or died) is (re)started with the same per-channel guarded start
     *       used by {@link #startAutoStartChannels()} - a per-channel failure is logged and does not abort the pass.</li>
     *   <li><b>Force-restart of a stuck-IDLE trunking channel.</b> A P25/DMR channel that has been PROCESSING but has
     *       never reached CONTROL/CALL for longer than {@link #IDLE_RESTART_THRESHOLD_MS} is stopped and restarted
     *       once to force re-acquisition, rate-limited by {@link #IDLE_RESTART_COOLDOWN_MS} per channel.  Conventional
     *       (NBFM/AM) channels are deliberately excluded - IDLE is their normal resting state with no traffic.</li>
     * </ol>
     */
    private void selfHealChannels()
    {
        try
        {
            //Only headless nodes self-heal, and never while first-run calibration/JMBE gate is closed.
            if(!mHeadless)
            {
                return;
            }

            //Never while a config import/reload is in flight.  That operation shuts the processing manager down, swaps
            //the database, then rebuilds the channel model with NEW Channel instances.  A sweep landing in that window
            //sees the OLD auto-start channels as "not processing" and starts them — producing a processing chain bound
            //to a Channel that is about to be discarded.  It holds its tuner, never appears in /channels, cannot be
            //stopped through the API, and makes the incoming channel set fail with "No Tuner Available", which this
            //very method then retries forever.  The node stays down after a config push and the logs read like a tuner
            //fault.  The next sweep (30s) picks up any genuine work once the operation completes.
            if(mConfigurationManager.isExternalConfigurationOperationInProgress())
            {
                return;
            }

            if(mDecodeReadyGate != null && !mDecodeReadyGate.getAsBoolean())
            {
                return;
            }

            ChannelProcessingManager cpm = mConfigurationManager.getChannelProcessingManager();
            Map<Channel,String> stateByChannel = buildChannelStateLookup(cpm.getChannelMetadataModel());
            long now = System.currentTimeMillis();

            for(Channel channel : mConfigurationManager.getChannelModel().getAutoStartChannels())
            {
                int id = channel.getChannelID();

                //(a) Not processing - failed or never started. (Re)start it... unless the node agent stopped it
                //on purpose (low decode health) - the agent owns that channel's lifecycle until it unsuppresses.
                if(!channel.isProcessing())
                {
                    mChannelUnlockedSince.remove(id);

                    String name = channel.getName() != null ? channel.getName().trim() : "";

                    if(mSelfHealSuppressed.contains(name))
                    {
                        long lastNotice = mSuppressedNoticeAt.getOrDefault(name, 0L);

                        if(now - lastNotice >= SUPPRESSED_NOTICE_INTERVAL_MS)
                        {
                            mLog.info("self-heal: auto-start channel [" + name +
                                    "] suppressed by agent (low decode) - leaving stopped");
                            mSuppressedNoticeAt.put(name, now);
                        }

                        continue;
                    }

                    try
                    {
                        cpm.start(channel);
                        mLog.info("self-heal: started auto-start channel [" + channel.getName() +
                                "] that was not processing");
                    }
                    catch(ChannelException | RuntimeException e)
                    {
                        mLog.warn("self-heal: start failed for channel [" + channel.getName() + "] - " + e.getMessage());
                    }

                    continue;
                }

                //(b) Processing - check whether a trunking channel is stuck unlocked (never reached CONTROL/CALL).
                if(!isControlLockingChannel(channel))
                {
                    mChannelUnlockedSince.remove(id);
                    continue;
                }

                String state = stateByChannel.get(channel);

                if(isLockedState(state))
                {
                    //Healthy - clear any unlocked streak.
                    mChannelUnlockedSince.remove(id);
                    continue;
                }

                long since = mChannelUnlockedSince.computeIfAbsent(id, k -> now);
                long unlockedMs = now - since;
                long lastRestart = mChannelLastRestart.getOrDefault(id, 0L);

                //A trunking channel PROCESSING but not locked is now LEFT ALONE. Force-restarting it here churned
                //every sibling channel packed onto the same SDR: cpm.start() recenters the shared dongle, which
                //re-derives each sibling's DDC output processor and knocks locked control channels to IDLE for ~5s
                //- and a sibling that then crossed this same threshold got restarted in turn, a self-perpetuating
                //churn loop across the dongle. Stock sdrtrunk has no such heuristic and locks reliably. So we only
                //SURFACE the stuck channel (rate-limited) and let it re-acquire on its own; a genuinely wedged
                //channel is fixed by a manual restart or by spreading channels across SDRs, without churning peers.
                if(unlockedMs >= IDLE_RESTART_THRESHOLD_MS && (now - lastRestart) >= IDLE_RESTART_COOLDOWN_MS)
                {
                    mLog.warn("self-heal: trunking channel [" + channel.getName() + "] processing but not locked (state=" +
                            (state != null ? state : "PROCESSING") + ") for " + (unlockedMs / 1000) +
                            "s - leaving it (force-restart disabled: restarting churns sibling channels on the same SDR)");
                    mChannelLastRestart.put(id, now); //rate-limit this notice (no restart performed)
                }
            }
        }
        catch(Throwable t)
        {
            //Never let a self-heal pass throw out of the scheduler.
            mLog.warn("self-heal: pass error", t);
        }
    }

    /**
     * Builds a channel -&gt; state-text lookup from the live channel metadata model, mirroring the snapshot logic in
     * {@link #buildChannelList()}.  State text is the channel-state identifier (e.g. CONTROL, CALL, IDLE); channels
     * with no metadata row are simply absent from the map.
     */
    private Map<Channel,String> buildChannelStateLookup(ChannelMetadataModel mm)
    {
        Map<Channel,String> stateByChannel = new HashMap<>();

        //Locked snapshot: this one runs on the self-heal thread, and a torn read here is worse than a cosmetic
        //glitch — a missed or stale state feeds selfHealChannels() and produces exactly the "processing but not
        //locked" false alarm the timeslot aggregation below was written to stop.
        for(Map.Entry<ChannelMetadata,Channel> entry: mm.snapshot())
        {
            {
                ChannelMetadata meta = entry.getKey();

                if(meta != null)
                {
                    Channel ch = entry.getValue();

                    if(ch != null)
                    {
                        Identifier state = meta.getChannelStateIdentifier();
                        String stateText = state != null ? state.toString() : null;

                        //Aggregate across TIMESLOTS: a channel is locked if ANY of its metadata rows is.  DMR and
                        //P25 Phase 2 produce two rows per Channel, one per timeslot, and putIfAbsent kept whichever
                        //happened to come first.  When the control lock sat on the row that lost that race, a
                        //perfectly healthy trunking channel was read as unlocked, accumulated an unlocked streak, and
                        //logged the "processing but not locked" warning every 5 minutes forever — a permanent false
                        //alarm on exactly the decoder types this heuristic covers.
                        String existing = stateByChannel.get(ch);

                        if(existing == null || (!isLockedState(existing) && isLockedState(stateText)))
                        {
                            stateByChannel.put(ch, stateText);
                        }
                    }
                }
            }
        }

        return stateByChannel;
    }

    /**
     * Whether the channel's decoder is a trunking type whose healthy resting state is CONTROL, so a prolonged
     * unlocked (IDLE) state is a fault rather than the normal no-traffic resting state of a conventional channel.
     */
    private boolean isControlLockingChannel(Channel channel)
    {
        try
        {
            if(channel.getDecodeConfiguration() != null)
            {
                return CONTROL_LOCKING_DECODERS.contains(channel.getDecodeConfiguration().getDecoderType());
            }
        }
        catch(Exception e)
        {
            //best effort - treat as non-trunking (never force-restart) on any doubt
        }

        return false;
    }

    /**
     * Whether the given channel-state text represents an active/locked decode (CONTROL lock or a live call) rather
     * than an unlocked/idle state.  Null/blank and IDLE/FADE/RESET/TEARDOWN are considered not-locked.
     */
    private static boolean isLockedState(String stateText)
    {
        if(stateText == null)
        {
            return false;
        }

        switch(stateText)
        {
            case "CONTROL":
            case "CALL":
            case "ENCRYPTED":
            case "DATA":
            case "ACTIVE":
                return true;
            default:
                return false;
        }
    }

    //---------------------------------------------------------------------------------------------------------------
    // Activity lookups (stats/activity SQLite database)
    //---------------------------------------------------------------------------------------------------------------

    private void handleActivity(HttpExchange exchange)
    {
        try
        {
            if(!authorize(exchange))
            {
                return;
            }

            String path = exchange.getRequestURI().getPath();

            if(path.equals("/activity/status"))
            {
                Map<String,Object> body = new LinkedHashMap<>();

                if(mActivityLogService != null)
                {
                    P25ActivityLogStatus status = mActivityLogService.getStatus();
                    body.put("summaryConfigured", status.summaryConfigured());
                    body.put("detailedHistoryConfigured", status.detailedHistoryConfigured());
                    body.put("summaryActive", status.summaryActive());
                    body.put("historyActive", status.detailedHistoryActive());
                    body.put("retentionDays", status.retentionDays());
                    body.put("state", String.valueOf(status.state()));
                    body.put("databasePath", status.databasePath());
                    body.put("lastSuccessfulWriteMs", status.lastSuccessfulWriteMs());
                    body.put("recordsWritten", status.recordsWritten());
                    body.put("recordsDropped", status.recordsDropped());
                    body.put("lastError", status.lastError());
                }
                else
                {
                    body.put("state", "UNAVAILABLE");
                }

                sendJson(exchange, 200, body);
                return;
            }

            if(path.equals("/activity/call-site"))
            {
                Map<String,String> query = parseQuery(exchange.getRequestURI().getRawQuery());

                Integer tgid = parseInteger(query.get("tgid"));
                Long tsMs = parseLong(query.get("tsMs"));

                if(tgid == null || tsMs == null)
                {
                    sendJson(exchange, 400, error("tgid and tsMs query parameters are required"));
                    return;
                }

                Integer src = parseInteger(query.get("src"));
                Long freqHz = parseLong(query.get("freqHz"));
                Long windowMs = parseLong(query.get("windowMs"));

                sendJson(exchange, 200, mActivityLookup.findCallSite(tgid, src != null ? src : 0,
                        freqHz != null ? freqHz : 0L, tsMs, windowMs != null ? windowMs : 4000L));
                return;
            }

            if(path.equals("/activity/events"))
            {
                Map<String,String> query = parseQuery(exchange.getRequestURI().getRawQuery());

                Long sinceId = query.containsKey("sinceId") ? parseLong(query.get("sinceId")) : Long.valueOf(0L);
                Integer limit = query.containsKey("limit") ? parseInteger(query.get("limit")) : Integer.valueOf(200);
                String kinds = query.containsKey("kinds") ? query.get("kinds") : "calls";

                if(sinceId == null || limit == null || !(kinds.equals("calls") || kinds.equals("all")))
                {
                    sendJson(exchange, 400, error("malformed sinceId, limit or kinds query parameter"));
                    return;
                }

                sendJson(exchange, 200, mActivityLookup.recentEvents(sinceId, limit, !kinds.equals("all")));
                return;
            }

            sendJson(exchange, 404, error("not found"));
        }
        catch(Exception e)
        {
            sendError(exchange, e);
        }
        finally
        {
            exchange.close();
        }
    }

    /**
     * Read-only deep P25 site metadata for the node agent's site-view feed.  {@code GET /site/snapshots} returns
     * {@code {"sites":[ <site> ]}} - see {@link ControlSiteLookup} for the per-site ingest contract.  A busy/locked/
     * missing database yields an empty {@code sites} array rather than an error.
     */
    private void handleSite(HttpExchange exchange)
    {
        try
        {
            if(!authorize(exchange))
            {
                return;
            }

            String path = exchange.getRequestURI().getPath();

            if(path.equals("/site/snapshots"))
            {
                sendJson(exchange, 200, mSiteLookup.siteSnapshots());
                return;
            }

            sendJson(exchange, 404, error("not found"));
        }
        catch(Exception e)
        {
            sendError(exchange, e);
        }
        finally
        {
            exchange.close();
        }
    }

    /**
     * Parses a URL query string into a key/value map (URL-decoded, last value wins).
     */
    private static Map<String,String> parseQuery(String rawQuery)
    {
        Map<String,String> map = new LinkedHashMap<>();

        if(rawQuery == null || rawQuery.isEmpty())
        {
            return map;
        }

        for(String pair : rawQuery.split("&"))
        {
            if(pair.isEmpty())
            {
                continue;
            }

            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            map.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }

        return map;
    }

    private static Integer parseInteger(String text)
    {
        try
        {
            return text != null && !text.isEmpty() ? Integer.valueOf(text.trim()) : null;
        }
        catch(NumberFormatException nfe)
        {
            return null;
        }
    }

    private static Long parseLong(String text)
    {
        try
        {
            return text != null && !text.isEmpty() ? Long.valueOf(text.trim()) : null;
        }
        catch(NumberFormatException nfe)
        {
            return null;
        }
    }

    //---------------------------------------------------------------------------------------------------------------
    // Helpers
    //---------------------------------------------------------------------------------------------------------------

    /**
     * Validates the bearer token.  On failure, writes a 401 JSON response and returns false.
     */
    private boolean authorize(HttpExchange exchange) throws IOException
    {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        String provided = null;

        if(header != null && header.startsWith("Bearer "))
        {
            provided = header.substring(7);
        }

        byte[] a = (provided == null ? "" : provided).getBytes(StandardCharsets.UTF_8);
        byte[] b = (mToken == null ? "" : mToken).getBytes(StandardCharsets.UTF_8);

        if(MessageDigest.isEqual(a, b))
        {
            return true;
        }

        sendJson(exchange, 401, error("unauthorized"));
        exchange.close();
        return false;
    }

    /**
     * Maximum request body this server will buffer.
     *
     * The control API is loopback-bound and token-gated, so this is robustness
     * rather than security: readAllBytes on a runaway or malformed payload
     * would buffer it whole into the heap of a JVM that is also decoding P25.
     */
    private static final int MAXIMUM_BODY_BYTES = 32 * 1024 * 1024;

    /**
     * Reads a request body, refusing anything past MAXIMUM_BODY_BYTES rather
     * than growing the heap until something else fails.
     */
    private static byte[] readLimited(InputStream is) throws IOException
    {
        byte[] bytes = is.readNBytes(MAXIMUM_BODY_BYTES + 1);

        if(bytes.length > MAXIMUM_BODY_BYTES)
        {
            throw new IOException("request body exceeds " + MAXIMUM_BODY_BYTES + " bytes");
        }

        return bytes;
    }

    private JsonNode readBody(HttpExchange exchange) throws IOException
    {
        try(InputStream is = exchange.getRequestBody())
        {
            byte[] bytes = readLimited(is);

            if(bytes.length == 0)
            {
                return mMapper.createObjectNode();
            }

            return mMapper.readTree(bytes);
        }
    }

    private Map<String,Object> ok()
    {
        Map<String,Object> map = new LinkedHashMap<>();
        map.put("ok", true);
        return map;
    }

    private Map<String,Object> error(String message)
    {
        Map<String,Object> map = new LinkedHashMap<>();
        map.put("ok", false);
        map.put("error", message == null ? "error" : message);
        return map;
    }

    private void sendError(HttpExchange exchange, Exception e)
    {
        mLog.warn("Control server request error", e);

        try
        {
            Map<String,Object> map = new LinkedHashMap<>();
            map.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            sendJson(exchange, 500, map);
        }
        catch(IOException ignore)
        {
            //nothing more we can do
        }
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException
    {
        byte[] bytes = mMapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", CONTENT_TYPE_JSON);
        exchange.sendResponseHeaders(status, bytes.length);

        try(OutputStream os = exchange.getResponseBody())
        {
            os.write(bytes);
        }
    }
}
