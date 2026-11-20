/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */
package io.github.dsheirer.module.decode.p25.phase1;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.decode.config.WithCallTimeout;
import io.github.dsheirer.source.tuner.channel.ChannelSpecification;

/**
 * APCO25 Phase 1 conventional decoder configuration.
 */
public class DecodeConfigP25Conventional extends DecodeConfiguration implements WithCallTimeout
{
    private Modulation mModulation = Modulation.C4FM;

    /**
     * Seconds of inactivity before the call state fades and, for a traffic channel, the channel is
     * torn down and its tuner released.  Defaults to the standard call timeout, so a user-configured
     * conventional channel behaves exactly as before.  The uplink monitor raises it (see
     * P25TrafficChannelManager) so an uplink channel keeps its tuner long enough to receive a whole
     * multi-block subscriber packet: a location report can span several seconds, far longer than the
     * one second a downlink traffic channel is given.  Not serialised - it is only ever set in-process
     * on the uplink monitor's channels, never saved in a user's channel configuration.
     */
    private int mCallTimeoutSeconds = DecodeConfiguration.DEFAULT_CALL_TIMEOUT_DELAY_SECONDS;

    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.P25_CONVENTIONAL;
    }

    @JsonIgnore
    @Override
    public int getCallTimeoutSeconds()
    {
        return mCallTimeoutSeconds;
    }

    /**
     * Sets the seconds of inactivity before this channel fades out and releases its tuner.
     * @param callTimeoutSeconds timeout in seconds
     */
    public void setCallTimeoutSeconds(int callTimeoutSeconds)
    {
        mCallTimeoutSeconds = callTimeoutSeconds;
    }

    public Modulation getModulation()
    {
        return mModulation;
    }

    public void setModulation(Modulation modulation)
    {
        mModulation = modulation != null ? modulation : Modulation.C4FM;
    }

    /**
     * Source channel specification for this decoder.
     */
    @JsonIgnore
    @Override
    public ChannelSpecification getChannelSpecification()
    {
        return new ChannelSpecification(50000.0, 12500, 5750.0, 6500.0);
    }
}
