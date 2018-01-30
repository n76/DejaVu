package org.fitchfamily.android.dejavu;

/**
 * Created by tfitch on 10/30/17.
 */

import android.location.Location;
import android.os.Build;
import android.os.Bundle;

public class WeightedAverage {
    public static final String TAG="DejaVu wgtAvg";
    public static final float MINIMUM_BELIEVABLE_ACCURACY = 15.0F;

    // Latitude averaging variables
    private double wSumLat;
    private double wSum2Lat;
    private double meanLat;
    private double sLat;

    // Longitude averaging variables
    private double wSumLon;
    private double wSum2Lon;
    private double meanLon;
    private double sLon;

    private int count;
    private long timeMs;
    private long mElapsedRealtimeNanos;

    private float accuracyOfLastReport;

    WeightedAverage() {
        reset();
    }

    public void reset() {
        wSumLat = wSum2Lat = meanLat = sLat = 0.0;
        wSumLon = wSum2Lon = meanLon = sLon = 0.0;

        count = 0;
        timeMs = 0;
        mElapsedRealtimeNanos = 0;
    }

    public void add(Location loc) {
        if (loc == null)
            return;

        //
        // We weight each location based on the signal strength, the higher the
        // strength the higher the weight. And we also use the estimated
        // coverage diameter. The larger the diameter, the lower the weight.
        //
        // ASU (signal strength) has been hard limited to always be >= 1
        // Accuracy (estimate of coverage radius) has been hard limited to always
        // be >= a emitter type minimum.
        //
        // So we are safe in computing the weight by dividing ASU by Accuracy.
        //

        float asu = loc.getExtras().getInt(RfEmitter.LOC_ASU);
        accuracyOfLastReport = loc.getAccuracy();
        double weight = asu/ accuracyOfLastReport;

        count++;
        //Log.d(TAG,"add() entry: weight="+weight+", count="+count);

        double lat = loc.getLatitude();
        wSumLat = wSumLat + weight;
        wSum2Lat = wSum2Lat + (weight * weight);
        double oldMean = meanLat;
        meanLat = oldMean + (weight / wSumLat) * (lat - oldMean);
        sLat = sLat + weight * (lat - oldMean) * (lat - meanLat);

        double lon = loc.getLongitude();
        wSumLon = wSumLon + weight;
        wSum2Lon = wSum2Lon + (weight * weight);
        oldMean = meanLon;
        meanLon = oldMean + (weight / wSumLon) * (lon - oldMean);
        sLon = sLon + weight * (lon - oldMean) * (lon - meanLon);

        timeMs = Math.max(timeMs,loc.getTime());
        mElapsedRealtimeNanos = Math.max(mElapsedRealtimeNanos,loc.getElapsedRealtimeNanos());
    }

    public Location result() {
        if (count < 1)
            return null;

        final Location location = new Location(BackendService.LOCATION_PROVIDER);

        location.setTime(timeMs);
        if (Build.VERSION.SDK_INT >= 17)
            location.setElapsedRealtimeNanos(mElapsedRealtimeNanos);

        location.setLatitude(meanLat);
        location.setLongitude(meanLon);
        if (count == 1) {
            location.setAccuracy(accuracyOfLastReport);
        } else {
            double sdLat = Math.sqrt(sLat / (wSumLat - wSum2Lat / wSumLat));
            double sdLon = Math.sqrt(sLon / (wSumLon - wSum2Lon / wSumLon));

            double sdMetersLat = sdLat * BackendService.DEG_TO_METER;
            double cosLat = Math.max(BackendService.MIN_COS, Math.cos(Math.toRadians(meanLat)));
            double sdMetersLon = sdLon * BackendService.DEG_TO_METER * cosLat;

            float acc = (float) Math.max(Math.sqrt((sdMetersLat*sdMetersLat)+(sdMetersLon*sdMetersLon)),MINIMUM_BELIEVABLE_ACCURACY);
            location.setAccuracy(acc);
        }

        Bundle extras = new Bundle();
        extras.putLong("AVERAGED_OF", count);
        location.setExtras(extras);

        return location;
    }
}
