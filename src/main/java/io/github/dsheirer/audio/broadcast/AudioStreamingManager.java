/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
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

package io.github.dsheirer.audio.broadcast;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.record.AudioCallRecorder;
import io.github.dsheirer.record.RecordFormat;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.util.ThreadPool;
import io.github.dsheirer.util.TimeStamp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Streams completed immutable audio calls by creating temporary recordings and enqueueing them for broadcast.
 */
public class AudioStreamingManager
{
    private static final Logger mLog = LoggerFactory.getLogger(AudioStreamingManager.class);
    private LinkedTransferQueue<CompletedAudioCall> mNewAudioCalls = new LinkedTransferQueue<>();
    private List<CompletedAudioCall> mAudioCalls = new ArrayList<>();
    private Listener<AudioRecording> mAudioRecordingListener;
    private BroadcastFormat mBroadcastFormat;
    private UserPreferences mUserPreferences;
    private final Consumer<CompletedAudioCall> mStreamedCallConsumer;
    private ScheduledFuture<?> mAudioSegmentProcessorFuture;
    private int mNextRecordingNumber = 1;

    /**
     * Constructs an instance
     * @param listener to receive completed audio recordings
     * @param broadcastFormat for temporary recordings
     * @param userPreferences to manage recording directories
     */
    public AudioStreamingManager(Listener<AudioRecording> listener, BroadcastFormat broadcastFormat, UserPreferences userPreferences)
    {
        this(listener, broadcastFormat, userPreferences, null);
    }

    public AudioStreamingManager(Listener<AudioRecording> listener, BroadcastFormat broadcastFormat,
                                 UserPreferences userPreferences,
                                 Consumer<CompletedAudioCall> streamedCallConsumer)
    {
        mAudioRecordingListener = listener;
        mBroadcastFormat = broadcastFormat;
        mUserPreferences = userPreferences;
        mStreamedCallConsumer = streamedCallConsumer;
    }

    /**
     * Starts the scheduled completed-call processor.
     */
    public void start()
    {
        if(mAudioSegmentProcessorFuture == null)
        {
            mAudioSegmentProcessorFuture = ThreadPool.SCHEDULED.scheduleAtFixedRate(new AudioSegmentProcessor(),
                0, 250, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Stops the scheduled completed-call processor.
     */
    public void stop()
    {
        if(mAudioSegmentProcessorFuture != null)
        {
            mAudioSegmentProcessorFuture.cancel(true);
            mAudioSegmentProcessorFuture = null;
        }

        mNewAudioCalls.clear();
        mAudioCalls.clear();
    }

    /**
     * Scheduled runnable to process completed calls.
     */
    public class AudioSegmentProcessor implements Runnable
    {
        /**
         * Creates a temporary streaming recording file path
         */
        private Path getTemporaryRecordingPath()
        {
            StringBuilder sb = new StringBuilder();
            sb.append(BroadcastModel.TEMPORARY_STREAM_FILE_SUFFIX);

            //Check for integer overflow and readjust negative value to 0
            if(mNextRecordingNumber < 0)
            {
                mNextRecordingNumber = 1;
            }

            int recordingNumber = mNextRecordingNumber++;

            sb.append(recordingNumber).append("_");
            sb.append(TimeStamp.getLongTimeStamp("_"));
            sb.append(mBroadcastFormat.getFileExtension());

            return mUserPreferences.getDirectoryPreference().getDirectoryStreaming().resolve(sb.toString());
        }

        /**
         * Processes a completed call for streaming by creating a temporary MP3 recording and submitting the recording
         * to the specific broadcast channel(s).
         * @param completedAudioCall to process for streaming
         * @param identifierCollection to use for the streamed audio recording
         * @param broadcastChannels to receive the audio recording
         */
        private boolean processAudioCall(CompletedAudioCall completedAudioCall,
                                         IdentifierCollection identifierCollection,
                                         Set<BroadcastChannel> broadcastChannels)
        {
            Path path = getTemporaryRecordingPath();
            long length = completedAudioCall.getDuration();

            try
            {
                AudioCallRecorder.write(completedAudioCall, path, RecordFormat.MP3, mUserPreferences,
                    identifierCollection);

                if(!Files.isRegularFile(path) || Files.size(path) <= 0)
                {
                    return false;
                }

                AudioRecording audioRecording = new AudioRecording(path, broadcastChannels, identifierCollection,
                    completedAudioCall.snapshot().startTimestamp(), length);
                mAudioRecordingListener.receive(audioRecording);
                return true;
            }
            catch(IOException ioe)
            {
                mLog.error("Error recording temporary stream MP3");
                return false;
            }
            catch(RuntimeException e)
            {
                mLog.warn("Error handing completed call to the streaming pipeline", e);
                return false;
            }
        }

        /**
         * Decomposes a patch group without allowing current alias state to expand the frozen routing decisions carried
         * by the completed call. Each routing key is claimed once in stable identifier order. A key contributed by a
         * losing receiver copy, or whose alias was removed after completion, is sent once with the original patch
         * identifiers instead of being dropped or sprayed across every member.
         */
        private boolean processPatchGroupTalkgroups(CompletedAudioCall completedAudioCall,
                                                    IdentifierCollection identifiers,
                                                    PatchGroup patchGroup)
        {
            Map<String, BroadcastChannel> frozenChannels =
                indexFrozenBroadcastChannels(completedAudioCall.snapshot().broadcastChannels());

            if(frozenChannels.isEmpty())
            {
                return false;
            }

            List<Identifier> patchIdentifiers = new ArrayList<>();
            patchIdentifiers.addAll(patchGroup.getPatchedTalkgroupIdentifiers());
            patchIdentifiers.addAll(patchGroup.getPatchedRadioIdentifiers());
            patchIdentifiers.sort(Comparator.comparingInt(this::stableIdentifierCategory)
                .thenComparing(this::stableIdentifierKey));
            AliasList aliasList = completedAudioCall.snapshot().aliasList();

            if(patchIdentifiers.isEmpty() || aliasList == null)
            {
                return processAudioCall(completedAudioCall, identifiers,
                    Set.copyOf(frozenChannels.values()));
            }

            Set<String> unclaimedRoutingKeys = new LinkedHashSet<>(frozenChannels.keySet());
            boolean sentToStreamer = false;

            for(Identifier identifier : patchIdentifiers)
            {
                Set<String> identifierRoutingKeys = new TreeSet<>();

                for(Alias alias : aliasList.getAliases(identifier))
                {
                    for(BroadcastChannel broadcastChannel : alias.getBroadcastChannels())
                    {
                        String routingKey = normalizeRoutingKey(broadcastChannel);

                        if(routingKey != null && unclaimedRoutingKeys.contains(routingKey))
                        {
                            identifierRoutingKeys.add(routingKey);
                        }
                    }
                }

                if(!identifierRoutingKeys.isEmpty())
                {
                    Set<BroadcastChannel> claimedChannels = new LinkedHashSet<>();

                    for(String routingKey : identifierRoutingKeys)
                    {
                        if(unclaimedRoutingKeys.remove(routingKey))
                        {
                            claimedChannels.add(frozenChannels.get(routingKey));
                        }
                    }

                    MutableIdentifierCollection decomposedIdentifiers =
                        new MutableIdentifierCollection(identifiers.getIdentifiers());
                    //Remove patch group TO identifier and replace it with the deterministic patched member.
                    decomposedIdentifiers.remove(Role.TO);
                    decomposedIdentifiers.update(identifier);
                    sentToStreamer |= processAudioCall(completedAudioCall, decomposedIdentifiers,
                        Set.copyOf(claimedChannels));
                }
            }

            if(!unclaimedRoutingKeys.isEmpty())
            {
                Set<BroadcastChannel> fallbackChannels = new LinkedHashSet<>();

                for(String routingKey : unclaimedRoutingKeys)
                {
                    fallbackChannels.add(frozenChannels.get(routingKey));
                }

                sentToStreamer |= processAudioCall(completedAudioCall, identifiers,
                    Set.copyOf(fallbackChannels));
            }

            return sentToStreamer;
        }

        /**
         * Restricts a completed non-patch call's frozen broadcast channel set to only the channels that an alias of
         * an identifier STILL resident in the call routes to - the final TO, the source radio (FROM), or any other
         * current identifier.
         *
         * A call accumulates a broadcast channel for every identifier the module ever saw while the call was open
         * (see MutableAudioCallBuilder). When a terminator is missed, or a new call is seeded from a stale identifier
         * collection, a previous talkgroup's TO can leave its broadcast channel in the frozen set. Streaming to that
         * raw set then uploads the call to a stream that belongs to the PREVIOUS talkgroup, tagged with the CURRENT
         * TO. Recomputing the channel set from the identifiers the call currently carries keeps it only on the
         * stream(s) those identifiers actually route to - mirroring MutableAudioCallBuilder.rebuildBroadcastChannels,
         * so this guard keeps exactly the set the builder would produce and prunes only channels routed by none of
         * the current identifiers.
         *
         * Both TO- and FROM-routed streams are preserved: an operator may attach a broadcast channel to a radio
         * (source) alias - "stream everything unit N transmits" - and that stream is legitimate even on a call whose
         * TO talkgroup routes elsewhere. Filtering by the TO alone would silently drop it. Patch groups are handled
         * separately (see {@link #processPatchGroupTalkgroups}) and never reach this method.
         *
         * Never drops the call to zero channels when a current identifier does route somewhere: if none of those
         * routes are present in the frozen set (unexpected), the current identifiers' alias channels are used
         * directly, so the call reaches its live streams rather than nowhere.
         */
        private Set<BroadcastChannel> broadcastChannelsForCurrentIdentifiers(CompletedAudioCall completedAudioCall,
                                                                             IdentifierCollection identifiers)
        {
            Map<String, BroadcastChannel> frozenChannels =
                indexFrozenBroadcastChannels(completedAudioCall.snapshot().broadcastChannels());
            AliasList aliasList = completedAudioCall.snapshot().aliasList();

            //Nothing to filter against, or no alias list to filter by: preserve prior behaviour.
            if(frozenChannels.isEmpty() || aliasList == null)
            {
                return Set.copyOf(frozenChannels.values());
            }

            //Routing keys that an alias of ANY identifier still resident in the call routes to (TO, FROM, ...).
            Set<String> currentRoutingKeys = new TreeSet<>();

            for(Identifier identifier : identifiers.getIdentifiers())
            {
                for(Alias alias : aliasList.getAliases(identifier))
                {
                    for(BroadcastChannel broadcastChannel : alias.getBroadcastChannels())
                    {
                        String routingKey = normalizeRoutingKey(broadcastChannel);

                        if(routingKey != null)
                        {
                            currentRoutingKeys.add(routingKey);
                        }
                    }
                }
            }

            //Keep only the frozen channels a current identifier routes to; collect the rest as stale drops.
            Set<BroadcastChannel> keptChannels = new LinkedHashSet<>();
            Set<String> droppedKeys = new TreeSet<>();

            for(Map.Entry<String, BroadcastChannel> entry : frozenChannels.entrySet())
            {
                if(currentRoutingKeys.contains(entry.getKey()))
                {
                    keptChannels.add(entry.getValue());
                }
                else
                {
                    droppedKeys.add(entry.getKey());
                }
            }

            //Never drop the call entirely when a current identifier does route somewhere but none of those routes
            //survive in the frozen set: route the call to where its current identifiers actually point.
            if(keptChannels.isEmpty() && !currentRoutingKeys.isEmpty())
            {
                for(Identifier identifier : identifiers.getIdentifiers())
                {
                    for(Alias alias : aliasList.getAliases(identifier))
                    {
                        keptChannels.addAll(alias.getBroadcastChannels());
                    }
                }
            }

            if(!droppedKeys.isEmpty() && mLog.isDebugEnabled())
            {
                mLog.debug("Dropped stale broadcast channel(s) " + droppedKeys + " from call with final TO [" +
                    identifiers.getToIdentifier() +
                    "] - those channels are routed only by an identifier no longer resident in the call");
            }

            return Set.copyOf(keptChannels);
        }

        private Map<String, BroadcastChannel> indexFrozenBroadcastChannels(
            Set<BroadcastChannel> broadcastChannels)
        {
            Map<String, BroadcastChannel> frozenChannels = new TreeMap<>();

            if(broadcastChannels != null)
            {
                for(BroadcastChannel broadcastChannel : broadcastChannels)
                {
                    String routingKey = normalizeRoutingKey(broadcastChannel);

                    if(routingKey != null)
                    {
                        frozenChannels.putIfAbsent(routingKey, broadcastChannel);
                    }
                }
            }

            return frozenChannels;
        }

        private String normalizeRoutingKey(BroadcastChannel broadcastChannel)
        {
            if(broadcastChannel == null || broadcastChannel.getChannelName() == null)
            {
                return null;
            }

            String routingKey = broadcastChannel.getChannelName().trim();
            return routingKey.isEmpty() ? null : routingKey;
        }

        private String stableIdentifierKey(Identifier identifier)
        {
            if(identifier == null)
            {
                return "";
            }

            String protocol = identifier.getProtocol() != null ? identifier.getProtocol().name() : "";
            String form = identifier.getForm() != null ? identifier.getForm().name() : "";
            String value = identifier.getValue() != null ? identifier.getValue().toString() : "";
            return protocol + '\u0000' + form + '\u0000' + value;
        }

        private int stableIdentifierCategory(Identifier identifier)
        {
            if(identifier == null || identifier.getForm() == null)
            {
                return 2;
            }

            return identifier.getForm() == Form.TALKGROUP ? 0 :
                identifier.getForm() == Form.RADIO ? 1 : 2;
        }

        /**
         * Main processing method to process completed calls.
         */
        private void processAudioSegments()
        {
            mNewAudioCalls.drainTo(mAudioCalls);

            Iterator<CompletedAudioCall> it = mAudioCalls.iterator();
            CompletedAudioCall completedAudioCall;
            while(it.hasNext())
            {
                completedAudioCall = it.next();
                boolean sentToStreamer = false;

                if(completedAudioCall.snapshot().duplicate() &&
                    mUserPreferences.getCallManagementPreference().isDuplicateStreamingSuppressionEnabled())
                {
                    it.remove();
                }
                else
                {
                    it.remove();

                    if(mAudioRecordingListener != null && completedAudioCall.snapshot().hasBroadcastChannels())
                    {
                        IdentifierCollection identifiers =
                            new IdentifierCollection(completedAudioCall.snapshot().identifierCollection().getIdentifiers());

                        if(identifiers.getToIdentifier() instanceof PatchGroupIdentifier patchGroupIdentifier)
                        {
                            if(mUserPreferences.getCallManagementPreference()
                                .getPatchGroupStreamingOption() == PatchGroupStreamingOption.TALKGROUPS)
                            {
                                sentToStreamer |= processPatchGroupTalkgroups(completedAudioCall, identifiers,
                                    patchGroupIdentifier.getValue());
                            }
                            else
                            {
                                sentToStreamer |= processAudioCall(completedAudioCall, identifiers,
                                    completedAudioCall.snapshot().broadcastChannels());
                            }
                        }
                        else
                        {
                            sentToStreamer |= processAudioCall(completedAudioCall, identifiers,
                                broadcastChannelsForCurrentIdentifiers(completedAudioCall, identifiers));
                        }
                    }
                }

                if(sentToStreamer)
                {
                    notifyStreamed(completedAudioCall);
                }
            }
        }

        @Override
        public void run()
        {
            try
            {
                processAudioSegments();
            }
            catch(Exception e)
            {
                mLog.error("Error processing completed audio calls for streaming", e);
            }
        }
    }

    public void receive(CompletedAudioCall completedAudioCall)
    {
        if(completedAudioCall != null)
        {
            mNewAudioCalls.add(completedAudioCall);
        }
    }

    private void notifyStreamed(CompletedAudioCall completedAudioCall)
    {
        if(mStreamedCallConsumer != null)
        {
            try
            {
                mStreamedCallConsumer.accept(completedAudioCall);
            }
            catch(RuntimeException e)
            {
                mLog.warn("Streamed-call stats listener failed", e);
            }
        }
    }
}
