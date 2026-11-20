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
package io.github.dsheirer.control;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.event.PlottableDecodeEvent;
import io.github.dsheirer.sample.Listener;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jdesktop.swingx.mapviewer.GeoPosition;

/**
 * Ring buffer of the positions the decoders reported, for the headless control server.
 *
 * <p>The decoders broadcast a {@link PlottableDecodeEvent} for every position they decode, whatever carried it: a
 * P25 Motorola unit GPS or L3Harris talker GPS link control word, an LRRP packet on a data channel, DMR, NXDN.  The
 * activity log stores such an event without its coordinates, and the general {@link EventBuffer} holds only the
 * last few hundred events of every kind, so a position is gone from it within seconds on a busy system.  This
 * buffer keeps positions only, with their coordinates, each under a sequence number a poller can resume from.</p>
 *
 * <p>Nothing is persisted: the sequence restarts at 1 with the process.  {@link #summary()} carries the time this
 * buffer was created so a poller can tell a restart from an idle system.</p>
 */
public class GpsBuffer implements Listener<IDecodeEvent>
{
    private static final int DEFAULT_CAPACITY = 2000;

    private final int mCapacity;
    private final AliasModel mAliasModel;
    private final long mStartedAtMs = System.currentTimeMillis();
    private final Deque<Position> mPositions = new ArrayDeque<>();
    private final Object mLock = new Object();
    private long mSequence;
    private long mInvalid;
    private long mLastAtMs;

    /**
     * Constructs a buffer with the default capacity (2000).
     * @param aliasModel for resolving FROM/TO alias names, or null to disable alias resolution.
     */
    public GpsBuffer(AliasModel aliasModel)
    {
        this(DEFAULT_CAPACITY, aliasModel);
    }

    /**
     * Constructs a buffer with the specified capacity.
     * @param capacity maximum number of positions to retain.
     * @param aliasModel for resolving FROM/TO alias names, or null to disable alias resolution.
     */
    public GpsBuffer(int capacity, AliasModel aliasModel)
    {
        mCapacity = capacity > 0 ? capacity : DEFAULT_CAPACITY;
        mAliasModel = aliasModel;
    }

    @Override
    public void receive(IDecodeEvent event)
    {
        if(!(event instanceof PlottableDecodeEvent plottable))
        {
            return;
        }

        GeoPosition location = plottable.getLocation();

        //A radio without a fix reports 0/0.  Counted, so "the system sends positions but none are usable" can be
        //told from "the system sends none", and otherwise dropped.
        if(location == null || !plottable.isValidLocation())
        {
            synchronized(mLock)
            {
                mInvalid++;
            }

            return;
        }

        Position position = new Position();
        position.at = event.getTimeStart();
        position.lat = location.getLatitude();
        position.lon = location.getLongitude();

        try
        {
            if(event.getEventType() != null)
            {
                position.type = event.getEventType().name();
            }

            if(event.getProtocol() != null)
            {
                position.protocol = event.getProtocol().name();
            }

            IdentifierCollection identifiers = event.getIdentifierCollection();

            if(identifiers != null)
            {
                Identifier from = firstIdentifierOf(identifiers, Role.FROM);
                Identifier to = firstIdentifierOf(identifiers, Role.TO);
                position.from = from != null ? from.toString() : null;
                position.to = to != null ? to.toString() : null;

                AliasList aliasList = mAliasModel != null ? mAliasModel.getAliasList(identifiers) : null;

                if(aliasList != null)
                {
                    position.fromAlias = aliasNames(aliasList, from);
                    position.toAlias = aliasNames(aliasList, to);
                }
            }

            IChannelDescriptor descriptor = event.getChannelDescriptor();

            if(descriptor != null)
            {
                position.channel = descriptor.toString();
                position.frequencyHz = descriptor.getDownlinkFrequency();
            }
        }
        catch(Exception e)
        {
            //Best effort: the coordinates are what matters, the rest is context.
        }

        synchronized(mLock)
        {
            position.seq = ++mSequence;
            mLastAtMs = position.at;
            mPositions.addLast(position);

            while(mPositions.size() > mCapacity)
            {
                mPositions.removeFirst();
            }
        }
    }

    /**
     * The positions after a sequence number, oldest first.
     * @param afterSequence return positions with a sequence greater than this; 0 for everything held.
     * @param limit maximum number to return; values below 1 mean no limit.
     */
    public List<Position> getPositions(long afterSequence, int limit)
    {
        List<Position> matches = new ArrayList<>();

        synchronized(mLock)
        {
            for(Position position : mPositions)
            {
                if(position.seq > afterSequence)
                {
                    matches.add(position);

                    if(limit > 0 && matches.size() >= limit)
                    {
                        break;
                    }
                }
            }
        }

        return matches;
    }

    /**
     * Counts since this buffer was created: usable positions, unusable (0/0) ones, and when the last usable one
     * was decoded.  {@code lastSeq} equals the number of usable positions, since the sequence starts at 1.
     */
    public Map<String,Object> summary()
    {
        Map<String,Object> summary = new LinkedHashMap<>();

        synchronized(mLock)
        {
            summary.put("startedAtMs", mStartedAtMs);
            summary.put("lastSeq", mSequence);
            summary.put("invalid", mInvalid);
            summary.put("lastAtMs", mLastAtMs);
            summary.put("held", mPositions.size());
        }

        return summary;
    }

    private static String aliasNames(AliasList aliasList, Identifier identifier)
    {
        if(aliasList == null || identifier == null)
        {
            return null;
        }

        List<Alias> aliases = aliasList.getAliases(identifier);

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

    private static Identifier firstIdentifierOf(IdentifierCollection collection, Role role)
    {
        List<Identifier> matches = collection.getIdentifiers(role);
        return matches != null && !matches.isEmpty() ? matches.get(0) : null;
    }

    /**
     * One decoded position.  Public fields are serialized directly by Jackson.
     */
    public static class Position
    {
        /** Sequence number, 1 for the first position since the process started. */
        public long seq;
        /** When it was decoded, epoch millis. */
        public long at;
        /** Latitude, decimal degrees. */
        public double lat;
        /** Longitude, decimal degrees. */
        public double lon;
        /** Event type enum constant name, normally "GPS". */
        public String type;
        /** Protocol enum name: "APCO25" for an in-call position, "LRRP" for one sent as packet data. */
        public String protocol;
        /** FROM identifier as text: the radio that reported the position.  May be null. */
        public String from;
        /** Resolved FROM alias name(s), comma-joined, or null. */
        public String fromAlias;
        /** TO identifier as text: the talkgroup of the call the position was sent in.  May be null. */
        public String to;
        /** Resolved TO alias name(s), comma-joined, or null. */
        public String toAlias;
        /** Channel descriptor text, may be null. */
        public String channel;
        /** Downlink frequency of that channel in hertz, 0 when unknown. */
        public long frequencyHz;
    }
}
