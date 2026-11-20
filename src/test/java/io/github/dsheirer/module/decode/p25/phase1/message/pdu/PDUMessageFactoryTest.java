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
package io.github.dsheirer.module.decode.p25.phase1.message.pdu;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.packet.PacketHeader;
import io.github.dsheirer.module.decode.p25.reference.ServiceAccessPoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that an unconfirmed data header (format 0x15) carrying a packet data SAP is decoded as a packet
 * (IP) header rather than unconfirmed multi-block trunking control - the upstream sdrtrunk #2506 behaviour,
 * which is what lets a subscriber's LRRP location report, sent as unconfirmed packet data, be recovered.
 *
 * Header hex (first octet 0x35 = format 0x15; second octet holds the 6-bit SAP at bits 10-15):
 * SAP 0 UNENCRYPTED USER DATA, 4 PACKET DATA and 6 SNDCP PACKET DATA CONTROL are packet data; 61 and 63 are
 * the trunking-control SAPs.
 */
public class PDUMessageFactoryTest
{
    private static final String UNCONFIRMED_SAP_0_HEADER = "3500003D227F840A00020000";
    private static final String UNCONFIRMED_SAP_4_HEADER = "3504003D227F840A00020000";
    private static final String UNCONFIRMED_SAP_6_HEADER = "3506003D227F840A00020000";
    private static final String UNCONFIRMED_SAP_61_HEADER = "353D003D227F840A00020000";
    private static final String UNCONFIRMED_SAP_63_HEADER = "353F003D227F840A00020000";

    @Test
    public void unconfirmedDataHeaderWithPacketDataSapIsDecodedAsPacketHeader()
    {
        for(String hex : new String[]{UNCONFIRMED_SAP_0_HEADER, UNCONFIRMED_SAP_4_HEADER, UNCONFIRMED_SAP_6_HEADER})
        {
            PDUHeader header = PDUHeaderFactory.getPDUHeader(CorrectedBinaryMessage.loadHex(hex));
            assertTrue(header instanceof PacketHeader, hex);
        }

        PacketHeader packetDataHeader =
                (PacketHeader)PDUHeaderFactory.getPDUHeader(CorrectedBinaryMessage.loadHex(UNCONFIRMED_SAP_4_HEADER));
        assertEquals(ServiceAccessPoint.PACKET_DATA, packetDataHeader.getServiceAccessPoint());
    }

    @Test
    public void unconfirmedDataHeaderWithTrunkingControlSapStaysTrunkingControl()
    {
        for(String hex : new String[]{UNCONFIRMED_SAP_61_HEADER, UNCONFIRMED_SAP_63_HEADER})
        {
            PDUHeader header = PDUHeaderFactory.getPDUHeader(CorrectedBinaryMessage.loadHex(hex));
            assertFalse(header instanceof PacketHeader, hex);
        }
    }
}
