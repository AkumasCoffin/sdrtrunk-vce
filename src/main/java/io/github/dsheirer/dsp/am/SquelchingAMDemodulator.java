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

package io.github.dsheirer.dsp.am;

import io.github.dsheirer.dsp.fm.ISquelchingDemodulator;
import io.github.dsheirer.dsp.magnitude.IMagnitudeCalculator;
import io.github.dsheirer.dsp.magnitude.MagnitudeFactory;
import io.github.dsheirer.dsp.squelch.AdaptiveSquelch;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.SourceEvent;
import org.apache.commons.math3.util.FastMath;

/**
 * AM (envelope) demodulator with integrated adaptive power squelch.
 *
 * Demodulation is the square root of the complex sample magnitude-squared (envelope detection) with a fixed
 * gain applied.  The adaptive squelch operates on the magnitude-squared (power) stream and zeroes squelched
 * samples; the optional auto-track feature follows the noise floor while squelched and re-adjusts the squelch
 * threshold.
 */
public class SquelchingAMDemodulator implements ISquelchingDemodulator, Listener<SourceEvent>
{
    private static final float ZERO = 0.0f;
    private final IMagnitudeCalculator mMagnitudeCalculator = MagnitudeFactory.getMagnitudeCalculator();
    private final AdaptiveSquelch mAdaptiveSquelch;
    private final float mGain;
    private boolean mSquelchChanged = false;

    /**
     * Constructs an instance
     * @param gain to apply to the demodulated AM samples (e.g. 150.0f)
     * @param alpha decay value of the squelch's single pole IIR filter in range: 0.0 - 1.0.  The smaller the
     * alpha value, the slower the squelch response.
     * @param squelchThreshold in decibels.  Signal power must exceed this threshold value for unsquelch.
     * @param squelchAutoTrack to enable the squelch noise floor auto tracking feature.
     */
    public SquelchingAMDemodulator(float gain, float alpha, float squelchThreshold, boolean squelchAutoTrack)
    {
        mGain = gain;
        mAdaptiveSquelch = new AdaptiveSquelch(alpha, squelchThreshold, squelchAutoTrack);
    }

    /**
     * Set or update the sample rate for the squelch to adjust the power level notification rate.
     * @param sampleRate in hertz
     */
    @Override
    public void setSampleRate(int sampleRate)
    {
        mAdaptiveSquelch.setSampleRate(sampleRate);
    }

    /**
     * Registers the listener to receive notifications of squelch change events from the power squelch.
     */
    @Override
    public void setSourceEventListener(Listener<SourceEvent> listener)
    {
        mAdaptiveSquelch.setSourceEventListener(listener);
    }

    /**
     * Demodulates the complex sample arrays.
     * @param i inphase sample array
     * @param q quadrature sample array
     * @return demodulated AM samples with gain applied and squelched samples zeroed.
     */
    @Override
    public float[] demodulate(float[] i, float[] q)
    {
        mAdaptiveSquelch.setSquelchChanged(false);
        setSquelchChanged(false);

        float[] magnitude = mMagnitudeCalculator.calculate(i, q);
        float[] demodulated = new float[magnitude.length];

        for(int x = 0; x < magnitude.length; x++)
        {
            demodulated[x] = (float)FastMath.sqrt(magnitude[x]) * mGain;

            mAdaptiveSquelch.process(magnitude[x]);

            if(!mAdaptiveSquelch.isUnmuted())
            {
                demodulated[x] = ZERO;
            }

            if(mAdaptiveSquelch.isSquelchChanged())
            {
                setSquelchChanged(true);
            }
        }

        return demodulated;
    }

    /**
     * Sets the threshold for squelch control
     * @param threshold (dB)
     */
    @Override
    public void setSquelchThreshold(float threshold)
    {
        mAdaptiveSquelch.setSquelchThreshold(threshold);
    }

    /**
     * Enables or disables the squelch noise floor auto-track feature.
     */
    @Override
    public void setSquelchAutoTrack(boolean autoTrack)
    {
        mAdaptiveSquelch.setSquelchAutoTrack(autoTrack);
    }

    /**
     * Indicates if the squelch state has changed during the processing of buffer(s)
     */
    @Override
    public boolean isSquelchChanged()
    {
        return mSquelchChanged;
    }

    /**
     * Sets or resets the squelch changed flag.
     */
    private void setSquelchChanged(boolean changed)
    {
        mSquelchChanged = changed;
    }

    /**
     * Indicates if the squelch state is currently muted
     */
    @Override
    public boolean isMuted()
    {
        return mAdaptiveSquelch.isMuted();
    }

    /**
     * Process source events initiated by the timer and end-user.
     * @param sourceEvent to process.
     */
    @Override
    public void receive(SourceEvent sourceEvent)
    {
        switch(sourceEvent.getEvent())
        {
            //Only forward squelch threshold & auto-track request events
            case REQUEST_CURRENT_SQUELCH_THRESHOLD:
            case REQUEST_CHANGE_SQUELCH_THRESHOLD:
            case REQUEST_CURRENT_SQUELCH_AUTO_TRACK:
            case REQUEST_CHANGE_SQUELCH_AUTO_TRACK:
                mAdaptiveSquelch.receive(sourceEvent);
                break;
            default:
                break;
        }
    }
}
