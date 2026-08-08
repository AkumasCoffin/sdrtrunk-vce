/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
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
package io.github.dsheirer.gui;

import com.jidesoft.swing.JideSplitPane;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.application.ApplicationInfo;
import io.github.dsheirer.application.update.UpdateCheckResult;
import io.github.dsheirer.application.update.UpdateCheckService;
import io.github.dsheirer.audio.call.AudioCallCoordinator;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.audio.call.DuplicateCallPriorityProvider;
import io.github.dsheirer.audio.broadcast.AudioStreamingManager;
import io.github.dsheirer.audio.broadcast.BroadcastFormat;
import io.github.dsheirer.audio.broadcast.BroadcastStatusPanel;
import io.github.dsheirer.audio.playback.AudioPlaybackManager;
import io.github.dsheirer.channel.quality.ControlChannelQualityRegistry;
import io.github.dsheirer.controller.ControllerPanel;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelException;
import io.github.dsheirer.controller.channel.ChannelSelectionManager;
import io.github.dsheirer.control.ControlServer;
import io.github.dsheirer.database.SdrTrunkDatabaseBootstrap;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.gui.configuration.LegacyPlaylistImportDialog;
import io.github.dsheirer.gui.configuration.ViewConfigurationRequest;
import io.github.dsheirer.gui.bugreport.BugReportDialog;
import io.github.dsheirer.gui.icon.ViewIconManagerRequest;
import io.github.dsheirer.gui.preference.ViewUserPreferenceEditorRequest;
import io.github.dsheirer.gui.preference.encryption.ViewEncryptionKeyPreferenceEditorRequest;
import io.github.dsheirer.gui.startup.CoordinatedStartupDialog;
import io.github.dsheirer.gui.theme.ThemeManager;
import io.github.dsheirer.gui.viewer.ViewRecordingViewerRequest;
import io.github.dsheirer.gui.whatsnew.WhatsNewDialog;
import io.github.dsheirer.icon.IconModel;
import io.github.dsheirer.jmbe.JmbeCreator;
import io.github.dsheirer.jmbe.github.GitHub;
import io.github.dsheirer.jmbe.github.Release;
import io.github.dsheirer.log.ApplicationLog;
import io.github.dsheirer.map.MapService;
import io.github.dsheirer.metadata.site.SiteControlChannelLearner;
import io.github.dsheirer.module.log.EventLogger;
import io.github.dsheirer.module.log.EventLogManager;
import io.github.dsheirer.monitor.ResourceMonitor;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.decoder.JmbeLibraryPreference;
import io.github.dsheirer.preference.encryption.vault.EncryptionKeyVaultService;
import io.github.dsheirer.preference.portable.SqlitePreferencesFactory;
import io.github.dsheirer.preference.swing.JTableColumnWidthMonitor;
import io.github.dsheirer.portable.PortableApplicationPaths;
import io.github.dsheirer.portable.PortableDataRootLock;
import io.github.dsheirer.stats.activity.P25ActivityLogService;
import io.github.dsheirer.record.AudioRecordingManager;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.settings.SettingsManager;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerEvent;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.source.tuner.sdrplay.api.SDRPlayLibraryHelper;
import io.github.dsheirer.source.tuner.ui.TunerSpectralDisplayManager;
import io.github.dsheirer.spectrum.ShowTunerMenuItem;
import io.github.dsheirer.spectrum.SpectralDisplayPanel;
import io.github.dsheirer.stats.StatsWebServerService;
import io.github.dsheirer.util.ThreadPool;
import io.github.dsheirer.util.TimeStamp;
import io.github.dsheirer.vector.calibrate.CalibrationManager;
import java.awt.AWTException;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Robot;
import java.awt.desktop.QuitResponse;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.prefs.Preferences;
import javafx.embed.swing.JFXPanel;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.KeyStroke;
import javax.swing.JToggleButton;
import javax.swing.WindowConstants;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;

public class SDRTrunk implements Listener<TunerEvent>
{
    private static final Logger mLog = LoggerFactory.getLogger(SDRTrunk.class);
    private Preferences mPreferences;

    private static final String PREFERENCE_BROADCAST_STATUS_VISIBLE = "sdrtrunk.broadcast.status.visible";
    private static final String PREFERENCE_NOW_PLAYING_LOWER_VIEWS_VISIBLE = "sdrtrunk.now.playing.details.visible";
    private static final String PREFERENCE_RESOURCE_STATUS_VISIBLE = "sdrtrunk.resource.status.visible";
    private static final String PREFERENCE_UPDATE_FOOTER_MIGRATION =
        "sdrtrunk.resource.status.update.icon.migration.1";
    private static final String PREFERENCE_SYSTEMS_VISIBLE = "sdrtrunk.systems.visible";
    private static final String BASE_WINDOW_NAME = "sdrtrunk.main.window";
    private static final String CONTROLLER_PANEL_IDENTIFIER = BASE_WINDOW_NAME + ".control.panel";
    private static final String SPECTRAL_PANEL_IDENTIFIER = BASE_WINDOW_NAME + ".spectral.panel";
    private static final String WINDOW_FRAME_IDENTIFIER = BASE_WINDOW_NAME + ".frame";
    private static final String MAIN_SPLIT_PANE_DIVIDER_IDENTIFIER = BASE_WINDOW_NAME + ".split.pane.divider";
    private static final String SPECTRAL_DISPLAY_DIVIDER_IDENTIFIER = BASE_WINDOW_NAME + ".spectral.display.divider";
    private static final String NOW_PLAYING_SPLIT_PANE_DIVIDER_IDENTIFIER = "now.playing.split.pane.divider";
    private static final String CHANNEL_SPECTRUM_SPLIT_PANE_DIVIDER_IDENTIFIER = "channel.spectrum.panel.split.pane.divider";
    private static final int MAIN_SPECTRAL_MINIMUM_HEIGHT = 120;
    private static final int MAIN_CONTROLLER_MINIMUM_HEIGHT = 180;

    private boolean mBroadcastStatusVisible;
    private boolean mResourceStatusVisible;
    private boolean mNowPlayingLowerViewsVisible;
    private AudioCallCoordinator mAudioCallCoordinator;
    private AudioPlaybackManager mAudioPlaybackManager;
    private P25ActivityLogService mP25ActivityLogService;
    private StatsWebServerService mStatsWebServerService;
    private AudioRecordingManager mAudioRecordingManager;
    private AudioStreamingManager mAudioStreamingManager;
    private ControlChannelQualityRegistry mControlChannelQualityRegistry;
    private BroadcastStatusPanel mBroadcastStatusPanel;
    private ControllerPanel mControllerPanel;
    private IconModel mIconModel;
    private ConfigurationManager mConfigurationManager;
    private SettingsManager mSettingsManager;
    private SpectralDisplayPanel mSpectralPanel;
    private TunerSpectralDisplayManager mTunerSpectralDisplayManager;
    private JFrame mMainGui;
    private JideSplitPane mSplitPane;
    private JavaFxWindowManager mJavaFxWindowManager;
    private UserPreferences mUserPreferences;
    private TunerManager mTunerManager;
    private ApplicationLog mApplicationLog;
    private ResourceMonitor mResourceMonitor;
    private JFXPanel mResourceStatusPanel;
    private UpdateCheckService mUpdateCheckService;
    private volatile UpdateCheckResult mUpdateCheckResult = UpdateCheckResult.notChecked();
    private final AtomicBoolean mUpdateCheckInProgress = new AtomicBoolean();
    private volatile boolean mManualUpdateFeedbackRequested;
    private JMenuItem mCheckForUpdatesMenuItem;
    private JButton mConfigurationEditorShortcutButton;
    private JButton mUserPreferencesShortcutButton;
    private JMenuItem mEncryptionKeysItem;
    private JToggleButton mSystemsToggleButton;
    private JToggleButton mSpectrumWaterfallToggleButton;
    private boolean mShutdownProcessed;
    private boolean mSpectralPanelVisible;
    private boolean mSystemsVisible;
    private boolean mMainSplitPaneDividerRestored;
    private PortableDataRootLock mDataRootLock;
    private ControlServer mControlServer;

    private String mTitle;

    private SDRTrunk(UserPreferences userPreferences, PortableDataRootLock dataRootLock)
    {
        mUserPreferences = userPreferences;
        mDataRootLock = dataRootLock;
        mPreferences = Preferences.userNodeForPackage(SDRTrunk.class);
        mUpdateCheckService = new UpdateCheckService();
        mIconModel = new IconModel();

        if(!GraphicsEnvironment.isHeadless())
        {
            //Install the stored look-and-feel before realizing the first Swing component.
            ThemeManager.getInstance().initialize(mUserPreferences);
            mMainGui = new JFrame();
        }

        mApplicationLog = new ApplicationLog(mUserPreferences);
        mApplicationLog.start();
        if(mUserPreferences.getVoiceDecryptionModulePreference().getModuleManager().isLoaded())
        {
            mUserPreferences.getEncryptionKeyPreference().getVaultService().tryAutoUnlockSavedPassword();
        }

        //Note: invoke this early in the application lifecycle, before the TunerManager causes the sdrplay classes
        //to be loaded since the jextract auto-generated code attempts to load the library by name and that can fail
        //when the library was not installed into a normal/default location, particularly on windows OS systems.
        if(SDRPlayLibraryHelper.LOADED)
        {
            mLog.info("SDRPlay API native library preemptively loaded");
        }

        mResourceMonitor = new ResourceMonitor(mUserPreferences);

        ThreadPool.logSettings();

        //Register FontAwesome so we can use the fonts in Swing windows
        IconFontSwing.register(FontAwesome.getIconFont());

        mTunerManager = new TunerManager(mUserPreferences);
        mTunerManager.start();

        mSettingsManager = new SettingsManager();

        AliasModel aliasModel = new AliasModel();
        EventLogManager eventLogManager = new EventLogManager(aliasModel, mUserPreferences);
        mConfigurationManager = new ConfigurationManager(mUserPreferences, mTunerManager, aliasModel, eventLogManager, mIconModel);

        if(!GraphicsEnvironment.isHeadless())
        {
            mJavaFxWindowManager = new JavaFxWindowManager(mUserPreferences, mTunerManager, mConfigurationManager);
        }

        CalibrationManager calibrationManager = CalibrationManager.getInstance(mUserPreferences);
        final boolean calibrating = !calibrationManager.isCalibrated() &&
            !mUserPreferences.getVectorCalibrationPreference().isHideCalibrationDialog();

        new ChannelSelectionManager(mConfigurationManager.getChannelModel());

        mAudioPlaybackManager = new AudioPlaybackManager(mUserPreferences);

        mP25ActivityLogService = new P25ActivityLogService(mUserPreferences);

        mAudioRecordingManager = new AudioRecordingManager(mUserPreferences,
            mP25ActivityLogService::receiveRecordedCall);
        mAudioRecordingManager.start();

        mAudioStreamingManager = new AudioStreamingManager(mConfigurationManager.getBroadcastModel(), BroadcastFormat.MP3,
            mUserPreferences, mP25ActivityLogService::receiveStreamedCall);
        mAudioStreamingManager.start();

        boolean headless = GraphicsEnvironment.isHeadless();

        if(!headless)
        {
            //Headless nodes don't run the embedded stats web server - the control server is the remote interface.
            mStatsWebServerService = new StatsWebServerService(mUserPreferences,
                mConfigurationManager.getChannelProcessingManager(), mP25ActivityLogService);
        }

        mControlChannelQualityRegistry = new ControlChannelQualityRegistry();
        Consumer<CompletedAudioCall> webConsumer = mStatsWebServerService != null ?
            mStatsWebServerService::receive : call -> {};
        mAudioCallCoordinator = new AudioCallCoordinator(mUserPreferences, mAudioPlaybackManager,
            mAudioRecordingManager,
            mAudioStreamingManager, webConsumer, DuplicateCallPriorityProvider.NONE);

        mConfigurationManager.getChannelProcessingManager().addAudioCallListener(mAudioCallCoordinator);
        mConfigurationManager.getChannelProcessingManager().addChannelDecodeEventListener(
            mP25ActivityLogService.getDecodeEventListener());
        mConfigurationManager.getChannelProcessingManager().addControlChannelQualityListener(
            mP25ActivityLogService.getControlChannelQualityListener());
        mConfigurationManager.getChannelProcessingManager().addControlChannelQualityListener(
            mControlChannelQualityRegistry);
        mConfigurationManager.getChannelProcessingManager().addSiteMetadataListener(mP25ActivityLogService);
        mConfigurationManager.getChannelProcessingManager().addProtocolSiteMetadataListener(mP25ActivityLogService);
        if(mStatsWebServerService != null)
        {
            mP25ActivityLogService.addActivityCommitListener(mStatsWebServerService);
        }
        mConfigurationManager.getChannelProcessingManager().addSiteMetadataListener(mConfigurationManager.getBroadcastModel());
        mConfigurationManager.getChannelProcessingManager().addSiteMetadataListener(new SiteControlChannelLearner(mConfigurationManager));

        MapService mapService = new MapService(aliasModel);
        mConfigurationManager.getChannelProcessingManager().addDecodeEventListener(mapService);

        mNowPlayingLowerViewsVisible = mPreferences.getBoolean(PREFERENCE_NOW_PLAYING_LOWER_VIEWS_VISIBLE, true);
        mSystemsVisible = mPreferences.getBoolean(PREFERENCE_SYSTEMS_VISIBLE, true);

        if(!GraphicsEnvironment.isHeadless())
        {
            mControllerPanel = new ControllerPanel(mConfigurationManager, mAudioPlaybackManager, mIconModel, mapService,
                    mSettingsManager, mTunerManager, mUserPreferences, mStatsWebServerService, mSystemsVisible,
                    mNowPlayingLowerViewsVisible, visible -> {
                        mNowPlayingLowerViewsVisible = visible;
                        mPreferences.putBoolean(PREFERENCE_NOW_PLAYING_LOWER_VIEWS_VISIBLE, visible);
                    });
        }

        mSpectralPanel = new SpectralDisplayPanel(mConfigurationManager, mSettingsManager,
            mTunerManager.getDiscoveredTunerModel(), mUserPreferences, SPECTRAL_DISPLAY_DIVIDER_IDENTIFIER);

        mTunerSpectralDisplayManager = new TunerSpectralDisplayManager(mSpectralPanel,
            mConfigurationManager, mSettingsManager, mTunerManager.getDiscoveredTunerModel(), mUserPreferences);
        mTunerManager.getDiscoveredTunerModel().addListener(mTunerSpectralDisplayManager);
        mTunerManager.getDiscoveredTunerModel().addListener(this);

        mConfigurationManager.init();

        //Start the headless control server if a control port was specified on the command line.
        startControlServer(headless);

        if(GraphicsEnvironment.isHeadless())
        {
            mLog.info("starting main application headless");
            //No GUI to run the calibration dialog or click "install JMBE" — do both
            //here, synchronously, before channels start. On the genuine first run
            //(work actually performed) restart once so channels come up with the
            //calibration + JMBE fully applied (both are read at startup).
            boolean didFirstRunWork = performHeadlessSetup(calibrationManager);
            if(didFirstRunWork)
            {
                maybeRestartAfterFirstRunSetup();
            }
        }
        else
        {
            mLog.info("starting main application gui");

            //Initialize the GUI
            initGUI();
        }

        //Start the gui
        EventQueue.invokeLater(() -> {
            try
            {
                if(!GraphicsEnvironment.isHeadless())
                {
                    ThemeManager.getInstance().registerSwing(mMainGui);
                    mMainGui.setVisible(true);
                    checkForUpdates(false);

                    if(mSpectralPanelVisible)
                    {
                        Tuner tuner = mTunerSpectralDisplayManager.showFirstTuner();

                        if(tuner != null)
                        {
                            updateTitle(tuner.getPreferredName());
                        }
                        else
                        {
                            // Allow delayed tuner startup paths up to about 20 seconds to populate the first display.
                            mTunerSpectralDisplayManager.retryShowFirstTuner(1, java.util.concurrent.TimeUnit.SECONDS, 20);
                        }
                    }
                }
            }
            catch(Exception e)
            {
                mLog.error("Unable to finish initial GUI setup; continuing with post-launch startup", e);
            }

            try
            {
                startPostLaunchExperience(calibrating);
            }
            catch(Exception e)
            {
                mLog.error("Post-launch startup failed; starting configured channels without the startup dialog", e);
                EncryptionKeyVaultService vaultService = getLockedLaunchVault();

                if(vaultService != null)
                {
                    vaultService.disableForRun();
                }

                startChannelsWithoutDialog(mConfigurationManager.getChannelModel().getAutoStartChannels());
            }
        });
    }

    private void startPostLaunchExperience(boolean calibrationRequired)
    {
        List<Channel> channels = mConfigurationManager.getChannelModel().getAutoStartChannels();
        EncryptionKeyVaultService vaultService = getLockedLaunchVault();

        if(GraphicsEnvironment.isHeadless())
        {
            if(vaultService != null)
            {
                vaultService.disableForRun();
            }

            //HARD GATE: never start decoding until BOTH the CPU calibration and the JMBE voice codec are ready.
            //Decoding without JMBE yields calls with no audio; running uncalibrated wastes CPU.  The control
            //server's /config/reload will start the channels once both are in place (typically post-restart).
            if(!isReadyToDecodeHeadless())
            {
                return;
            }

            startChannelsWithoutDialog(channels);
            return;
        }

        CoordinatedStartupDialog dialog = new CoordinatedStartupDialog(mMainGui, mUserPreferences,
            WhatsNewDialog.getPendingReleaseNotes(), calibrationRequired, vaultService, channels,
            mConfigurationManager.getChannelProcessingManager());

        if(dialog.hasSteps())
        {
            dialog.showExperience();
        }
    }

    private void startChannelsWithoutDialog(List<Channel> channels)
    {
        for(Channel channel: channels)
        {
            try
            {
                mLog.info("Auto-starting channel " + channel.getName());
                mConfigurationManager.getChannelProcessingManager().start(channel);
            }
            catch(ChannelException | RuntimeException e)
            {
                mLog.error("Channel: " + channel.getName() + " auto-start failed: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Starts the headless control server when the {@code sdrtrunk.control.port} system property has been set (from the
     * {@code --control-port} command line argument).  The control auth token is read from the
     * {@code SDRTRUNK_CONTROL_TOKEN} environment variable.
     *
     * @param headless true if the application is running headless.
     */
    private void startControlServer(boolean headless)
    {
        String portProperty = System.getProperty("sdrtrunk.control.port");

        if(portProperty == null || portProperty.isEmpty())
        {
            return;
        }

        try
        {
            int port = Integer.parseInt(portProperty.trim());
            String token = System.getenv("SDRTRUNK_CONTROL_TOKEN");
            mControlServer = new ControlServer(mTunerManager, mConfigurationManager, mP25ActivityLogService,
                mUserPreferences, this::isReadyToDecodeHeadless, headless, port, token);
            mControlServer.start();
            mLog.info("Headless control server started on port [" + port + "]");
        }
        catch(Exception e)
        {
            mLog.error("Unable to start headless control server on port [" + portProperty + "]", e);
        }
    }

    /**
     * First-run setup for a HEADLESS node. There's no GUI to run the CPU
     * calibration dialog or to click the "install JMBE" button, so do both here.
     * Both persist, so subsequent starts skip straight through. Runs synchronously
     * (before channels auto-start) so voice audio is available when decoding begins.
     */
    private boolean performHeadlessSetup(CalibrationManager calibrationManager)
    {
        boolean didWork = false;

        //1) CPU (vector/SIMD) calibration — normally a first-run dialog.
        try
        {
            if(!calibrationManager.isCalibrated())
            {
                mLog.info("headless: running one-time CPU calibration (this can take a minute)...");
                calibrationManager.calibrate();
                mLog.info("headless: CPU calibration complete");
                didWork = true;
            }
            else
            {
                mLog.info("headless: CPU calibration already done");
            }
        }
        catch(Exception e)
        {
            mLog.error("headless: CPU calibration failed; continuing with default implementations", e);
        }

        //2) JMBE audio library (AMBE/IMBE voice codec) — required to produce voice
        //   audio. Without it a call decodes + shows in Now Playing but yields no
        //   audio to record/stream.
        try
        {
            if(ensureJmbeLibraryHeadless())
            {
                didWork = true;
            }
        }
        catch(Exception e)
        {
            mLog.error("headless: JMBE auto-install failed; voice audio will be unavailable", e);
        }

        return didWork;
    }

    /**
     * Ensures a JMBE audio library is installed and referenced, downloading +
     * building the latest release (the same flow as the GUI's JMBE editor) when
     * absent. No-op once installed.
     */
    private boolean ensureJmbeLibraryHeadless() throws Exception
    {
        JmbeLibraryPreference jmbePref = mUserPreferences.getJmbeLibraryPreference();
        Path existing = jmbePref.getPathJmbeLibrary();
        if(existing != null && Files.exists(existing))
        {
            mLog.info("headless: JMBE audio library present [" + existing + "]");
            return false;
        }

        mLog.info("headless: JMBE audio library missing - auto-installing from GitHub (one-time)...");
        Release release = GitHub.getLatestRelease(JmbeCreator.GITHUB_JMBE_RELEASES_URL);
        if(release == null)
        {
            mLog.error("headless: could not resolve the latest JMBE release; voice audio unavailable");
            return false;
        }

        Path jmbeDir = mUserPreferences.getDirectoryPreference().getDirectoryApplicationRoot().resolve("jmbe");
        Files.createDirectories(jmbeDir);
        Path library = jmbeDir.resolve("jmbe-" + release.getVersion() + ".jar");

        JmbeCreator creator = new JmbeCreator(release, library);
        CountDownLatch latch = new CountDownLatch(1);
        creator.completeProperty().addListener((obs, oldV, complete) -> {
            if(complete)
            {
                latch.countDown();
            }
        });
        creator.execute(); //async build on a background thread
        if(!creator.completeProperty().get())
        {
            latch.await(5, TimeUnit.MINUTES);
        }

        if(!creator.hasErrors() && Files.exists(library))
        {
            jmbePref.setPathJmbeLibrary(library);
            mLog.info("headless: JMBE audio library installed [" + library + "]");
            return true;
        }

        mLog.error("headless: JMBE library build did not complete; voice audio unavailable");
        return false;
    }

    /**
     * Whether headless channels may auto-start. Gated ONLY on CPU calibration:
     * it runs during first-run setup and a mid-calibration start would fight it
     * for the CPU (the one restart afterwards clears this). JMBE is deliberately
     * NOT a gate — it is only needed for voice AUDIO, whereas control-channel
     * decoding (which produces all the call/site/talkgroup/radio activity the
     * data pipeline consumes) needs no codec. A failed JMBE install must not
     * leave a node silent on metadata; it just means no audio until JMBE lands.
     * The gate only applies headless - GUI builds always return true.
     */
    private boolean isReadyToDecodeHeadless()
    {
        if(!GraphicsEnvironment.isHeadless())
        {
            return true;
        }

        if(!CalibrationManager.getInstance().isCalibrated())
        {
            mLog.info("headless: deferring channel auto-start until CPU calibration completes");
            return false;
        }

        Path jmbe = mUserPreferences.getJmbeLibraryPreference().getPathJmbeLibrary();
        if(jmbe == null || !Files.exists(jmbe))
        {
            mLog.warn("headless: starting channels WITHOUT the JMBE codec — control-channel decoding and activity " +
                    "logging work, but decoded voice calls will have no audio until JMBE is installed");
        }
        return true;
    }

    /**
     * After a genuine first-run setup (calibration and/or JMBE install), restart
     * SDR-Trunk so channels come up with the calibration + JMBE applied (both are
     * read at startup). A restart COUNTER in the (writable) app root bounds this
     * to a few restarts — enough for calibration then a (possibly retried) JMBE
     * install to each settle, but capped so it can never loop even if the persisted
     * "calibrated" flag / JMBE path fail to save (e.g. HOME not writable).
     */
    private void maybeRestartAfterFirstRunSetup()
    {
        final int MAX_RESTARTS = 3;
        Path counter = mUserPreferences.getDirectoryPreference().getDirectoryApplicationRoot()
                .resolve(".headless-setup-restarts");

        int count = 0;
        try
        {
            if(Files.exists(counter))
            {
                count = Integer.parseInt(Files.readString(counter).trim());
            }
        }
        catch(Exception ignore)
        {
            count = 0;
        }

        if(count >= MAX_RESTARTS)
        {
            mLog.warn("headless: first-run setup did work again but already restarted " + count + " times - NOT " +
                    "restarting (avoiding a loop). Calibration/JMBE prefs may not be persisting; ensure HOME is writable.");
            return;
        }

        try
        {
            Files.writeString(counter, Integer.toString(count + 1));
        }
        catch(Exception e)
        {
            mLog.error("headless: could not write restart counter; NOT restarting (avoiding a possible loop)", e);
            return;
        }

        mLog.info("headless: first-run setup did work - restarting SDR-Trunk (restart " + (count + 1) + " of " +
                MAX_RESTARTS + ") so channels start with calibration + JMBE applied");
        System.exit(0); //the node supervisor relaunches; a subsequent clean run auto-starts channels
    }

    private EncryptionKeyVaultService getLockedLaunchVault()
    {
        if(!mUserPreferences.getVoiceDecryptionModulePreference().getModuleManager().isLoaded())
        {
            return null;
        }

        EncryptionKeyVaultService vaultService = mUserPreferences.getEncryptionKeyPreference().getVaultService();

        if(!vaultService.hasVault() || vaultService.isUnlocked() || !vaultService.isPromptOnLaunch())
        {
            return null;
        }

        return vaultService;
    }

    /**
     * Initialize the contents of the frame.
     */
    private void initGUI()
    {
        mMainGui.setLayout(new MigLayout("insets 0 0 0 0 ", "[grow,fill]", "[]0[grow,fill]0[shrink 0]"));
        ApplicationIcon.apply(mMainGui);

        /**
         * Setup main JFrame window
         */
        mTitle = ApplicationInfo.getDisplayName();
        mMainGui.setTitle(mTitle);

        Point location = mUserPreferences.getSwingPreference().getLocation(WINDOW_FRAME_IDENTIFIER);
        Dimension dimension = mUserPreferences.getSwingPreference().getDimension(WINDOW_FRAME_IDENTIFIER);

        if(location != null)
        {
            mMainGui.setLocation(location);
        }
        else
        {
            mMainGui.setLocationRelativeTo(null);
        }
        mMainGui.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        mMainGui.addWindowListener(new ShutdownMonitor());
        registerQuitHandler();

        mSpectralPanel.setPreferredSize(new Dimension(1280, 300));
        mSpectralPanel.setMinimumSize(new Dimension(0, MAIN_SPECTRAL_MINIMUM_HEIGHT));
        mControllerPanel.setPreferredSize(new Dimension(1280, 500));
        mControllerPanel.setMinimumSize(new Dimension(0, MAIN_CONTROLLER_MINIMUM_HEIGHT));

        if(dimension != null)
        {
            Dimension spectral = mUserPreferences.getSwingPreference().getDimension(SPECTRAL_PANEL_IDENTIFIER);
            if(spectral != null)
            {
                Dimension pref = mSpectralPanel.getPreferredSize();
                mSpectralPanel.setPreferredSize(new Dimension(pref.width, spectral.height));
            }

            Dimension controller = mUserPreferences.getSwingPreference().getDimension(CONTROLLER_PANEL_IDENTIFIER);
            if(controller != null)
            {
                Dimension pref = mControllerPanel.getPreferredSize();
                mControllerPanel.setPreferredSize(new Dimension(pref.width, controller.height));
            }

            mMainGui.setSize(dimension);

            if(mUserPreferences.getSwingPreference().getMaximized(WINDOW_FRAME_IDENTIFIER, false))
            {
                mMainGui.setExtendedState(Frame.MAXIMIZED_BOTH);
            }
        }
        else
        {
            mMainGui.setSize(new Dimension(1280, 800));
        }
        mSplitPane = new JideSplitPane(JideSplitPane.VERTICAL_SPLIT);
        mSplitPane.setDividerSize(5);
        mSplitPane.addComponentListener(new ComponentAdapter()
        {
            @Override
            public void componentResized(ComponentEvent e)
            {
                if(mSpectralPanelVisible)
                {
                    restoreMainSplitPaneDividerLocation();
                }
            }
        });
        mSpectralPanelVisible = mUserPreferences.getSpectrumPreference().isDisplayEnabled();

        if(mSpectralPanelVisible)
        {
            mSplitPane.add(mSpectralPanel);
        }

        mSplitPane.add(mControllerPanel);
        mBroadcastStatusVisible = mPreferences.getBoolean(PREFERENCE_BROADCAST_STATUS_VISIBLE, false);

        //Show broadcast status panel when user requests - disabled by default
        if(mBroadcastStatusVisible)
        {
            mSplitPane.add(getBroadcastStatusPanel());
        }

        if(mSpectralPanelVisible)
        {
            EventQueue.invokeLater(this::restoreMainSplitPaneDividerLocation);
        }

        mMainGui.add(getMainControlPanel(), "cell 0 0,growx");
        mMainGui.add(mSplitPane, "cell 0 1,grow");

        mResourceMonitor.start();
        mResourceStatusVisible = initializeResourceStatusVisibility();
        if(mResourceStatusVisible)
        {
            mMainGui.add(getResourceStatusPanel(), "cell 0 2,growx");
        }

        /**
         * Menu items
         */
        JMenuBar menuBar = new JMenuBar();
        mMainGui.setJMenuBar(menuBar);

        JMenu fileMenu = new JMenu("File");
        menuBar.add(fileMenu);

        JMenuItem importLegacyPlaylistMenu = new JMenuItem("Import Legacy Playlist XML...");
        importLegacyPlaylistMenu.addActionListener(event -> LegacyPlaylistImportDialog.show(mMainGui,
            mConfigurationManager, mUserPreferences.getDirectoryPreference().getDirectoryApplicationRoot()));
        fileMenu.add(importLegacyPlaylistMenu);
        fileMenu.addSeparator();

        JMenuItem exitMenu = new JMenuItem("Exit");
        exitMenu.addActionListener(event -> {
                processShutdown();
                System.exit(0);
            }
        );

        fileMenu.add(exitMenu);

        JMenu viewMenu = new JMenu("View");

        JMenuItem viewConfigurationItem = new JMenuItem("Configuration Editor");
        viewConfigurationItem.setIcon(IconFontSwing.buildIcon(FontAwesome.PLAY_CIRCLE_O, 12));
        viewConfigurationItem.addActionListener(e -> MyEventBus.getGlobalEventBus().post(new ViewConfigurationRequest()));
        viewMenu.add(viewConfigurationItem);

        mEncryptionKeysItem = new JMenuItem("Encryption Keys");
        mEncryptionKeysItem.setIcon(IconFontSwing.buildIcon(FontAwesome.KEY, 12));
        mEncryptionKeysItem.addActionListener(e -> MyEventBus.getGlobalEventBus().post(new ViewEncryptionKeyPreferenceEditorRequest()));
        mEncryptionKeysItem.setVisible(mUserPreferences.getVoiceDecryptionModulePreference().getModuleManager().isLoaded());
        mUserPreferences.getVoiceDecryptionModulePreference().getModuleManager().loadedProperty()
            .addListener((observable, oldValue, loaded) -> EventQueue.invokeLater(() -> {
                mEncryptionKeysItem.setVisible(loaded);

                if(!loaded)
                {
                    mUserPreferences.getEncryptionKeyPreference().getVaultService().lock();
                }
            }));
        viewMenu.add(mEncryptionKeysItem);

        viewMenu.add(new JSeparator());

        JMenuItem viewApplicationLogsMenu = new JMenuItem("Application Log Files");
        viewApplicationLogsMenu.setIcon(IconFontSwing.buildIcon(FontAwesome.FOLDER_OPEN_O, 12));
        viewApplicationLogsMenu.addActionListener(arg0 ->
                openFileExplorer(mUserPreferences.getDirectoryPreference().getDirectoryApplicationLog().toFile()));
        viewMenu.add(viewApplicationLogsMenu);

        JMenuItem viewRecordingsMenuItem = new JMenuItem("Audio Recordings");
        viewRecordingsMenuItem.setIcon(IconFontSwing.buildIcon(FontAwesome.FOLDER_OPEN_O, 12));
        viewRecordingsMenuItem.addActionListener(arg0 ->
                openFileExplorer(mUserPreferences.getDirectoryPreference().getDirectoryRecording().toFile()));
        viewMenu.add(viewRecordingsMenuItem);

        JMenuItem viewEventLogsMenu = new JMenuItem("Channel Event Log Files");
        viewEventLogsMenu.setIcon(IconFontSwing.buildIcon(FontAwesome.FOLDER_OPEN_O, 12));
        viewEventLogsMenu.addActionListener(arg0 ->
                openFileExplorer(mUserPreferences.getDirectoryPreference().getDirectoryEventLog().toFile()));
        viewMenu.add(viewEventLogsMenu);

        JMenuItem iconManagerMenu = new JMenuItem("Icon Manager");
        iconManagerMenu.setIcon(IconFontSwing.buildIcon(FontAwesome.PICTURE_O, 12));
        iconManagerMenu.addActionListener(arg0 -> MyEventBus.getGlobalEventBus().post(new ViewIconManagerRequest()));
        viewMenu.add(iconManagerMenu);

        JMenuItem recordingViewerMenu = new JMenuItem("Message Recording Viewer (.bits)");
        recordingViewerMenu.setIcon(IconFontSwing.buildIcon(FontAwesome.BRAILLE, 12));
        recordingViewerMenu.addActionListener(e -> MyEventBus.getGlobalEventBus().post(new ViewRecordingViewerRequest()));
        viewMenu.add(recordingViewerMenu);

        JMenuItem viewScreenCapturesMenu = new JMenuItem("Screen Captures");
        viewScreenCapturesMenu.setIcon(IconFontSwing.buildIcon(FontAwesome.FOLDER_OPEN_O, 12));
        viewScreenCapturesMenu.addActionListener(arg0 ->
                openFileExplorer(mUserPreferences.getDirectoryPreference().getDirectoryScreenCapture().toFile()));
        viewMenu.add(viewScreenCapturesMenu);

        JMenuItem preferencesItem = new JMenuItem("User Preferences");
        preferencesItem.setIcon(IconFontSwing.buildIcon(FontAwesome.COG, 12));
        preferencesItem.addActionListener(e -> MyEventBus.getGlobalEventBus().post(new ViewUserPreferenceEditorRequest()));
        viewMenu.add(preferencesItem);

        viewMenu.add(new JSeparator());
        viewMenu.add(new TunersMenu());
        viewMenu.add(new JSeparator());
        JMenuItem resetColumnWidthsMenuItem = new JMenuItem("Reset Table Column Widths");
        resetColumnWidthsMenuItem.addActionListener(e -> {
            int removed = JTableColumnWidthMonitor.resetSavedColumnWidths(mUserPreferences);
            JOptionPane.showMessageDialog(mMainGui, "Reset " + removed + " saved table column width setting" +
                    (removed == 1 ? "." : "s."), "Table Column Widths Reset", JOptionPane.INFORMATION_MESSAGE);
        });
        viewMenu.add(resetColumnWidthsMenuItem);
        viewMenu.add(new JSeparator());
        viewMenu.add(new BroadcastStatusVisibleMenuItem());
        viewMenu.add(new ResourceStatusVisibleMenuItem());

        menuBar.add(viewMenu);

        JMenuItem screenCaptureItem = new JMenuItem("Screen Capture");
        screenCaptureItem.setIcon(IconFontSwing.buildIcon(FontAwesome.CAMERA, 12));
        screenCaptureItem.setMnemonic(KeyEvent.VK_C);
        screenCaptureItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, ActionEvent.ALT_MASK));
        screenCaptureItem.setMaximumSize(screenCaptureItem.getPreferredSize());
        screenCaptureItem.addActionListener(arg0 -> {
            try
            {
                Robot robot = new Robot();

                final BufferedImage image = robot.createScreenCapture(mMainGui.getBounds());

                String filename = TimeStamp.getTimeStamp("_") + "_screen_capture.png";

                final Path captureFile = mUserPreferences.getDirectoryPreference().getDirectoryScreenCapture().resolve(filename);

                ThreadPool.CACHED.submit(() -> {
                    try
                    {
                        ImageIO.write(image, "png", captureFile.toFile());
                    }
                    catch(IOException e)
                    {
                        mLog.error("Couldn't write screen capture to file [" + captureFile + "]", e);
                    }
                });
            }
            catch(AWTException e)
            {
                mLog.error("Exception while taking screen capture", e);
            }
        });

        menuBar.add(screenCaptureItem);

        JMenu helpMenu = new JMenu("Help");
        if(WhatsNewDialog.hasCurrent())
        {
            JMenuItem whatsNewItem = new JMenuItem("What's New");
            whatsNewItem.addActionListener(event -> WhatsNewDialog.showCurrent(mMainGui));
            helpMenu.add(whatsNewItem);
            helpMenu.add(new JSeparator());
        }

        mCheckForUpdatesMenuItem = new JMenuItem("Check for Updates");
        mCheckForUpdatesMenuItem.setIcon(IconFontSwing.buildIcon(FontAwesome.DOWNLOAD, 12));
        mCheckForUpdatesMenuItem.addActionListener(event -> {
            if(mUpdateCheckResult.isUpdateAvailable())
            {
                openUpdateReleasePage(mUpdateCheckResult.releaseUri());
            }
            else
            {
                checkForUpdates(true);
            }
        });
        helpMenu.add(mCheckForUpdatesMenuItem);
        helpMenu.add(new JSeparator());

        JMenuItem bugReportItem = new JMenuItem("Submit Bug Report...");
        bugReportItem.setIcon(IconFontSwing.buildIcon(FontAwesome.BUG, 12));
        bugReportItem.addActionListener(event ->
            new BugReportDialog(mMainGui, mUserPreferences, mTunerManager).setVisible(true));
        helpMenu.add(bugReportItem);
        helpMenu.add(new JSeparator());
        JMenuItem creditsItem = new JMenuItem("Credits & Licensing");
        creditsItem.addActionListener(event -> new CreditsDialog(mMainGui).setVisible(true));
        helpMenu.add(creditsItem);
        menuBar.add(helpMenu);
    }

    private boolean initializeResourceStatusVisibility()
    {
        if(!mPreferences.getBoolean(PREFERENCE_UPDATE_FOOTER_MIGRATION, false))
        {
            mPreferences.putBoolean(PREFERENCE_RESOURCE_STATUS_VISIBLE, true);
            mPreferences.putBoolean(PREFERENCE_UPDATE_FOOTER_MIGRATION, true);
            return true;
        }

        return mPreferences.getBoolean(PREFERENCE_RESOURCE_STATUS_VISIBLE, true);
    }

    private void checkForUpdates(boolean manual)
    {
        if(manual)
        {
            mManualUpdateFeedbackRequested = true;
        }

        if(!mUpdateCheckInProgress.compareAndSet(false, true))
        {
            if(manual)
            {
                mCheckForUpdatesMenuItem.setText("Checking for Updates...");
                mCheckForUpdatesMenuItem.setEnabled(false);
            }

            return;
        }

        if(manual)
        {
            mCheckForUpdatesMenuItem.setText("Checking for Updates...");
            mCheckForUpdatesMenuItem.setEnabled(false);
        }

        ThreadPool.CACHED.execute(() -> {
            UpdateCheckResult result = mUpdateCheckService.check();
            mUpdateCheckResult = result;
            mUpdateCheckInProgress.set(false);
            boolean showNonAvailableResult = mManualUpdateFeedbackRequested;
            mManualUpdateFeedbackRequested = false;

            if(result.state() == UpdateCheckResult.State.UNAVAILABLE)
            {
                mLog.warn("Unable to check for updates: {}", result.detail());
            }

            EventQueue.invokeLater(() -> updateCheckMenuItem(result, showNonAvailableResult));
        });
    }

    private void updateCheckMenuItem(UpdateCheckResult result, boolean showNonAvailableResult)
    {
        mCheckForUpdatesMenuItem.setEnabled(true);

        if(result.isUpdateAvailable())
        {
            mCheckForUpdatesMenuItem.setText("Update Available — " + result.version());
        }
        else if(showNonAvailableResult && result.state() == UpdateCheckResult.State.CURRENT)
        {
            mCheckForUpdatesMenuItem.setText("Check for Updates — Up to Date");
        }
        else if(showNonAvailableResult)
        {
            mCheckForUpdatesMenuItem.setText("Check for Updates — Unable to Check");
        }
        else
        {
            mCheckForUpdatesMenuItem.setText("Check for Updates");
        }
    }

    private void openUpdateReleasePage(URI releaseUri)
    {
        EventQueue.invokeLater(() -> {
            try
            {
                if(releaseUri != null && Desktop.isDesktopSupported() &&
                    Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                {
                    Desktop.getDesktop().browse(releaseUri);
                }
            }
            catch(Exception e)
            {
                mLog.error("Unable to open update release page", e);
            }
        });
    }

    /**
     * Performs shutdown operations
     */
    private void openFileExplorer(File directory)
    {
        try
        {
            Desktop.getDesktop().open(directory);
        }
        catch(Exception e)
        {
            mLog.error("Couldn't open file explorer", e);
            JOptionPane.showMessageDialog(mMainGui,
                    "Can't launch file explorer - files are located at: " + directory,
                    "Can't launch file explorer",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void processShutdown()
    {
        if(mShutdownProcessed)
        {
            return;
        }

        mShutdownProcessed = true;
        mLog.info("Application shutdown started ...");

        if(mControlServer != null)
        {
            mControlServer.stop();
            mControlServer = null;
        }

        if(!GraphicsEnvironment.isHeadless())
        {
            mUserPreferences.getSwingPreference().setLocation(WINDOW_FRAME_IDENTIFIER, mMainGui.getLocation());
            mUserPreferences.getSwingPreference().setDimension(WINDOW_FRAME_IDENTIFIER, mMainGui.getSize());
            mUserPreferences.getSwingPreference().setMaximized(WINDOW_FRAME_IDENTIFIER,
                (mMainGui.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH);
            mPreferences.putBoolean(PREFERENCE_SYSTEMS_VISIBLE, mSystemsVisible);
            if(mSpectralPanelVisible)
            {
                mUserPreferences.getSwingPreference().setDimension(SPECTRAL_PANEL_IDENTIFIER, mSpectralPanel.getSize());
                mUserPreferences.getSwingPreference().setInt(MAIN_SPLIT_PANE_DIVIDER_IDENTIFIER,
                    getMainSplitPaneDividerLocation());
            }

            mUserPreferences.getSwingPreference().setDimension(CONTROLLER_PANEL_IDENTIFIER, mControllerPanel.getSize());
            mUserPreferences.getSwingPreference().setInt(SPECTRAL_DISPLAY_DIVIDER_IDENTIFIER,
                mSpectralPanel.getSplitPaneDividerLocation());
            mUserPreferences.getSwingPreference().setInt(NOW_PLAYING_SPLIT_PANE_DIVIDER_IDENTIFIER,
                mControllerPanel.getNowPlayingPanel().getSplitPaneDividerLocation());
            mUserPreferences.getSwingPreference().setInt(CHANNEL_SPECTRUM_SPLIT_PANE_DIVIDER_IDENTIFIER,
                mControllerPanel.getNowPlayingPanel().getChannelSpectrumPanelDividerLocation());
            mUserPreferences.getSwingPreference().flush();
            mControllerPanel.dispose();
            mJavaFxWindowManager.shutdown();
        }
        mLog.info("Stopping channels ...");
        if(mStatsWebServerService != null)
        {
            if(mP25ActivityLogService != null)
            {
                mP25ActivityLogService.removeActivityCommitListener(mStatsWebServerService);
            }

            mStatsWebServerService.close();
        }
        if(mControlChannelQualityRegistry != null)
        {
            mConfigurationManager.getChannelProcessingManager().removeControlChannelQualityListener(
                mControlChannelQualityRegistry);
        }
        mConfigurationManager.getChannelProcessingManager().shutdown();
        if(mP25ActivityLogService != null)
        {
            mConfigurationManager.getChannelProcessingManager().removeChannelDecodeEventListener(
                mP25ActivityLogService.getDecodeEventListener());
            mConfigurationManager.getChannelProcessingManager().removeControlChannelQualityListener(
                mP25ActivityLogService.getControlChannelQualityListener());
            mConfigurationManager.getChannelProcessingManager().removeSiteMetadataListener(mP25ActivityLogService);
            mConfigurationManager.getChannelProcessingManager()
                .removeProtocolSiteMetadataListener(mP25ActivityLogService);
            mP25ActivityLogService.dispose();
        }
        EventLogger.flushPendingWrites();
        if(mAudioCallCoordinator != null)
        {
            mAudioCallCoordinator.dispose();
        }
        if(mAudioPlaybackManager != null)
        {
            mAudioPlaybackManager.dispose();
            mAudioPlaybackManager = null;
        }
        mAudioRecordingManager.stop();
        if(mControlChannelQualityRegistry != null)
        {
            mControlChannelQualityRegistry.clear();
        }
        mResourceMonitor.stop();

        if(!GraphicsEnvironment.isHeadless())
        {
            mLog.info("Stopping spectral display ...");
            mSpectralPanel.clearTuner();
        }
        mLog.info("Stopping tuners ...");
        mTunerManager.stop();
        mLog.info("Shutdown complete.");
        mApplicationLog.stop();
        SqlitePreferencesFactory.shutdown();

        if(mDataRootLock != null)
        {
            try
            {
                mDataRootLock.close();
            }
            catch(IOException e)
            {
                mLog.error("Unable to release the portable data lock", e);
            }

            mDataRootLock = null;
        }
    }

    private void registerQuitHandler()
    {
        if(!Desktop.isDesktopSupported())
        {
            return;
        }

        Desktop desktop = Desktop.getDesktop();

        if(!desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER))
        {
            return;
        }

        desktop.setQuitHandler((quitEvent, quitResponse) -> {
            try
            {
                processShutdown();
                quitResponse.performQuit();
            }
            catch(Exception e)
            {
                mLog.error("Error while processing application quit", e);
                quitResponse.cancelQuit();
            }
        });
    }

    /**
     * Lazy constructor for broadcast status panel
     */
    private BroadcastStatusPanel getBroadcastStatusPanel()
    {
        if(mBroadcastStatusPanel == null)
        {
            mBroadcastStatusPanel = new BroadcastStatusPanel(mConfigurationManager.getBroadcastModel(), mUserPreferences,
                "application.broadcast.status.panel");
            mBroadcastStatusPanel.setPreferredSize(new Dimension(880, 70));
            mBroadcastStatusPanel.getTable().setEnabled(false);
        }

        return mBroadcastStatusPanel;
    }

    private JPanel getMainControlPanel()
    {
        JPanel panel = new JPanel(new MigLayout("insets 2 6 2 6", "[][][grow,fill][][]", "[]"));
        panel.add(getConfigurationEditorShortcutButton());
        panel.add(getUserPreferencesShortcutButton());
        panel.add(new JPanel(), "grow");
        panel.add(getSystemsToggleButton());
        panel.add(mControllerPanel.getNowPlayingPanel().getLowerViewsToggleButton());
        panel.add(getSpectrumWaterfallToggleButton());
        updateSystemsToggleButton();
        return panel;
    }

    private JButton getConfigurationEditorShortcutButton()
    {
        if(mConfigurationEditorShortcutButton == null)
        {
            mConfigurationEditorShortcutButton = new JButton(IconFontSwing.buildIcon(FontAwesome.PLAY_CIRCLE_O, 14));
            mConfigurationEditorShortcutButton.setFocusable(false);
            mConfigurationEditorShortcutButton.setToolTipText("Configuration Editor");
            mConfigurationEditorShortcutButton.addActionListener(e ->
                MyEventBus.getGlobalEventBus().post(new ViewConfigurationRequest()));
        }

        return mConfigurationEditorShortcutButton;
    }

    private JButton getUserPreferencesShortcutButton()
    {
        if(mUserPreferencesShortcutButton == null)
        {
            mUserPreferencesShortcutButton = new JButton(IconFontSwing.buildIcon(FontAwesome.COG, 14));
            mUserPreferencesShortcutButton.setFocusable(false);
            mUserPreferencesShortcutButton.setToolTipText("User Preferences");
            mUserPreferencesShortcutButton.addActionListener(e ->
                MyEventBus.getGlobalEventBus().post(new ViewUserPreferenceEditorRequest()));
        }

        return mUserPreferencesShortcutButton;
    }

    private JToggleButton getSystemsToggleButton()
    {
        if(mSystemsToggleButton == null)
        {
            mSystemsToggleButton = new JToggleButton("Systems");
            mSystemsToggleButton.setFocusable(false);
            mSystemsToggleButton.addActionListener(event ->
                EventQueue.invokeLater(() -> setSystemsVisible(!mSystemsVisible)));
            updateSystemsToggleButton();
        }

        return mSystemsToggleButton;
    }

    private void setSystemsVisible(boolean visible)
    {
        if(mSystemsVisible != visible)
        {
            mSystemsVisible = visible;
            mControllerPanel.setSystemsVisible(visible);
            mPreferences.putBoolean(PREFERENCE_SYSTEMS_VISIBLE, visible);
            mControllerPanel.revalidate();
            mControllerPanel.repaint();
            mMainGui.revalidate();
            mMainGui.repaint();
        }

        updateSystemsToggleButton();
    }

    private void updateSystemsToggleButton()
    {
        if(mSystemsToggleButton != null)
        {
            mSystemsToggleButton.setSelected(mSystemsVisible);
            mSystemsToggleButton.setIcon(IconFontSwing.buildIcon(mSystemsVisible ?
                FontAwesome.CHEVRON_DOWN : FontAwesome.CHEVRON_UP, 12));
            mSystemsToggleButton.setToolTipText(mSystemsVisible ? "Hide Systems" : "Show Systems");
            mControllerPanel.getNowPlayingPanel().getLowerViewsToggleButton().setEnabled(mSystemsVisible);
        }
    }

    private JToggleButton getSpectrumWaterfallToggleButton()
    {
        if(mSpectrumWaterfallToggleButton == null)
        {
            mSpectrumWaterfallToggleButton = new JToggleButton("Spectrum");
            mSpectrumWaterfallToggleButton.setFocusable(false);
            mSpectrumWaterfallToggleButton.addActionListener(event ->
                EventQueue.invokeLater(() -> setSpectralPanelVisible(!mSpectralPanelVisible)));
            updateSpectrumWaterfallToggleButton();
        }

        return mSpectrumWaterfallToggleButton;
    }

    private void setSpectralPanelVisible(boolean visible)
    {
        setSpectralPanelVisible(visible, null);
    }

    private void setSpectralPanelVisible(boolean visible, Tuner preferredTuner)
    {
        if(mSpectralPanelVisible == visible)
        {
            if(visible && preferredTuner != null)
            {
                mSpectralPanel.showTuner(preferredTuner);
                updateTitle(preferredTuner.getPreferredName());
            }

            updateSpectrumWaterfallToggleButton();
            return;
        }

        if(visible)
        {
            mSplitPane.add(mSpectralPanel, 0);
            mSpectralPanelVisible = true;
            mUserPreferences.getSpectrumPreference().setDisplayEnabled(true);
            mMainSplitPaneDividerRestored = false;
            restoreMainSplitPaneDividerLocation();
            EventQueue.invokeLater(this::restoreMainSplitPaneDividerLocation);

            if(mTunerSpectralDisplayManager != null)
            {
                Tuner tuner = preferredTuner;

                if(tuner != null)
                {
                    mSpectralPanel.showTuner(tuner);
                }
                else
                {
                    tuner = mTunerSpectralDisplayManager.showFirstTuner();
                }

                if(tuner != null)
                {
                    updateTitle(tuner.getPreferredName());
                }
            }
        }
        else
        {
            saveMainSplitPaneDividerLocation();
            mSpectralPanel.clearTuner();
            mSplitPane.remove(mSpectralPanel);
            mSpectralPanelVisible = false;
            mUserPreferences.getSpectrumPreference().setDisplayEnabled(false);
            updateTitle(null);
        }

        mSplitPane.revalidate();
        mSplitPane.repaint();
        mMainGui.revalidate();
        mMainGui.repaint();
        updateSpectrumWaterfallToggleButton();
    }

    private void restoreMainSplitPaneDividerLocation()
    {
        if(!mMainSplitPaneDividerRestored && mSplitPane != null && mSpectralPanelVisible)
        {
            int location = mUserPreferences.getSwingPreference().getInt(MAIN_SPLIT_PANE_DIVIDER_IDENTIFIER,
                mSpectralPanel.getPreferredSize().height);
            mMainSplitPaneDividerRestored = SplitPaneDividerHelper.restore(mSplitPane, 0, location,
                MAIN_SPECTRAL_MINIMUM_HEIGHT, true);
        }
    }

    private void saveMainSplitPaneDividerLocation()
    {
        int savedLocation = mUserPreferences.getSwingPreference().getInt(MAIN_SPLIT_PANE_DIVIDER_IDENTIFIER,
            mSpectralPanel.getPreferredSize().height);
        mUserPreferences.getSwingPreference().setInt(MAIN_SPLIT_PANE_DIVIDER_IDENTIFIER,
            SplitPaneDividerHelper.getDividerLocationOrDefault(mSplitPane, 0, savedLocation,
                MAIN_SPECTRAL_MINIMUM_HEIGHT, true));
    }

    private int getMainSplitPaneDividerLocation()
    {
        int savedLocation = mUserPreferences.getSwingPreference().getInt(MAIN_SPLIT_PANE_DIVIDER_IDENTIFIER,
            mSpectralPanel.getPreferredSize().height);
        return SplitPaneDividerHelper.getDividerLocationOrDefault(mSplitPane, 0, savedLocation,
            MAIN_SPECTRAL_MINIMUM_HEIGHT, true);
    }

    private void updateSpectrumWaterfallToggleButton()
    {
        if(mSpectrumWaterfallToggleButton != null)
        {
            mSpectrumWaterfallToggleButton.setSelected(mSpectralPanelVisible);
            mSpectrumWaterfallToggleButton.setIcon(IconFontSwing.buildIcon(mSpectralPanelVisible ?
                FontAwesome.CHEVRON_UP : FontAwesome.CHEVRON_DOWN, 12));
            mSpectrumWaterfallToggleButton.setToolTipText(mSpectralPanelVisible ?
                "Collapse Spectrum and Waterfall" : "Expand Spectrum and Waterfall");
        }
    }

    /**
     * Lazy constructor for resource status panel
     */
    private JFXPanel getResourceStatusPanel()
    {

        if(mResourceStatusPanel == null)
        {
            mResourceStatusPanel = mJavaFxWindowManager.getStatusPanel(mResourceMonitor,
                mUserPreferences.getEncryptionKeyPreference().getVaultService(),
                mUserPreferences.getVoiceDecryptionModulePreference().getModuleManager(),
                mStatsWebServerService::getNavigationState, () -> mUpdateCheckResult,
                this::openUpdateReleasePage);
        }

        return mResourceStatusPanel;
    }

    @Override
    public void receive(TunerEvent event)
    {
        switch(event.getEvent())
        {
            case REQUEST_MAIN_SPECTRAL_DISPLAY:
                EventQueue.invokeLater(() -> {
                    if(!mSpectralPanelVisible)
                    {
                        setSpectralPanelVisible(true, event.getTuner());
                    }
                    else if(event.hasTuner())
                    {
                        updateTitle(event.getTuner().getPreferredName());
                    }
                });
                break;
            case REQUEST_CLEAR_MAIN_SPECTRAL_DISPLAY:
                EventQueue.invokeLater(() -> updateTitle(null));
                break;
            case NOTIFICATION_SHUTTING_DOWN:
                Tuner currentTuner = mSpectralPanel.getTuner();

                if(event.hasTuner() && event.getTuner().equals(currentTuner) || currentTuner == null)
                {
                    updateTitle(null);
                }
                break;
        }
    }

    /**
     * Updates the title bar with the tuner name
     * @param tunerName optional
     */
    private void updateTitle(String tunerName)
    {
        if(tunerName != null)
        {
            mMainGui.setTitle(mTitle + " - " + tunerName);
        }
        else
        {
            mMainGui.setTitle(mTitle);
        }
    }

    public class ShutdownMonitor extends WindowAdapter
    {
        @Override
        public void windowClosing(WindowEvent e)
        {
            processShutdown();
        }
    }

    /**
     * Broadcast status panel visible toggle menu item
     */
    public class BroadcastStatusVisibleMenuItem extends JCheckBoxMenuItem
    {
        public BroadcastStatusVisibleMenuItem()
        {
            super("Show Streaming Status");
            setSelected(mBroadcastStatusVisible);
            addActionListener(e -> {
                mBroadcastStatusVisible = !mBroadcastStatusVisible;
                EventQueue.invokeLater(() -> {
                    if(mBroadcastStatusVisible)
                    {
                        mSplitPane.add(getBroadcastStatusPanel());
                    }
                    else
                    {
                        mSplitPane.remove(getBroadcastStatusPanel());
                    }
                    mMainGui.revalidate();
                });
                mPreferences.putBoolean(PREFERENCE_BROADCAST_STATUS_VISIBLE, mBroadcastStatusVisible);
                setSelected(mBroadcastStatusVisible);
            });
        }
    }

    /**
     * Resource status panel visible toggle menu item
     */
    public class ResourceStatusVisibleMenuItem extends JCheckBoxMenuItem
    {
        public ResourceStatusVisibleMenuItem()
        {
            super("Show Status Footer");
            setSelected(mResourceStatusVisible);
            addActionListener(e -> {
                mResourceStatusVisible = !mResourceStatusVisible;
                EventQueue.invokeLater(() -> {
                    if(mResourceStatusVisible)
                    {
                        mMainGui.add(getResourceStatusPanel(), "cell 0 2,growx");
                    }
                    else
                    {
                        mMainGui.remove(getResourceStatusPanel());
                    }
                    mMainGui.revalidate();
                });
                mPreferences.putBoolean(PREFERENCE_RESOURCE_STATUS_VISIBLE, mResourceStatusVisible);
                setSelected(mResourceStatusVisible);
            });
        }
    }

    public class TunersMenu extends JMenu
    {
        public TunersMenu()
        {
            super("Tuners");

            addMenuListener(new MenuListener()
            {
                @Override
                public void menuSelected(MenuEvent e)
                {
                    removeAll();

                    for(DiscoveredTuner discoveredTuner: mTunerManager.getAvailableTuners())
                    {
                        add(new ShowTunerMenuItem(mTunerManager.getDiscoveredTunerModel(), discoveredTuner.getTuner(),
                            mUserPreferences.getSpectrumPreference()));
                    }
                }

                @Override
                public void menuDeselected(MenuEvent e) { /* no action required */ }
                @Override
                public void menuCanceled(MenuEvent e) { /* no action required */ }
            });
        }

    }

    /**
     * Launch the application.
     *
     * Supported command line arguments (parsed BEFORE any AWT/Swing class is touched and BEFORE the data root
     * is resolved):
     *   --headless                      run without a GUI (sets java.awt.headless=true).
     *   --control-port &lt;int&gt;            start the headless control server on the given loopback port (WS on port+1).
     *   --app-root &lt;dir&gt;                relocate the application data root (sets the portable data root property).
     *   --stats-logging on|off          enable/disable stats summary logging.
     *   --stats-detailed-history on|off enable/disable detailed stats history.
     *   --stats-retention-days &lt;int&gt;    stats logging retention period in days.
     *
     * Database bootstrap arguments (--fresh/--import-xml/--upgrade-data/--upgrade-current) pass through to
     * {@link SdrTrunkDatabaseBootstrap}.  The control auth token is read from the SDRTRUNK_CONTROL_TOKEN
     * environment variable.
     */
    public static void main(String[] args)
    {
        //Must run before PortableApplicationPaths.getDataRoot() is called and before any AWT class initializes.
        parseArguments(args);

        System.setProperty("apple.awt.application.name", "sdrtrunk-vce");
        PortableDataRootLock dataRootLock = null;

        try
        {
            Path dataRoot = PortableApplicationPaths.getDataRoot();
            Path databasePath = SdrTrunkDatabasePath.getDatabasePath(dataRoot);

            if(Files.isRegularFile(databasePath))
            {
                dataRootLock = PortableDataRootLock.acquire(dataRoot);
            }

            SdrTrunkDatabaseBootstrap.BootstrapResult bootstrap = SdrTrunkDatabaseBootstrap.run(args);

            if(!bootstrap.startApplication())
            {
                if(dataRootLock != null)
                {
                    dataRootLock.close();
                }

                return;
            }

            if(dataRootLock == null)
            {
                dataRootLock = PortableDataRootLock.acquire(dataRoot);
            }

            SqlitePreferencesFactory.install(databasePath);
            UserPreferences userPreferences = new UserPreferences();

            if(bootstrap.initializeNewPreferences())
            {
                userPreferences.getApplicationPreference().setStatsLoggingEnabled(true);
            }

            //Command-line stats overrides (--stats-logging / --stats-detailed-history / --stats-retention-days)
            //win over the new-preferences default above.
            applyStatsArguments(userPreferences);

            new SDRTrunk(userPreferences, dataRootLock);
            dataRootLock = null;
        }
        catch(Exception e)
        {
            SqlitePreferencesFactory.shutdown();

            if(dataRootLock != null)
            {
                try
                {
                    dataRootLock.close();
                }
                catch(IOException closeFailure)
                {
                    e.addSuppressed(closeFailure);
                }
            }

            String message = "sdrtrunk-vce could not start.\n\n" + e.getMessage();
            System.err.println(message);
            e.printStackTrace(System.err);

            if(!GraphicsEnvironment.isHeadless())
            {
                JOptionPane.showMessageDialog(null, message, "sdrtrunk-vce Startup Error",
                    JOptionPane.ERROR_MESSAGE);
            }

            System.exit(1);
        }
    }

    /**
     * Parses command line arguments.  Must be invoked before any AWT/Swing class is referenced (so headless mode
     * takes effect before the toolkit initializes) and before the portable data root is resolved (so --app-root
     * takes effect).  Unrecognized arguments are ignored - the database bootstrap flags are parsed separately by
     * {@link SdrTrunkDatabaseBootstrap}.
     *
     * @param args command line arguments.
     */
    private static void parseArguments(String[] args)
    {
        if(args == null)
        {
            return;
        }

        for(int i = 0; i < args.length; i++)
        {
            String arg = args[i];

            switch(arg)
            {
                case "--headless":
                    //Must be the very first thing we do so the toolkit initializes headless.
                    System.setProperty("java.awt.headless", "true");
                    break;
                case "--control-port":
                    if(i + 1 < args.length)
                    {
                        System.setProperty("sdrtrunk.control.port", args[++i]);
                    }
                    break;
                case "--app-root":
                    if(i + 1 < args.length)
                    {
                        //Read lazily by PortableApplicationPaths.getDataRoot().
                        System.setProperty(PortableApplicationPaths.DATA_ROOT_PROPERTY, args[++i]);
                    }
                    break;
                case "--stats-logging":
                    if(i + 1 < args.length)
                    {
                        System.setProperty("sdrtrunk.stats.logging", args[++i]);
                    }
                    break;
                case "--stats-detailed-history":
                    if(i + 1 < args.length)
                    {
                        System.setProperty("sdrtrunk.stats.detailed.history", args[++i]);
                    }
                    break;
                case "--stats-retention-days":
                    if(i + 1 < args.length)
                    {
                        System.setProperty("sdrtrunk.stats.retention.days", args[++i]);
                    }
                    break;
                default:
                    //ignore unrecognized arguments (database bootstrap flags pass through)
                    break;
            }
        }
    }

    /**
     * Applies the stats system properties captured by {@link #parseArguments(String[])} to the application
     * preferences.  No-op for any property that was not supplied on the command line.
     */
    private static void applyStatsArguments(UserPreferences userPreferences)
    {
        String logging = System.getProperty("sdrtrunk.stats.logging");

        if(logging != null && !logging.isEmpty())
        {
            userPreferences.getApplicationPreference().setStatsLoggingEnabled(parseOnOff(logging));
        }

        String detailedHistory = System.getProperty("sdrtrunk.stats.detailed.history");

        if(detailedHistory != null && !detailedHistory.isEmpty())
        {
            userPreferences.getApplicationPreference().setStatsDetailedHistoryEnabled(parseOnOff(detailedHistory));
        }

        String retentionDays = System.getProperty("sdrtrunk.stats.retention.days");

        if(retentionDays != null && !retentionDays.isEmpty())
        {
            try
            {
                userPreferences.getApplicationPreference().setStatsLoggingRetentionDays(
                    Integer.parseInt(retentionDays.trim()));
            }
            catch(NumberFormatException nfe)
            {
                mLog.warn("Ignoring invalid --stats-retention-days value [" + retentionDays + "]");
            }
        }
    }

    /**
     * Parses an on/off style command-line value ("on"/"true"/"1" are true; anything else is false).
     */
    private static boolean parseOnOff(String value)
    {
        String normalized = value.trim();
        return "on".equalsIgnoreCase(normalized) || "true".equalsIgnoreCase(normalized) || "1".equals(normalized);
    }
}
