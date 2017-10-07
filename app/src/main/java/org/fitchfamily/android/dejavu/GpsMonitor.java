package org.fitchfamily.android.dejavu;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.util.Log;

/**
 * Created by tfitch on 8/31/17.
 */

/**
 * A passive GPS monitor. We don't want to turn on the GPS as the backend
 * runs continuously and we would quickly drain the battery. But if some
 * other app turns on the GPS we want to listen in on its reports. The GPS
 * reports are used as a primary (trusted) source of position that we can
 * use to map the coverage of the RF emitters we detect.
 */
public class GpsMonitor extends Service implements LocationListener {
    private static final String TAG = "DejaVu GpsMonitor";

    private static final int GPS_SAMPLE_TIME = 0;
    private static final float GSP_SAMPLE_DISTANCE = 0;

    protected LocationManager lm;
    private boolean monitoring = false;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        {
            Log.d(TAG,"GpsMonitor onBind() entry.");
            return new Binder();
        }
    }

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate()");
        lm = (LocationManager) getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
        try {
            lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER,
                    GPS_SAMPLE_TIME,
                    GSP_SAMPLE_DISTANCE,
                    this);
            monitoring = true;
        } catch (SecurityException ex) {
            Log.w(TAG, "onCreate() failed: ", ex);
            monitoring = false;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy()");
        if (monitoring) {
            try {
                lm.removeUpdates(this);
            } catch (SecurityException ex) {
                // ignore
            }
            monitoring = false;
        }
    }

    /**
     * The passive provider we are monitoring will give positions from all
     * providers on the phone (including ourselves) we ignore all providers other
     * than the GPS. The GPS reports we pass on to our main backend service for
     * it to use in mapping RF emitter coverage.
     *
     * @param location A position report from a location provider
     */
    @Override
    public void onLocationChanged(Location location) {
        // Log.d(TAG, "onLocationChanged()");
        if (location.getProvider().equals("gps")) {
            BackendService.instanceGpsLocationUpdated(location);
        } else {
            // Log.d(TAG, "Ignoring position from \""+location.getProvider()+"\"");
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        Log.d(TAG, "onStatusChanged()");
    }

    @Override
    public void onProviderEnabled(String provider) {
        Log.d(TAG, "onProviderEnabled()");
    }

    @Override
    public void onProviderDisabled(String provider) {
        Log.d(TAG, "onProviderDisabled()");
    }
}
