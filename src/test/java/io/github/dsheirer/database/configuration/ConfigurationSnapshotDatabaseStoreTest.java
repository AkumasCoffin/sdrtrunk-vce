/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.database.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy;
import io.github.dsheirer.configuration.ConfigurationState;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.am.DecodeConfigAM;
import io.github.dsheirer.module.decode.analog.DecodeConfigAnalog;
import io.github.dsheirer.protocol.Protocol;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationSnapshotDatabaseStoreTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void rollsBackTheWholeDatabaseSnapshotWhenAWriteFails() throws Exception
    {
        Path database = mTemporaryFolder.resolve("snapshot-rollback.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        AliasListDefinition definition =
            new AliasListDefinition("County P25", AliasListFamily.P25);
        Alias alias = new Alias("Dispatch");
        alias.setAliasListDefinition(definition);
        alias.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 1001));

        ConfigurationState state = new ConfigurationState();
        state.setAliasListDefinitions(List.of(definition));
        state.setAliases(List.of(alias));

        try(Connection connection = SdrTrunkDatabase.open(database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP TABLE configuration_channel");
        }

        assertThrows(SQLException.class, () -> new ConfigurationSnapshotDatabaseStore(database).replace(state));

        try(Connection connection = SdrTrunkDatabase.open(database);
            Statement statement = connection.createStatement())
        {
            try(ResultSet resultSet = statement.executeQuery(
                "SELECT (SELECT COUNT(*) FROM alias), (SELECT COUNT(*) FROM alias_list)"))
            {
                assertTrue(resultSet.next());
                assertEquals(0, resultSet.getInt(1));
                assertEquals(0, resultSet.getInt(2));
            }
        }
    }

    /**
     * The node agent's /config/import path: a decodeConfigAM channel JSON binds to DecodeConfigAM, persists
     * through the snapshot store, and reloads as a policy-active AM channel.  An AM talkgroup alias in an
     * NBFM-family list persists alongside it.
     */
    @Test
    void persistsAndReloadsAnAmChannelAndAmAliasFromTheImportJsonShape() throws Exception
    {
        Path database = mTemporaryFolder.resolve("snapshot-am.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        //Exactly the channel shape the node agent POSTs to /config/import.
        Channel channel = mapper.readValue("""
            {
              "name": "TWR YSSY",
              "system": "AirBand",
              "site": "Sydney",
              "autoStart": true,
              "sourceConfiguration": {"type": "sourceConfigTuner", "sourceType": "TUNER", "frequency": 120500000},
              "decodeConfiguration": {"type": "decodeConfigAM", "bandwidth": "BW_15_0", "talkgroup": 7,
                                      "squelchThreshold": -70, "squelchAutoTrack": false}
            }
            """, Channel.class);

        DecodeConfigAM decodeConfig = (DecodeConfigAM)channel.getDecodeConfiguration();
        assertEquals(DecoderType.AM, decodeConfig.getDecoderType());
        assertEquals(DecodeConfigAnalog.Bandwidth.BW_15_0, decodeConfig.getBandwidth());
        assertEquals(7, decodeConfig.getTalkgroup());
        assertEquals(-70, decodeConfig.getSquelchThreshold());
        assertEquals(false, decodeConfig.isSquelchAutoTrack());
        assertTrue(ChannelConfigurationPolicy.isActive(channel));

        AliasListDefinition definition = new AliasListDefinition("AirBand", AliasListFamily.NBFM);
        Alias alias = new Alias("Sydney Tower");
        alias.setAliasListDefinition(definition);
        alias.setMatchIdentifier(new Talkgroup(Protocol.AM, 7));

        ConfigurationState state = new ConfigurationState();
        state.setAliasListDefinitions(List.of(definition));
        state.setAliases(List.of(alias));
        state.setChannels(List.of(channel));

        new ConfigurationSnapshotDatabaseStore(database).replace(state);

        ConfigurationState reloaded = new ConfigurationDatabaseStore(database).loadConfigurationState();
        assertEquals(1, reloaded.getChannels().size());
        Channel reloadedChannel = reloaded.getChannels().getFirst();
        assertEquals("TWR YSSY", reloadedChannel.getName());
        DecodeConfigAM reloadedConfig = (DecodeConfigAM)reloadedChannel.getDecodeConfiguration();
        assertEquals(DecoderType.AM, reloadedConfig.getDecoderType());
        assertEquals(DecodeConfigAnalog.Bandwidth.BW_15_0, reloadedConfig.getBandwidth());
        assertEquals(7, reloadedConfig.getTalkgroup());
        assertEquals(-70, reloadedConfig.getSquelchThreshold());
        assertEquals(false, reloadedConfig.isSquelchAutoTrack());
        assertTrue(ChannelConfigurationPolicy.isActive(reloadedChannel));

        try(Connection connection = SdrTrunkDatabase.open(database);
            Statement statement = connection.createStatement())
        {
            try(ResultSet resultSet = statement.executeQuery(
                "SELECT decoder_type FROM configuration_channel"))
            {
                assertTrue(resultSet.next());
                assertEquals("AM", resultSet.getString(1));
            }
            try(ResultSet resultSet = statement.executeQuery(
                "SELECT (SELECT COUNT(*) FROM alias), (SELECT COUNT(*) FROM alias_list)"))
            {
                assertTrue(resultSet.next());
                assertEquals(1, resultSet.getInt(1));
                assertEquals(1, resultSet.getInt(2));
            }
        }
    }
}
