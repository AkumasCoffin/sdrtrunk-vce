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

package io.github.dsheirer.audio.call;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.message.TimeslotMessage;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that {@link MutableAudioCallBuilder} keeps its broadcast channel set in step with the identifiers currently
 * resident in the call, rather than accumulating the broadcast channels of every talkgroup it ever saw. The latter is
 * the root cause of a call being streamed to a previous talkgroup's stream when a terminator is missed or the builder
 * is seeded from a stale identifier collection.
 */
public class MutableAudioCallBuilderBroadcastChannelTest
{
    private static final int TALKGROUP_A = 100;
    private static final int TALKGROUP_B = 200;
    private static final int RADIO = 9999;
    private static final String STREAM_A = "Stream A";
    private static final String STREAM_B = "Stream B";

    /**
     * A TO talkgroup A (routed to stream X) replaced by a different TO talkgroup B (routed to stream Y) must leave the
     * builder with only stream Y and TO = B.
     */
    @Test
    public void toReplacementRebuildsBroadcastChannels()
    {
        MutableAudioCallBuilder builder = new MutableAudioCallBuilder(aliasList(), TimeslotMessage.TIMESLOT_0);

        builder.addIdentifiers(List.of(APCO25Talkgroup.create(TALKGROUP_A)));
        builder.addIdentifiers(List.of(APCO25Talkgroup.create(TALKGROUP_B)));

        assertEquals(Set.of(STREAM_B), channelNames(builder),
            "Prior TO talkgroup A's stream must be dropped once TO becomes talkgroup B");
        assertEquals(TALKGROUP_B, builder.getIdentifierCollection().getToIdentifier().getValue(),
            "Final TO must be talkgroup B");
    }

    /**
     * A builder seeded with a stale TO talkgroup A (plus a FROM radio) and then updated to TO talkgroup B must likewise
     * end with only stream Y and TO = B. This mirrors AbstractAudioModule.getAudioCall seeding a new call from an
     * identifier collection that still holds the previous call's talkgroup.
     */
    @Test
    public void staleSeedRebuildsBroadcastChannels()
    {
        MutableAudioCallBuilder builder = new MutableAudioCallBuilder(aliasList(), TimeslotMessage.TIMESLOT_0);

        //Seed from a stale collection: the previous call's TO talkgroup A and a FROM radio.
        RadioIdentifier radio = APCO25RadioIdentifier.createFrom(RADIO);
        builder.addIdentifiers(List.of(APCO25Talkgroup.create(TALKGROUP_A), radio));
        //The new call's TO arrives and replaces the stale TO.
        builder.addIdentifiers(List.of(APCO25Talkgroup.create(TALKGROUP_B)));

        assertEquals(Set.of(STREAM_B), channelNames(builder),
            "A stale seeded TO talkgroup A's stream must be dropped once TO becomes talkgroup B");
        assertEquals(TALKGROUP_B, builder.getIdentifierCollection().getToIdentifier().getValue(),
            "Final TO must be talkgroup B");
    }

    private static Set<String> channelNames(MutableAudioCallBuilder builder)
    {
        return builder.getBroadcastChannels().stream()
            .map(BroadcastChannel::getChannelName)
            .collect(Collectors.toSet());
    }

    private static AliasList aliasList()
    {
        AliasList aliasList = new AliasList(new AliasListDefinition("builder-broadcast-test", AliasListFamily.P25));

        Alias aliasA = new Alias("talkgroup A");
        aliasA.setMatchIdentifier(new Talkgroup(Protocol.APCO25, TALKGROUP_A));
        aliasA.addBroadcastChannel(new BroadcastChannel(STREAM_A));
        aliasList.addAlias(aliasA);

        Alias aliasB = new Alias("talkgroup B");
        aliasB.setMatchIdentifier(new Talkgroup(Protocol.APCO25, TALKGROUP_B));
        aliasB.addBroadcastChannel(new BroadcastChannel(STREAM_B));
        aliasList.addAlias(aliasB);

        return aliasList;
    }
}
