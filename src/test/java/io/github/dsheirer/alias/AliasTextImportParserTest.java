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

package io.github.dsheirer.alias;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AliasTextImportParserTest
{
    @Test
    void parsesTabAndMultiSpaceColumnsAndIgnoresHeaders()
    {
        String text = """
            TGID        Alias                    Description

            10301    1 STATE                    Admin - State Operations / MRU
            10310    10 NSCC A/H            Admin - North Sydney/Central Coast AHS
            10319\tPTO OPS 1\tPatient Transport - Operations 1
            """;

        AliasTextImportParser.Result result = AliasTextImportParser.parse(text);

        assertEquals(3, result.talkgroups().size());
        assertEquals(1, result.ignoredLineCount());
        assertEquals(0, result.duplicateCount());

        AliasTextImportParser.ParsedTalkgroup first = result.talkgroups().get(0);
        assertEquals(10301, first.talkgroup());
        assertEquals("1 STATE", first.alias());
        assertEquals("Admin - State Operations / MRU", first.description());

        AliasTextImportParser.ParsedTalkgroup second = result.talkgroups().get(1);
        assertEquals(10310, second.talkgroup());
        assertEquals("10 NSCC A/H", second.alias());
        assertEquals("Admin - North Sydney/Central Coast AHS", second.description());

        AliasTextImportParser.ParsedTalkgroup third = result.talkgroups().get(2);
        assertEquals(10319, third.talkgroup());
        assertEquals("PTO OPS 1", third.alias());
        assertEquals("Patient Transport - Operations 1", third.description());
    }

    @Test
    void usesDescriptionAsAliasWhenNoAliasColumnIsPresent()
    {
        AliasTextImportParser.Result result =
            AliasTextImportParser.parse("10307        Admin - Public Health Service Liaison");

        assertEquals(1, result.talkgroups().size());
        AliasTextImportParser.ParsedTalkgroup parsed = result.talkgroups().get(0);
        assertEquals(10307, parsed.talkgroup());
        assertEquals("Admin - Public Health Service Liaison", parsed.alias());
        assertEquals("", parsed.description());
    }

    @Test
    void fallsBackToSingleSpaceSplitWhenColumnsUseOneSpace()
    {
        AliasTextImportParser.Result result = AliasTextImportParser.parse("10305 5 TG EDU");

        assertEquals(1, result.talkgroups().size());
        AliasTextImportParser.ParsedTalkgroup parsed = result.talkgroups().get(0);
        assertEquals(10305, parsed.talkgroup());
        assertEquals("5 TG EDU", parsed.alias());
    }

    @Test
    void keepsTheFirstOccurrenceOfDuplicateTalkgroupsAndSkipsUnusableLines()
    {
        String text = """
            10301    First Alias    First
            10301    Second Alias   Second
            10999
            not a line
            """;

        AliasTextImportParser.Result result = AliasTextImportParser.parse(text);

        assertEquals(1, result.talkgroups().size());
        assertEquals("First Alias", result.talkgroups().get(0).alias());
        assertEquals(1, result.duplicateCount());
        assertEquals(2, result.ignoredLineCount());
    }

    @Test
    void parsesQuotedCsvWithHeaderRow()
    {
        String text = """
            "TGID","Alias","Description"
            "10301","1 STATE","Admin - State Operations / MRU"
            "10306","6 NETS","Admin - NETS (No longer in use - see NETS)"
            "10399","","Admin - Unnamed, with comma"
            """;

        AliasTextImportParser.Result result = AliasTextImportParser.parse(text);

        assertEquals(3, result.talkgroups().size());
        assertEquals(1, result.ignoredLineCount());

        AliasTextImportParser.ParsedTalkgroup first = result.talkgroups().get(0);
        assertEquals(10301, first.talkgroup());
        assertEquals("1 STATE", first.alias());
        assertEquals("Admin - State Operations / MRU", first.description());

        AliasTextImportParser.ParsedTalkgroup blankAlias = result.talkgroups().get(2);
        assertEquals(10399, blankAlias.talkgroup());
        assertEquals("Admin - Unnamed, with comma", blankAlias.alias());
        assertEquals("Admin - Unnamed, with comma", blankAlias.description());
    }

    @Test
    void parsesUnquotedCsv()
    {
        AliasTextImportParser.Result result =
            AliasTextImportParser.parse("10320,PTO OPS 2,Patient Transport - Operations 2");

        assertEquals(1, result.talkgroups().size());
        AliasTextImportParser.ParsedTalkgroup parsed = result.talkgroups().get(0);
        assertEquals(10320, parsed.talkgroup());
        assertEquals("PTO OPS 2", parsed.alias());
        assertEquals("Patient Transport - Operations 2", parsed.description());
    }

    @Test
    void handlesEmptyInput()
    {
        assertTrue(AliasTextImportParser.parse(null).talkgroups().isEmpty());
        assertTrue(AliasTextImportParser.parse("").talkgroups().isEmpty());
        assertTrue(AliasTextImportParser.parse("   \n\n").talkgroups().isEmpty());
    }
}
