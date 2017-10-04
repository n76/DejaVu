package org.fitchfamily.android.dejavu;

import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;

/**
 * Created by tfitch on 8/31/17.
 */

public class Kalman {
    private static final double DEG_TO_METER = 111225.0;
    private static final double METER_TO_DEG = 1.0 / DEG_TO_METER;

    private static final double ALTITUDE_NOISE = 10.0;

    private static final float MOVING_THRESHOLD = 0.7f;     // meters/sec (2.5 kph ~= 0.7 m/s)
    private static final float MIN_ACCURACY = 3.0f;         // Meters

    /**
     * Three 1-dimension trackers, since the dimensions are independent and can avoid using matrices.
     */
    private Kalman1Dim mLatTracker;
    private Kalman1Dim mLonTracker;
    private Kalman1Dim mAltTracker;

    /**
     *  Most recently computed mBearing. Only updated if we are moving.
     */
    private float mBearing = 0.0f;

    /**
     *  Time of last update. Used to determine how stale our position is.
     */
    private long mTimeOfUpdate;

    /**
     * Number of samples filter has used.
     */
    private long samples;

    /**
     *
     * @param location
     */

    public Kalman(Location location, double coordinateNoise) {
        final double accuracy = location.getAccuracy();
        final double coordinateNoiseDegrees = coordinateNoise * METER_TO_DEG;
        double position, noise;
        long timeMs = location.getTime();

        // Latitude
        position = location.getLatitude();
        noise = accuracy * METER_TO_DEG;
        mLatTracker = new Kalman1Dim(coordinateNoiseDegrees, timeMs);
        mLatTracker.setState(position, 0.0, noise);

        // Longitude
        position = location.getLongitude();
        noise = accuracy * Math.cos(Math.toRadians(location.getLatitude())) * METER_TO_DEG;
        mLonTracker = new Kalman1Dim(coordinateNoiseDegrees, timeMs);
        mLonTracker.setState(position, 0.0, noise);

        // Altitude
        position = 0.0;
        if (location.hasAltitude()) {
            position = location.getAltitude();
            noise = accuracy;
            mAltTracker = new Kalman1Dim(ALTITUDE_NOISE, timeMs);
            mAltTracker.setState(position, 0.0, noise);
        }
        mTimeOfUpdate = timeMs;
        samples = 1;
    }

    public synchronized void update(Location location) {
        if (location == null)
            return;

        // Reusable
        final double accuracy = location.getAccuracy();
        double position, noise;
        long timeMs = location.getTime();

        predict(timeMs);
        mTimeOfUpdate = timeMs;
        samples++;

        // Latitude
        position = location.getLatitude();
        noise = accuracy * METER_TO_DEG;
        mLatTracker.update(position, noise);

        // Longitude
        position = location.getLongitude();
        noise = accuracy * Math.cos(Math.toRadians(location.getLatitude())) * METER_TO_DEG ;
        mLonTracker.update(position, noise);

        // Altitude
        if (location.hasAltitude()) {
            position = location.getAltitude();
            noise = accuracy;
            if (mAltTracker == null) {
                mAltTracker = new Kalman1Dim(ALTITUDE_NOISE, timeMs);
                mAltTracker.setState(position, 0.0, noise);
            } else {
                mAltTracker.update(position, noise);
            }
        }
    }

    public synchronized void predict(long timeMs) {
        mLatTracker.predict(0.0, timeMs);
        mLonTracker.predict(0.0, timeMs);
        if (mAltTracker != null)
            mAltTracker.predict(0.0, timeMs);
    }

    // Allow others to override our sample count. They may want to have us report only the
    // most recent samples.
    public void setSamples(long s) {
        samples = s;
    }

    public long getSamples() {
        return samples;
    }

    public synchronized Location getLocation() {
        Long timeMs = System.currentTimeMillis();
        final Location location = new Location(BackendService.LOCATION_PROVIDER);

        predict(timeMs);
        location.setTime(timeMs);
        if (Build.VERSION.SDK_INT >= 17)
            location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        location.setLatitude(mLatTracker.getPosition());
        location.setLongitude(mLonTracker.getPosition());
        if (mAltTracker != null)
            location.setAltitude(mAltTracker.getPosition());

        float accuracy = (float) (mLatTracker.getAccuracy() * DEG_TO_METER);
        if (accuracy < MIN_ACCURACY)
            accuracy = MIN_ACCURACY;
        location.setAccuracy(accuracy);

        // Derive speed from degrees/ms in lat and lon
        double latVeolocity = mLatTracker.getVelocity() * DEG_TO_METER;
        double lonVeolocity = mLonTracker.getVelocity() * DEG_TO_METER *
                Math.cos(Math.toRadians(location.getLatitude()));
        float speed = (float) Math.sqrt((latVeolocity*latVeolocity)+(lonVeolocity*lonVeolocity));
        location.setSpeed(speed);

        // Compute bearing only if we are moving. Report old bearing
        // if we are below our threshold for moving.
        if (speed > MOVING_THRESHOLD) {
            mBearing = (float) Math.toDegrees(Math.atan2(latVeolocity, lonVeolocity));
        }
        location.setBearing(mBearing);

        Bundle extras = new Bundle();
        extras.putLong("AVERAGED_OF", samples);
        location.setExtras(extras);

        return location;
    }
}
