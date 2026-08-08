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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses pasted columnar talkgroup text in the form TGID / Alias / Description where columns are separated by
 * tabs, by runs of two or more spaces, or formatted as CSV (eg "TGID","Alias","Description").  Header rows and
 * lines without a leading numeric talkgroup are ignored.  When a line has no alias column, the description is
 * used as the alias.
 */
public final class AliasTextImportParser
{
    private AliasTextImportParser()
    {
    }

    /**
     * One successfully parsed talkgroup line.
     */
    public record ParsedTalkgroup(int talkgroup, String alias, String description)
    {
    }

    /**
     * Outcome of parsing one block of pasted text.
     */
    public record Result(List<ParsedTalkgroup> talkgroups, int ignoredLineCount, int duplicateCount)
    {
    }

    /**
     * Parses the pasted text into talkgroup entries.  Repeated talkgroup identifiers keep the first occurrence.
     */
    public static Result parse(String text)
    {
        Map<Integer,ParsedTalkgroup> parsed = new LinkedHashMap<>();
        int ignored = 0;
        int duplicates = 0;

        for(String line: text == null ? new String[0] : text.split("\\R"))
        {
            String trimmed = line.trim();

            if(trimmed.isEmpty())
            {
                continue;
            }

            String[] columns = split(trimmed);
            Integer talkgroup = parseTalkgroup(columns[0]);

            if(talkgroup == null)
            {
                ignored++;
                continue;
            }

            String alias = columns.length > 1 ? columns[1].trim() : "";
            String description = columns.length > 2 ?
                String.join(" ", Arrays.asList(columns).subList(2, columns.length)).trim() : "";

            if(alias.isEmpty())
            {
                alias = description;
            }

            if(alias.isEmpty())
            {
                ignored++;
                continue;
            }

            if(parsed.containsKey(talkgroup))
            {
                duplicates++;
                continue;
            }

            parsed.put(talkgroup, new ParsedTalkgroup(talkgroup, alias, description));
        }

        return new Result(List.copyOf(parsed.values()), ignored, duplicates);
    }

    private static String[] split(String line)
    {
        String[] columns = null;

        if(line.contains("\"") && line.contains(","))
        {
            String[] candidate = splitCsv(line);

            //Only accept the CSV split when it yields a usable leading column, otherwise fall through to the
            //whitespace layouts so quotes inside plain text columns are not misread as CSV.
            if(candidate.length > 1 && (candidate[0].trim().matches("\\d{1,9}") || !line.contains("  ")))
            {
                columns = candidate;
            }
        }

        if(columns == null && line.contains("\t"))
        {
            columns = line.split("\t+");
        }

        if(columns == null && line.contains(",") && !line.contains("  "))
        {
            //Unquoted CSV layout, eg: 10301,1 STATE,Admin - State Operations
            String[] candidate = line.split(",");

            if(candidate.length > 1 && candidate[0].trim().matches("\\d{1,9}"))
            {
                columns = candidate;
            }
        }

        if(columns == null)
        {
            columns = line.split("\\s{2,}");
        }

        if(columns.length == 1)
        {
            //Single-space layout: talkgroup identifier followed by alias text
            columns = line.split("\\s+", 2);
        }

        for(int index = 0; index < columns.length; index++)
        {
            columns[index] = columns[index].trim();
        }

        return columns;
    }

    /**
     * Splits one CSV line into fields, honoring double-quoted fields and doubled-quote escapes.
     */
    private static String[] splitCsv(String line)
    {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;

        for(int index = 0; index < line.length(); index++)
        {
            char character = line.charAt(index);

            if(quoted)
            {
                if(character == '"')
                {
                    if(index + 1 < line.length() && line.charAt(index + 1) == '"')
                    {
                        field.append('"');
                        index++;
                    }
                    else
                    {
                        quoted = false;
                    }
                }
                else
                {
                    field.append(character);
                }
            }
            else if(character == '"')
            {
                quoted = true;
            }
            else if(character == ',')
            {
                fields.add(field.toString());
                field.setLength(0);
            }
            else
            {
                field.append(character);
            }
        }

        fields.add(field.toString());
        return fields.toArray(String[]::new);
    }

    private static Integer parseTalkgroup(String value)
    {
        if(!value.matches("\\d{1,9}"))
        {
            return null;
        }

        try
        {
            return Integer.parseInt(value);
        }
        catch(NumberFormatException e)
        {
            return null;
        }
    }
}
