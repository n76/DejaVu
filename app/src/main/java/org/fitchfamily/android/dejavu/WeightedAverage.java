package org.fitchfamily.android.dejavu;

/**
 * Created by tfitch on 10/30/17.
 */

import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

public class WeightedAverage {
    public static final String TAG="DejaVu wgtAvg";

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

    WeightedAverage() {
        reset();
    }

    public void reset() {
        wSumLat = wSum2Lat = meanLat = sLat = 0.0;
        wSumLon = wSum2Lon = meanLon = sLon = 0.0;

        count = 0;
        timeMs = 0;
    }

    public void add(Location loc, double weight) {
        if (loc == null)
            return;

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

        timeMs = loc.getTime();
    }

    public Location result() {
        if (count < 1)
            return null;

        final Location location = new Location(BackendService.LOCATION_PROVIDER);

        location.setTime(timeMs);
        if (Build.VERSION.SDK_INT >= 17)
            location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());

        location.setLatitude(meanLat);
        location.setLongitude(meanLon);
        if (count == 1) {
            location.setAccuracy(BackendService.MINIMUM_BELIEVABLE_ACCURACY);
        } else {
            //double varLat = sLat / (wSumLat - 1);
            //double varLon = sLon / (wSumLon - 1);
            double varLat = sLat / (wSumLat - wSum2Lat / wSumLat);
            double varLon = sLon / (wSumLon - wSum2Lon / wSumLon);

            double sdLat = Math.sqrt(varLat);
            double sdLon = Math.sqrt(varLon);

            //Log.d(TAG, "result() sLat=" + sLat + ", wSumLat=" + wSumLat + ", wSum2Lat=" + wSum2Lat + ", varLat=" + varLat + ", sdLat=" + sdLat);

            double sdMetersLat = sdLat * BackendService.DEG_TO_METER;
            double cosLat = Math.max(BackendService.MIN_COS, Math.cos(Math.toRadians(meanLat)));
            double sdMetersLon = sdLon * BackendService.DEG_TO_METER * cosLat;

            float acc = (float) Math.max(sdMetersLat, sdMetersLon);
            location.setAccuracy(acc);
        }

        Bundle extras = new Bundle();
        extras.putLong("AVERAGED_OF", count);
        location.setExtras(extras);

        return location;
    }
}
