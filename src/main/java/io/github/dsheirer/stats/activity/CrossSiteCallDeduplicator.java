/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.stats.activity;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Counts a logical trunked call once when several sites of the same system observe it.  The first receiver context
 * to report a counted call within the same scope, target, and source inside the observation window owns the deduped
 * count; observations of the same call from other sites within the window are duplicates.  Re-observations by the
 * owning context stay countable so a busy-retry replay of a rolled-back write batch reaches the same decisions.
 */
class CrossSiteCallDeduplicator
{
    static final long SAME_CALL_WINDOW_MILLISECONDS = 5_000;
    private static final int MAXIMUM_TRACKED_CALLS = 65_536;

    private final Map<String,Anchor> mRecentCalls = new LinkedHashMap<>(256, 0.75f, true);

    /**
     * Indicates whether this counted call is the first observation of the logical call within its scope.
     *
     * @param scopeToken identifying the trunked system, or a per-context fallback when no scope is known
     * @param activity counted call observation
     * @return true when the call has not been counted for another site within the window
     */
    synchronized boolean isFirstObservation(String scopeToken, P25ActivityLogRecords.ActivityEvent activity)
    {
        long observedAt = activity.observedAtEpochMilliseconds();
        cleanup(observedAt);
        String key = String.join("|",
            safe(scopeToken),
            safe(activity.protocol()),
            safe(activity.targetKind()),
            safe(activity.targetId()),
            safe(activity.sourceRadioId()));
        String owner = activity.guid() != null ? activity.guid() : safe(activity.contextKey());
        Anchor anchor = mRecentCalls.get(key);

        if(anchor != null && Math.abs(observedAt - anchor.observedAt()) <= SAME_CALL_WINDOW_MILLISECONDS)
        {
            if(anchor.owner().equals(owner))
            {
                mRecentCalls.put(key, new Anchor(observedAt, owner));
                return true;
            }

            return false;
        }

        mRecentCalls.put(key, new Anchor(observedAt, owner));
        enforceMaximumSize();
        return true;
    }

    synchronized void clear()
    {
        mRecentCalls.clear();
    }

    private void cleanup(long now)
    {
        Iterator<Map.Entry<String,Anchor>> iterator = mRecentCalls.entrySet().iterator();

        while(iterator.hasNext())
        {
            Map.Entry<String,Anchor> entry = iterator.next();

            if(now >= entry.getValue().observedAt() &&
                now - entry.getValue().observedAt() > SAME_CALL_WINDOW_MILLISECONDS)
            {
                iterator.remove();
            }
            else
            {
                break;
            }
        }
    }

    private void enforceMaximumSize()
    {
        Iterator<String> iterator = mRecentCalls.keySet().iterator();

        while(mRecentCalls.size() > MAXIMUM_TRACKED_CALLS && iterator.hasNext())
        {
            iterator.next();
            iterator.remove();
        }
    }

    private static String safe(Object value)
    {
        return value == null ? "" : value.toString();
    }

    private record Anchor(long observedAt, String owner)
    {
    }
}
