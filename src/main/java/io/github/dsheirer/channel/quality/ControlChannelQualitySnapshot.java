/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel.quality;

import io.github.dsheirer.controller.channel.Channel;

/**
 * Immutable live quality measurement for the currently tuned trunked control channel.
 */
public record ControlChannelQualitySnapshot(Channel channel, String guid, long frequencyHz, long observedAtMs,
                                            boolean active, Double signalDbfs, Double averageSignalDbfs,
                                            Double minimumSignalDbfs, Double maximumSignalDbfs,
                                            Double decodeHealthPercent, long validFrames, long invalidFrames,
                                            long correctedBits, long syncLossBits, long droppedBits,
                                            long lastValidDecodeMs, long decodingSinceMs)
{
    /**
     * A snapshot that does not say when decoding began.  0 means "not known", which is what every snapshot built
     * before this field existed meant, and what a channel that has not decoded anything yet still means.
     */
    public ControlChannelQualitySnapshot(Channel channel, String guid, long frequencyHz, long observedAtMs,
                                         boolean active, Double signalDbfs, Double averageSignalDbfs,
                                         Double minimumSignalDbfs, Double maximumSignalDbfs,
                                         Double decodeHealthPercent, long validFrames, long invalidFrames,
                                         long correctedBits, long syncLossBits, long droppedBits,
                                         long lastValidDecodeMs)
    {
        this(channel, guid, frequencyHz, observedAtMs, active, signalDbfs, averageSignalDbfs, minimumSignalDbfs,
            maximumSignalDbfs, decodeHealthPercent, validFrames, invalidFrames, correctedBits, syncLossBits,
            droppedBits, lastValidDecodeMs, 0L);
    }
}
