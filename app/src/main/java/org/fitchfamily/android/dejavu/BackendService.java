package org.fitchfamily.android.dejavu;
/*
 *    DejaVu - A location provider backend for microG/UnifiedNlp
 *
 *    Copyright (C) 2017 Tod Fitch
 *
 *    This program is Free Software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as
 *    published by the Free Software Foundation, either version 3 of the
 *    License, or (at your option) any later version.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

/**
 * Created by tfitch on 8/27/17.
 */

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.ServiceConnection;
import android.location.Location;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.telephony.CellIdentityCdma;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityWcdma;
import android.telephony.CellInfoCdma;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoWcdma;
import android.telephony.CellLocation;
import android.telephony.gsm.GsmCellLocation;
import android.telephony.NeighboringCellInfo;
import android.telephony.TelephonyManager;
import android.util.Log;

import static android.Manifest.permission.ACCESS_COARSE_LOCATION;
import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.ACCESS_WIFI_STATE;
import static android.Manifest.permission.CHANGE_WIFI_STATE;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;

import org.microg.nlp.api.LocationBackendService;
import org.microg.nlp.api.MPermissionHelperActivity;

public class BackendService extends LocationBackendService {
    private static final String TAG = "DejaVu Backend";

    public static final String LOCATION_PROVIDER = "DejaVu";
    private final boolean DEBUG = false;

    private static final
            String[] myPerms = new String[]{
            ACCESS_WIFI_STATE, CHANGE_WIFI_STATE,
            ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION};

    public static final double DEG_TO_METER = 111225.0;
    public static final double METER_TO_DEG = 1.0 / DEG_TO_METER;
    public static final double MIN_COS = 0.01;      // for things that are dividing by the cosine

    // Define range of received signal strength to be used for all emitter types.
    // Basically use the same range of values for LTE and WiFi as GSM defaults to.
    public static final int MAXIMUM_ASU = 31;
    public static final int MINIMUM_ASU = 1;

    // KPH -> Meters/millisec (KPH * 1000) / (60*60*1000) -> KPH/3600
    public static final float EXPECTED_SPEED = 120.0f / 3600;           // 120KPH (74 MPH)

    private static final float NULL_ISLAND_DISTANCE = 1000;
    private static Location nullIsland = new Location(BackendService.LOCATION_PROVIDER);;

    /**
     * Process noise for lat and lon.
     *
     * We do not have an accelerometer, so process noise ought to be large enough
     * to account for reasonable changes in vehicle speed. Assume 0 to 100 kph in
     * 5 seconds (20kph/sec ~= 5.6 m/s**2 acceleration). Or the reverse, 6 m/s**2
     * is about 0-130 kph in 6 seconds
     */
    private final static double GPS_COORDINATE_NOISE = 3.0;
    private final static double POSITION_COORDINATE_NOISE = 6.0;

    private static BackendService instance;
    private boolean gpsMonitorRunning = false;
    private boolean wifiBroadcastReceiverRegistered = false;
    private boolean permissionsOkay = true;

    // We use a threads for potentially slow operations.
    private Thread mobileThread;
    private Thread backgroundThread;
    private boolean wifiScanInprogress;

    private TelephonyManager tm;

    // Stuff for scanning WiFi APs
    private final static IntentFilter wifiBroadcastFilter =
            new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);

    private WifiManager wm;

    private final BroadcastReceiver wifiBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            onWiFisChanged();
        }
    };

    private Kalman gpsLocation;             // Filtered GPS (because GPS is so bad on Moto G4 Play)

    //
    // Periodic process information.
    //
    // We keep a set of the WiFi APs we expected to see and ones we've seen and then
    // periodically adjust the trust. Ones we've seen we increment, ones we expected
    // to see but didn't we decrement.
    //
    private Set<RfIdentification> seenSet;
    private Cache emitterCache;

    //
    // Scanning and reporting are resource intensive operations, so we throttle
    // them. Ideally the intervals should be multiples of one another.
    //
    // We are triggered by external events, so we really don't run periodically.
    // So these numbers are the minimum time. Actual will be at least that based
    // on when we get GPS locations and/or update requests from microG/UnifiedNlp.
    //
    private final static long REPORTING_INTERVAL   = 2700;                          // in milliseconds
    private final static long MOBILE_SCAN_INTERVAL = REPORTING_INTERVAL/2 - 100;    // in milliseconds
    private final static long WLAN_SCAN_INTERVAL   = REPORTING_INTERVAL/3 - 100;    // in milliseconds

    private long nextMobileScanTime;
    private long nextWlanScanTime;
    private long nextReportTime;

    //
    // We want only a single background thread to do all the work but we have a couple
    // of asynchronous inputs. So put everything into a work item queue. . . and have
    // a single server pull and process the information.
    //
    private class WorkItem {
        Collection<Observation> observations;
        Location loc;
        long time;

        WorkItem(Collection<Observation> o, Location l, long tm) {
            observations = o;
            loc = l;
            time = tm;
        }
    }
    private Queue<WorkItem> workQueue = new ConcurrentLinkedQueue<>();

    //
    // Overrides of inherited methods
    //

    @Override
    public void onCreate() {
        //Log.d(TAG, "onCreate() entry.");
        super.onCreate();
        nullIsland.setLatitude(0.0);
        nullIsland.setLongitude(0.0);
    }

    /**
     * We are starting to run, get the resources we need to do our job.
     */
    @Override
    protected void onOpen() {
        Log.d(TAG, "onOpen() entry.");
        super.onOpen();
        instance = this;
        nextReportTime = 0;
        nextMobileScanTime = 0;
        nextWlanScanTime = 0;
        wifiBroadcastReceiverRegistered = false;
        wifiScanInprogress = false;

        if (emitterCache == null)
            emitterCache = new Cache(this);

        permissionsOkay = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Check our needed permissions, don't run unless we can.
            for (String s : myPerms) {
                permissionsOkay &= (checkSelfPermission(s) == PackageManager.PERMISSION_GRANTED);
            }
        }
        if (permissionsOkay) {
            setgpsMonitorRunning(true);
            this.registerReceiver(wifiBroadcastReceiver, wifiBroadcastFilter);
            wifiBroadcastReceiverRegistered = true;
        } else {
            Log.d(TAG, "onOpen() - Permissions not granted, soft fail.");
        }
    }

    /**
     * Closing down, release our dynamic resources.
     */
    @Override
    protected synchronized void onClose() {
        super.onClose();
        Log.d(TAG, "onClose()");
        if (wifiBroadcastReceiverRegistered) {
            this.unregisterReceiver(wifiBroadcastReceiver);
        }
        setgpsMonitorRunning(false);

        if (emitterCache != null) {
            emitterCache.close();
            emitterCache = null;
        }

        if (instance == this) {
            instance = null;
        }
    }

    /**
     * Called by MicroG/UnifiedNlp when our backend is enabled. We return a list of
     * the Android permissions we need but have not (yet) been granted. MicroG will
     * handle putting up the dialog boxes, etc. to get our permissions granted.
     *
     * @return An intent with the list of permissions we need to run.
     */
    @Override
    protected Intent getInitIntent() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Build list of permissions we need but have not been granted
            List<String> perms = new LinkedList<>();
            for (String s : myPerms) {
                if (checkSelfPermission(s) != PackageManager.PERMISSION_GRANTED)
                    perms.add(s);
            }

            // Send the list of permissions we need to UnifiedNlp so it can ask for
            // them to be granted.
            if (perms.isEmpty())
                return null;
            Intent intent = new Intent(this, MPermissionHelperActivity.class);
            intent.putExtra(MPermissionHelperActivity.EXTRA_PERMISSIONS, perms.toArray(new String[perms.size()]));
            return intent;
        }
        return super.getInitIntent();
    }

    /**
     * Called by microG/UnifiedNlp when it wants a position update. We return a null indicating
     * we don't have a current position but treat it as a good time to kick off a scan of all
     * our RF sensors.
     *
     * @return Always null.
     */
    @Override
    protected Location update() {
        //Log.d(TAG, "update() entry.");
        if (permissionsOkay) {
            scanAllSensors();
        } else {
            Log.d(TAG, "update() - Permissions not granted, soft fail.");
        }
        return null;
    }

    //
    // Other public methods
    //

    /**
     * Called by Android when a GPS location reports becomes available.
     *
     * @param locReport The current GPS position estimate
     */
    public static void instanceGpsLocationUpdated(final android.location.Location locReport) {
        //Log.d(TAG, "instanceGpsLocationUpdated() entry.");
        if (instance != null) {
            instance.onGpsChanged(locReport);
        }
    }

    /**
     * Check if location too close to null island to be real
     *
     * @param loc The location to be checked
     * @return boolean True if away from lat,lon of 0,0
     */
    public static boolean notNullIsland(Location loc) {
        return (nullIsland.distanceTo(loc) > NULL_ISLAND_DISTANCE);
    }

    //
    // Private methods
    //

    /**
     * Called when we have a new GPS position report from Android. We update our local
     * Kalman filter (our best guess on GPS reported position) and since our location is
     * pretty current it is a good time to kick of a scan of RF sensors.
     *
     * @param updt The current GPS reported location
     */
    private void onGpsChanged(Location updt) {
        synchronized (this) {
            if (permissionsOkay) {
                if (notNullIsland(updt)) {
                    //Log.d(TAG, "onGpsChanged() entry.");
                    if (gpsLocation == null)
                        gpsLocation = new Kalman(updt, GPS_COORDINATE_NOISE);
                    else
                        gpsLocation.update(updt);

                    scanAllSensors();
                }
            } else {
                Log.d(TAG, "onGpsChanged() - Permissions not granted, soft fail.");
            }
        }
    }

    /**
     * Kick off new scans for all the sensor types we know about. Typically scans
     * should occur asynchronously so we don't hang up our caller's thread.
     */
    private void scanAllSensors() {
        synchronized (this) {
            if (emitterCache == null) {
                Log.d(TAG, "scanAllSensors() - emitterCache is null?!?");
                return;
            }
            startWiFiScan();
            startMobileScan();
        }
    }

    /**
     * Ask Android's WiFi manager to scan for access points (APs). When done the onWiFisChanged()
     * method will be called by Android.
     */
    private void startWiFiScan() {
        // Throttle scanning for WiFi APs. In open terrain an AP could cover a kilometer.
        // Even in a vehicle moving at highway speeds it can take several seconds to traverse
        // the coverage area, no need to waste phone resources scanning too rapidly.
        long currentProcessTime = System.currentTimeMillis();
        if (currentProcessTime < nextWlanScanTime)
            return;
        nextWlanScanTime = currentProcessTime + WLAN_SCAN_INTERVAL;

        if (wm == null) {
            wm = (WifiManager) this.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        }
        if ((wm != null)  && !wifiScanInprogress) {
            if (wm.isWifiEnabled() ||
                    ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) && wm.isScanAlwaysAvailable())) {
                //Log.d(TAG,"startWiFiScan() - Starting WiFi collection.");
                wifiScanInprogress = true;
                wm.startScan();
            }
        }
    }

    /**
     * Start a separate thread to scan for mobile (cell) towers. This can take some time so
     * we won't do it in the caller's thread.
     */
    private synchronized void startMobileScan() {
        // Throttle scanning for mobile towers. Generally each tower covers a significant amount
        // of terrain so even if we are moving fairly rapidly we should remain in a single tower's
        // coverage area for several seconds. No need to sample more ofen than that and we save
        // resources on the phone.

        long currentProcessTime = System.currentTimeMillis();
        if (currentProcessTime < nextMobileScanTime)
            return;
        nextMobileScanTime = currentProcessTime + MOBILE_SCAN_INTERVAL;

        // Scanning towers takes some time, so do it in a separate thread.
        if (mobileThread != null) {
            Log.d(TAG,"startMobileScan() - Thread exists.");
            return;
        }
        //Log.d(TAG,"startMobileScan() - Starting mobile signal scan thread.");
        mobileThread = new Thread(new Runnable() {
            @Override
            public void run() {
                scanMobile();
                mobileThread = null;
            }
        });
        mobileThread.start();
    }

    /**
     * Scan for the mobile (cell) towers the phone sees. If we see any, then add them
     * to the queue for background processing.
     */
    private void scanMobile() {
        // Log.d(TAG, "scanMobile() - calling getMobileTowers().");
        Collection<Observation> observations = getMobileTowers();

        if (observations.size() > 0) {
            // Log.d(TAG,"scanMobile() " + observations.size() + " records to be queued for processing.");
            queueForProcessing(observations, System.currentTimeMillis());
        }
    }

    /**
     * Get the set of mobile (cell) towers that Android claims the phone can see.
     * we use the current API but fall back to deprecated methods if we get a null
     * or empty result from the current API.
     *
     * @return A set of mobile tower observations
     */
    private Set<Observation> getMobileTowers() {
        if (tm == null) {
            tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        }

        Set<Observation> observations = new HashSet<>();

        // Try most recent API to get all cell information
        List<android.telephony.CellInfo> allCells;
        try {
            allCells = tm.getAllCellInfo();
        } catch (NoSuchMethodError e) {
            allCells = null;
            // Log.d(TAG, "getMobileTowers(): no such method: getAllCellInfo().");
        }
        if ((allCells != null) && !allCells.isEmpty()) {
            // Log.d(TAG, "getMobileTowers(): getAllCellInfo() returned "+ allCells.size() + "records.");
            for (android.telephony.CellInfo inputCellInfo : allCells) {
                // Log.d(TAG, "getMobileTowers(): inputCellInfo: "+ inputCellInfo.toString());
                if (inputCellInfo instanceof CellInfoLte) {
                    CellInfoLte info = (CellInfoLte) inputCellInfo;
                    CellIdentityLte id = info.getCellIdentity();

                    // CellIdentityLte accessors all state Integer.MAX_VALUE is returned for unknown values.
                    if ((id.getMcc() != Integer.MAX_VALUE) && (id.getMnc() != Integer.MAX_VALUE) &&
                        (id.getCi() != Integer.MAX_VALUE) && (id.getPci() != Integer.MAX_VALUE) &&
                        (id.getTac() != Integer.MAX_VALUE)) {
                        // Log.d(TAG, "getMobileTowers(): LTE tower: " + info.toString());
                        String idStr = "LTE" + "/" + id.getMcc() + "/" +
                                id.getMnc() + "/" + id.getCi() + "/" +
                                id.getPci() + "/" + id.getTac();
                        int asu = (info.getCellSignalStrength().getAsuLevel() * MAXIMUM_ASU) / 97;

                        Observation o = new Observation(idStr, RfEmitter.EmitterType.MOBILE);
                        o.setAsu(asu);
                        observations.add(o);
                    } else {
                        if (DEBUG)
                            Log.d(TAG, "getMobileTowers(): LTE Cell Identity has unknown values: " + id.toString());
                    }
                } else if (inputCellInfo instanceof CellInfoGsm) {
                    CellInfoGsm info = (CellInfoGsm) inputCellInfo;
                    CellIdentityGsm id = info.getCellIdentity();

                    // CellIdentityGsm accessors all state Integer.MAX_VALUE is returned for unknown values.
                    if ((id.getMcc() != Integer.MAX_VALUE) && (id.getMnc() != Integer.MAX_VALUE) &&
                        (id.getLac() != Integer.MAX_VALUE) && (id.getCid() != Integer.MAX_VALUE)) {
                        // Log.d(TAG, "getMobileTowers(): GSM tower: " + info.toString());
                        String idStr = "GSM" + "/" + id.getMcc() + "/" +
                                id.getMnc() + "/" + id.getLac() + "/" +
                                id.getCid();
                        int asu = info.getCellSignalStrength().getAsuLevel();
                        Observation o = new Observation(idStr, RfEmitter.EmitterType.MOBILE);
                        o.setAsu(asu);
                        observations.add(o);
                    } else {
                        if (DEBUG)
                            Log.d(TAG, "getMobileTowers(): GSM Cell Identity has unknown values: " + id.toString());
                    }
                } else if (inputCellInfo instanceof CellInfoWcdma) {
                    CellInfoWcdma info = (CellInfoWcdma) inputCellInfo;
                    CellIdentityWcdma id = info.getCellIdentity();

                    // CellIdentityWcdma accessors all state Integer.MAX_VALUE is returned for unknown values.
                    if ((id.getMcc() != Integer.MAX_VALUE) && (id.getMnc() != Integer.MAX_VALUE) &&
                            (id.getLac() != Integer.MAX_VALUE) && (id.getCid() != Integer.MAX_VALUE)) {
                        // Log.d(TAG, "getMobileTowers(): WCDMA tower: " + info.toString());
                        String idStr = "WCDMA" + "/" + id.getMcc() + "/" +
                                id.getMnc() + "/" + id.getLac() + "/" +
                                id.getCid();
                        int asu = info.getCellSignalStrength().getAsuLevel();
                        Observation o = new Observation(idStr, RfEmitter.EmitterType.MOBILE);
                        o.setAsu(asu);
                        observations.add(o);
                    } else {
                        if (DEBUG)
                            Log.d(TAG, "getMobileTowers(): WCDMA Cell Identity has unknown values: " + id.toString());
                    }
                } else if (inputCellInfo instanceof CellInfoCdma) {
                    CellInfoCdma info = (CellInfoCdma) inputCellInfo;
                    CellIdentityCdma id = info.getCellIdentity();

                    // CellIdentityCdma accessors all state Integer.MAX_VALUE is returned for unknown values.
                    if ((id.getNetworkId() != Integer.MAX_VALUE) && (id.getSystemId() != Integer.MAX_VALUE) &&
                            (id.getBasestationId() != Integer.MAX_VALUE)) {
                        // Log.d(TAG, "getMobileTowers(): CDMA tower: " + info.toString());
                        String idStr = "CDMA" + "/" + id.getNetworkId() + "/" +
                                id.getSystemId() + "/" + id.getBasestationId();
                        int asu = info.getCellSignalStrength().getAsuLevel();
                        Observation o = new Observation(idStr, RfEmitter.EmitterType.MOBILE);
                        o.setAsu(asu);
                        observations.add(o);
                    } else {
                        if (DEBUG)
                            Log.d(TAG, "getMobileTowers(): CDMA Cell Identity has unknown values: " + id.toString());
                    }
                } else {
                    Log.d(TAG, "getMobileTowers(): Unsupported Cell type:  "+ inputCellInfo.toString());
                }
            }
        } else {
            observations = deprecatedGetMobileTowers();
        }
        //Log.d(TAG, "getMobileTowers(): Observations: " + observations.toString());
        return observations;
    }

    /**
     * Use old but still implemented methods to gather information about the mobile (cell)
     * towers our phone sees. Only called if the non-deprecated methods fail to return a
     * usable result.
     *
     * @return A set of observations for all the towers Android is reporting.
     */
    private Set<Observation> deprecatedGetMobileTowers() {

        Set<Observation> observations = new HashSet<>();

        String mncString = tm.getNetworkOperator();
        if ((mncString == null) || (mncString.length() < 5) || (mncString.length() > 6)) {
            // Log.d(TAG, "deprecatedGetMobileTowers(): mncString is NULL or not recognized.");
            return observations;
        }
        int mcc;
        int mnc;
        try {
            mcc = Integer.parseInt(mncString.substring(0, 3));
            mnc = Integer.parseInt(mncString.substring(3));
        } catch (NumberFormatException e) {
            // Log.d(TAG, "deprecatedGetMobileTowers(), Unable to parse mncString: " + e.toString());
            return observations;
        }
        final CellLocation cellLocation = tm.getCellLocation();

        if ((cellLocation != null) && (cellLocation instanceof GsmCellLocation)) {
            GsmCellLocation info = (GsmCellLocation) cellLocation;

            String idStr = "GSM" + "/" + mcc + "/" +
                    mnc + "/" + info.getLac() + "/" +
                    info.getCid();

            Observation o = new Observation(idStr, RfEmitter.EmitterType.MOBILE);
            o.setAsu(MINIMUM_ASU);
            observations.add(o);

        } else {
            if (DEBUG)
                Log.d(TAG, "deprecatedGetMobileTowers(): getCellLocation() returned null or not GsmCellLocation.");
        }
        try {
            final List<NeighboringCellInfo> neighbors = tm.getNeighboringCellInfo();
            if ((neighbors != null) && !neighbors.isEmpty()) {
                for (NeighboringCellInfo neighbor : neighbors) {
                    if ((neighbor.getCid() > 0) && (neighbor.getLac() > 0)) {
                        String idStr = "GSM" + "/" + mcc + "/" +
                                mnc + "/" + neighbor.getLac() + "/" +
                                neighbor.getCid();

                        Observation o = new Observation(idStr, RfEmitter.EmitterType.MOBILE);
                        o.setAsu(neighbor.getRssi());
                        observations.add(o);
                    }
                }
            } else {
                if (DEBUG)
                    Log.d(TAG, "deprecatedGetMobileTowers(): getNeighboringCellInfo() returned null or empty set.");
            }
        } catch (NoSuchMethodError e) {
            if (DEBUG)
                Log.d(TAG, "deprecatedGetMobileTowers(): no such method: getNeighboringCellInfo().");
        }
        return observations;
    }

    // Stuff for binding to (basically starting) background AP location
    // collection
    private ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className,
                                       IBinder binder) {
            Log.d(TAG, "mConnection.onServiceConnected()");
        }

        public void onServiceDisconnected(ComponentName className) {
            Log.d(TAG, "mConnection.onServiceDisconnected()");
        }
    };

    /**
     * Control whether or not we are listening for position reports from other sources.
     * The only one we care about is the GPS, thus the name.
     *
     * @param enable A boolean value, true enables monitoring.
     */
    private void setgpsMonitorRunning(boolean enable) {
        // Log.d(TAG,"setgpsMonitorRunning(" + enable + ")");
        if(enable != gpsMonitorRunning) {
            if (enable) {
                bindService(new Intent(this, GpsMonitor.class), mConnection, Context.BIND_AUTO_CREATE);
            } else {
                unbindService(mConnection);
            }
            gpsMonitorRunning = enable;
        }
    }

    /**
     * Call back method entered when Android has completed a scan for WiFi emitters in
     * the area.
     */
    private synchronized void onWiFisChanged() {
        if ((wm != null) && (emitterCache != null)) {
            List<ScanResult> scanResults = wm.getScanResults();
            Set<Observation> observations = new HashSet<>();
            for (ScanResult sr : scanResults) {
                String bssid = sr.BSSID.toLowerCase(Locale.US).replace(".", ":");
                RfEmitter.EmitterType rftype = RfEmitter.EmitterType.WLAN_24GHZ;
                if (is5GHz(sr))
                    rftype = RfEmitter.EmitterType.WLAN_5GHZ;
                Log.i(TAG,"rfType="+rftype.toString()+", ScanResult="+sr.toString());
                if (bssid != null) {
                    Observation o = new Observation(bssid, rftype);

                    o.setAsu(WifiManager.calculateSignalLevel(sr.level, MAXIMUM_ASU));
                    o.setNote(sr.SSID);
                    observations.add(o);
                }
            }
            if (!observations.isEmpty()) {
                // Log.d(TAG, "onWiFisChanged(): Observations: " + observations.toString());
                queueForProcessing(observations, System.currentTimeMillis());
            }
        }
        wifiScanInprogress = false;
    }

    /**
     * This seems like it ought to be in ScanResult but I get an unidentified error
     * @param sr Result from a WLAN/WiFi scan
     * @return True if in the 5GHZ range
     */
    static boolean is5GHz(ScanResult sr) {
        int freq = sr.frequency;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (sr.channelWidth != ScanResult.CHANNEL_WIDTH_20MHZ)
                freq = sr.centerFreq0;
        }
        return freq > 2500;
    }

    /**
     * Add a collection of observations to our background thread's work queue. If
     * no thread currently exists, start one.
     *
     * @param observations A set of RF emitter observations (all must be of the same type)
     * @param timeMs The time the observations were made.
     */
    private synchronized void queueForProcessing(Collection<Observation> observations,
                                                 long timeMs) {
        Location loc = null;
        if ((gpsLocation != null) && notNullIsland(gpsLocation.getLocation()))
            loc = gpsLocation.getLocation();
        WorkItem work = new WorkItem(observations, loc, timeMs);
        workQueue.offer(work);

        if (backgroundThread != null) {
            // Log.d(TAG,"queueForProcessing() - Thread exists.");
            return;
        }

        backgroundThread = new Thread(new Runnable() {
            @Override
            public void run() {
                WorkItem myWork = workQueue.poll();
                while (myWork != null) {
                    backgroundProcessing(myWork);
                    myWork = workQueue.poll();
                }
                backgroundThread = null;
            }
        });
        backgroundThread.start();
    }

    //
    //    Generic private methods
    //

    /**
     * Process a group of observations. Process in this context means
     * 1. Add the emitters to the set of emitters we have seen in this processing period.
     * 2. If the GPS is accurate enough, update our coverage estimates for the emitters.
     * 3. If the GPS is accurate enough, update a list of emitters we think we should have seen.
     * 3. Compute a position based on the current observations.
     * 4. If our collection period is over, report our position to microG/UnifiedNlp and
     *    synchonize our information with the flash based database.
     *
     * @param myWork
     */
    private synchronized void backgroundProcessing(WorkItem myWork) {
        if (emitterCache == null)
            return;

        if (seenSet == null)
            seenSet = new HashSet<>();

        Collection<RfEmitter> emitters = new HashSet<>();

        // Remember all the emitters we've seen during this processing period
        // and build a set of emitter objects for each RF emitter in the
        // observation set.

        for (Observation o : myWork.observations) {
            seenSet.add(o.getIdent());
            RfEmitter e = emitterCache.get(o.getIdent());
            if (e != null) {
                e.setLastObservation(o);
                emitters.add(e);
            }
        }

        // Update emitter coverage based on GPS as needed and get the set of locations
        // the emitters are known to be seen at.

        updateEmitters( emitters, myWork.loc, myWork.time);

        // Check for the end of our collection period. If we are in a new period
        // then finish off the processing for the previous period.
        long currentProcessTime = System.currentTimeMillis();
        if (currentProcessTime >= nextReportTime) {
            nextReportTime = currentProcessTime + REPORTING_INTERVAL;
            endOfPeriodProcessing();
        }
    }

    /**
     * Update the coverage estimates for the emitters we have just gotten observations for.
     *
     * @param emitters The emitters we have just observed
     * @param gps The GPS position at the time the observations were collected.
     * @param curTime The time the observations were collected
     */
    private synchronized void updateEmitters(Collection<RfEmitter> emitters, Location gps, long curTime) {

        if (emitterCache == null) {
            Log.d(TAG,"updateEmitters() - emitterCache is null?!?");
            emitterCache = new Cache(this);
        }

        for (RfEmitter emitter : emitters) {
            emitter.updateLocation(gps);
        }
    }

    /**
     * Get coverage estimates for a list of emitter IDs. Locations are marked with the
     * time of last update, etc.
     *
     * @param rfids IDs of the emitters desired
     * @return A list of the coverage areas for the emitters
     */
    private List<Location> getRfLocations(Collection<RfIdentification> rfids) {
        List<Location> locations = new LinkedList<>();
        for (RfIdentification id : rfids) {
            RfEmitter e = emitterCache.get(id);
            if (e != null) {
                Location l = e.getLocation();
                if (l != null) {
                    locations.add(l);
                }
            }
        }
        return locations;
    }

    /**
     * Compute our current location using a weighted average algorithm. We also keep
     * track of the types of emitters we have seen for the end of period processing.
     *
     * For any given reporting interval, we will only use an emitter once, so we keep
     * a set of used emitters.
     *
     * @param locations The set of coverage information for the current observations
     */
    private Location computePostion(Collection<Location> locations) {
        if (locations == null)
            return null;

        WeightedAverage weightedAverage = new WeightedAverage();
        for (Location l : locations) {
            weightedAverage.add(l);
        }
        return weightedAverage.result();
    }

    /**
     *
     * The collector service attempts to detect and not report moved/moving emitters.
     * But it (and thus our database) can't be perfect. This routine looks at all the
     * emitters and returns the largest subset (group) that are within a reasonable
     * distance of one another.
     *
     * The hope is that a single moved/moving emitters that is seen now but whose
     * location was detected miles away can be excluded from the set of APs
     * we use to determine where the phone is at this moment.
     *
     * We do this by creating collections of emitters where all the emitters in a group
     * are within a plausible distance of one another. A single emitters may end up
     * in multiple groups. When done, we return the largest group.
     *
     * If we are at the extreme limit of possible coverage (movedThreshold)
     * from two emitters then those emitters could be a distance of 2*movedThreshold apart.
     * So we will group the emitters based on that large distance.
     *
     * @param locations A collection of the coverages for the current observation set
     * @return The largest set of coverages found within the raw observations. That is
     * the most believable set of coverage areas.
     */
    private Set<Location> culledEmitters(Collection<Location> locations) {
        Set<Set<Location>> locationGroups = divideInGroups(locations);

        List<Set<Location>> clsList = new ArrayList<>(locationGroups);
        Collections.sort(clsList, new Comparator<Set<Location>>() {
            @Override
            public int compare(Set<Location> lhs, Set<Location> rhs) {
                return rhs.size() - lhs.size();
            }
        });

        if (!clsList.isEmpty()) {
            Set<Location> rslt = clsList.get(0);

            // Determine minimum count for a valid group of emitters.
            // The RfEmitter class will have put the min count into the location
            // it provided.
            Long reqdCount = 99999L;            // Some impossibly big number
            for (Location l : rslt) {
                reqdCount = Math.min(l.getExtras().getLong(RfEmitter.LOC_MIN_COUNT,9999L),reqdCount);
            }
            //Log.d(TAG,"culledEmitters() reqdCount="+reqdCount+", size="+rslt.size());
            if (rslt.size() >= reqdCount)
                return rslt;
        }
        return null;
    }

    /**
     * Build a set of sets (or groups) each outer set member is a set of coverage of
     * reasonably near RF emitters. Basically we are grouping the raw observations
     * into clumps based on how believably close together they are. An outlying emitter
     * will likely be put into its own group. Our caller will take the largest set as
     * the most believable group of observations to use to compute a position.
     *
     * @param locations A set of RF emitter coverage records
     * @return A set of coverage sets.
     */
    private Set<Set<Location>> divideInGroups(Collection<Location> locations) {

        Set<Set<Location>> bins = new HashSet<>();

        // Create a bins
        for (Location location : locations) {
            Set<Location> locGroup = new HashSet<>();
            locGroup.add(location);
            bins.add(locGroup);
        }

        for (Location location : locations) {
            for (Set<Location> locGroup : bins) {
                if (locationCompatibleWithGroup(location, locGroup)) {
                    locGroup.add(location);
                }
            }
        }
        return bins;
    }

    /**
     * Check to see if the coverage area (location) of an RF emitter is close
     * enough to others in a group that we can believably add it to the group.
     * @param location The coverage area of the candidate emitter
     * @param locGroup The coverage areas of the emitters already in the group
     * @return True if location is close to others in group
     */
    private boolean locationCompatibleWithGroup(Location location,
                                                Set<Location> locGroup) {

        // If the location is within range of all current members of the
        // group, then we are compatible.
        for (Location other : locGroup) {
            double testDistance = (location.distanceTo(other) -
                    location.getAccuracy() -
                    other.getAccuracy());

            if (testDistance > 0.0) {
                //Log.d(TAG,"locationCompatibleWithGroup(): "+testDistance);
                return false;
            }
        }
        return true;
    }

    /**
     * We bulk up operations to reduce writing to flash memory. And there really isn't
     * much need to report location to microG/UnifiedNlp more often than once every three
     * or four seconds. Another reason is that we can average more samples into each
     * report so there is a chance that our position computation is more accurate.
     */
    private void endOfPeriodProcessing() {

        //Log.d(TAG,"endOfPeriodProcessing() - Starting new process period.");

        // Estimate location using weighted average of the most recent
        // observations from the set of RF emitters we have seen. We cull
        // the locations based on distance from each other to reduce the
        // chance that a moved/moving emitter will be used in the computation.

        Collection<Location> locations = culledEmitters(getRfLocations(seenSet));
        Location weightedAverageLocation = computePostion(locations);
        if ((weightedAverageLocation != null) && notNullIsland(weightedAverageLocation)) {
            //Log.d(TAG, "endOfPeriodProcessing(): " + weightedAverageLocation.toString());
            report(weightedAverageLocation);
        }

        // Increment the trust of the emitters we've seen and decrement the trust
        // of the emitters we expected to see but didn't.

        if (seenSet != null) {
            for (RfIdentification id : seenSet) {
                if (id != null) {
                    RfEmitter e = emitterCache.get(id);
                    if (e != null)
                        e.incrementTrust();
                }
            }
        }

        // If we are dealing with very movable emitters, then try to detect ones that
        // have moved out of the area. We do that by collecting the set of emitters
        // that we expected to see in this area based on the GPS and our own location
        // computation.

        Set<RfIdentification> expectedSet = new HashSet<>();
        if (weightedAverageLocation != null) {
            emitterCache.sync();        // getExpected() ends bypassing the cache, so sync first

            for (RfEmitter.EmitterType etype : RfEmitter.EmitterType.values()) {
                expectedSet.addAll(getExpected(weightedAverageLocation, etype));
            }
            if (gpsLocation != null) {
                for (RfEmitter.EmitterType etype : RfEmitter.EmitterType.values()) {
                    expectedSet.addAll(getExpected(gpsLocation.getLocation(), etype));
                }
            }
        }

        for (RfIdentification  u : expectedSet) {
            if (!seenSet.contains(u)) {
                RfEmitter e = emitterCache.get(u);
                if (e != null) {
                    e.decrementTrust();
                }
            }
        }

        // Sync all of our changes to the on flash database and reset the RF emitters we've seen.

        emitterCache.sync();
        seenSet = new HashSet<>();
    }

    /**
     * Add all the RF emitters of the specified type within the specified bounding
     * box to the set of emitters we expect to see. This is used to age out emitters
     * that may have changed locations (or gone off the air). When aged out we
     * can remove them from our database.
     *
     * @param loc The location we think we are at.
     * @param rfType The type of RF emitters we expect to see within the bounding
     *               box.
     * @return A set of IDs for the RF emitters we should expect in this location.
     */
    private Set<RfIdentification> getExpected(Location loc, RfEmitter.EmitterType rfType) {
        RfEmitter.RfCharacteristics rfChar = RfEmitter.getRfCharacteristics(rfType);
        if ((loc == null) || (loc.getAccuracy() > rfChar.typicalRange))
            return new HashSet<>();
        BoundingBox bb = new BoundingBox(loc.getLatitude(), loc.getLongitude(), rfChar.typicalRange);
        return emitterCache.getEmitters(rfType, bb);
    }
}

