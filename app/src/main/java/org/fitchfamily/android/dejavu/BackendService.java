package org.fitchfamily.android.dejavu;

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
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
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
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.microg.nlp.api.LocationBackendService;
import org.microg.nlp.api.MPermissionHelperActivity;

public class BackendService extends LocationBackendService {
    private static final String TAG = "DejaVu Backend";

    public static final String LOCATION_PROVIDER = "DejaVu";

    // Define range of received signal strength to be used for all emitter types.
    // Basically use the same range of values for LTE and WiFi as GSM defaults to.
    public static final int MAXIMUM_ASU = 31;
    public static final int MINIMUM_ASU = 1;

    // To avoid computing a false location based on moving WiFi APs
    // (i.e. APs in buses, trains, cars, etc.) we require more than one
    // report. MIN_NUMBER_OF_WIFI_APS specifies our minumum count of
    // APs with locations needed.
    public static final int MIN_NUMBER_OF_WIFI_APS = 2;

    // KPH -> Meters/millisec (KPH * 1000) / (60*60*1000) -> KPH/3600
    public static final float EXPECTED_SPEED = 120.0f / 3600;           // 120KPH (74 MPH)
    public static final float MINIMUM_BELIEVABLE_ACCURACY = 15.0F;

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

    // We use a threads for potentially slow operations.
    private Thread mobileThread;
    private Thread backgroundThread;

    private Database database;
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

    private Kalman kalmanLocationEstimate;    // Our best guess for our current location

    private Location weightedAverageLocation;
    private double speedEstimate;
    private long lastLocationComputeTime;

    //
    // Periodic process information.
    //
    // We keep a set of the WiFi APs we expected to see and ones we've seen and then
    // periodically adjust the trust. Ones we've seen we increment, ones we expected
    // to see but didn't we decrement.
    //
    Set<String> seenSet;
    Set<String> expectedSet;
    long collectionPeriod;
    boolean wifiSeen;
    boolean mobileSeen;
    private final static long COLLECTION_INTERVAL = 4000;        // in milliseconds
    private long lastMobileScanPeriod;


    //
    // We want only a single background thread to do all the work but we have a couple
    // of asynchronous inputs. So put everything into a work item queue. . . and have
    // a single server pull and process the information.
    //
    private class WorkItem {
        public Collection<RfEmitter> emitters;
        public RfEmitter.EmitterType rfType;
        public Location loc;
        public long time;

        WorkItem(Collection<RfEmitter> e, RfEmitter.EmitterType tp, Location l, long tm) {
            emitters = e;
            rfType = tp;
            loc = l;
            time = tm;
        }
    }
    Queue<WorkItem> workQueue = new ConcurrentLinkedQueue<WorkItem>();

    //
    // Overrides of inherited methods
    //

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate() entry.");
        super.onCreate();
    }

    @Override
    protected void onOpen() {
        Log.d(TAG, "onOpen() entry.");
        super.onOpen();
        instance = this;
        collectionPeriod = currentProcessPeriodId(System.currentTimeMillis());
        lastMobileScanPeriod = collectionPeriod - 1;
        wifiSeen = false;
        mobileSeen = false;

        if (database == null)
            database = new Database(this);
        setgpsMonitorRunning(true);
        this.registerReceiver(wifiBroadcastReceiver, wifiBroadcastFilter);
    }

    @Override
    protected void onClose() {
        super.onClose();
        Log.d(TAG, "onClose()");
        this.unregisterReceiver(wifiBroadcastReceiver);
        setgpsMonitorRunning(false);
        if (instance == this) {
            instance = null;
        }
        if (database != null) {
            database.close();
            database = null;
        }
    }

    // Return intent with all the permissions we need to run. UnifiedNlp and/or microG
    // will then create a dialog box for the user to grant them.
    @Override
    protected Intent getInitIntent() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // List of all needed permissions
            String[] myPerms = new String[]{
                    ACCESS_WIFI_STATE, CHANGE_WIFI_STATE,
                    ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION};

            // Build list of permissions we need but have not been granted
            List<String> perms = new LinkedList<String>();
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

    @Override
    protected Location update() {
        //Log.d(TAG, "update() entry.");
        scanAllSensors();
        return null;
    }

    //
    // Other public methods
    //

    public static void instanceGpsLocationUpdated(final android.location.Location locReport) {
        //Log.d(TAG, "instanceGpsLocationUpdated() entry.");
        if (instance != null) {
            instance.onGpsChanged(locReport);
        }
    }

    //
    // Private methods
    //

    private synchronized void onGpsChanged(Location updt) {
        // Log.d(TAG, "onGpsChanged() entry.");
        if (gpsLocation == null)
            gpsLocation = new Kalman(updt, GPS_COORDINATE_NOISE);
        else
            gpsLocation.update(updt);
        scanAllSensors();
    }

    private synchronized void scanAllSensors() {
        startWiFiScan();
        startMobileScan();
    }

    private void startWiFiScan() {
        if (wm == null) {
            wm = (WifiManager) this.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        }
        if (wm.isWifiEnabled() ||
                ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) && wm.isScanAlwaysAvailable())) {
            wm.startScan();
        }
    }

    private synchronized void startMobileScan() {
        if (lastMobileScanPeriod == collectionPeriod)
            return;
        lastMobileScanPeriod = collectionPeriod;

        if (mobileThread != null) {
            Log.d(TAG,"startMobileScan() - Thread exists.");
            return;
        }
        // Log.d(TAG,"startMobileScan() - Starting collection thread.");
        mobileThread = new Thread(new Runnable() {
            @Override
            public void run() {
                scanMobile();
                mobileThread = null;
            }
        });
        mobileThread.start();
    }

    private void scanMobile() {
        Log.d(TAG, "scanMobile() - Entry.");

        Log.d(TAG, "scanMobile() - calling getMobileTowers().");
        Collection<RfEmitter> towers = getMobileTowers();
        Log.d(TAG, "scanMobile() - back from getMobileTowers().");
        if (towers.size() > 0) {
            Log.d(TAG, "scanMobile() - Calling startBackgroundProcessing.");
            startBackgroundProcessing(towers, RfEmitter.EmitterType.MOBILE);
        }
        Log.d(TAG, "scanMobile() - Exit.");
    }

    private Set<RfEmitter> getMobileTowers() {
        Log.d(TAG, "getMobileTowers() - entry.");
        if (tm == null) {
            tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        }

        Set<RfEmitter> mobileTowers = new HashSet<>();

        // Try most recent API to get all cell information
        List<android.telephony.CellInfo> allCells;
        try {
            allCells = tm.getAllCellInfo();
        } catch (NoSuchMethodError e) {
            allCells = null;
            // Log.d(TAG, "getMobileTowers(): no such method: getAllCellInfo().");
        }
        if ((allCells != null) && !allCells.isEmpty()) {
            Log.d(TAG, "getMobileTowers(): getAllCellInfo() returned "+ allCells.size() + "records.");
            for (android.telephony.CellInfo inputCellInfo : allCells) {
                if (inputCellInfo instanceof CellInfoLte) {
                    CellInfoLte info = (CellInfoLte) inputCellInfo;
                    CellIdentityLte id = info.getCellIdentity();
                    String idStr = "LTE" + "/" + id.getMcc() + "/" +
                            id.getMnc() + "/" + id.getCi() + "/" +
                            id.getPci()+ "/" + id.getTac();
                    int asu = (info.getCellSignalStrength().getAsuLevel() * MAXIMUM_ASU)/97;
                    RfEmitter emitter = new RfEmitter(
                            RfEmitter.EmitterType.MOBILE,
                            idStr, asu );
                    mobileTowers.add(emitter);
                } else if (inputCellInfo instanceof CellInfoGsm) {
                    CellInfoGsm info = (CellInfoGsm) inputCellInfo;
                    CellIdentityGsm id = info.getCellIdentity();
                    String idStr = "GSM" + "/" + id.getMcc() + "/" +
                            id.getMnc() + "/" + id.getLac() + "/" +
                            id.getCid();
                    int asu = info.getCellSignalStrength().getAsuLevel();
                    RfEmitter emitter = new RfEmitter(
                            RfEmitter.EmitterType.MOBILE,
                            idStr, asu );
                    mobileTowers.add(emitter);
                }
            }
        } else {
            mobileTowers = deprecatedGetMobileTowers();
        }
        return mobileTowers;
    }

    private Set<RfEmitter> deprecatedGetMobileTowers() {
        Log.d(TAG, "deprecatedGetMobileTowers() - Entry");

        Set<RfEmitter> mobileTowers = new HashSet<>();

        String mncString = tm.getNetworkOperator();
        if ((mncString == null) || (mncString.length() < 5) || (mncString.length() > 6)) {
            Log.d(TAG, "deprecatedGetMobileTowers(): mncString is NULL or not recognized.");
            return mobileTowers;
        }
        int mcc = 0;
        int mnc = 0;
        try {
            mcc = Integer.parseInt(mncString.substring(0, 3));
            mnc = Integer.parseInt(mncString.substring(3));
        } catch (NumberFormatException e) {
            Log.d(TAG, "deprecatedGetMobileTowers(), Unable to parse mncString: " + e.toString());
            return mobileTowers;
        }
        final CellLocation cellLocation = tm.getCellLocation();

        if ((cellLocation != null) && (cellLocation instanceof GsmCellLocation)) {
            GsmCellLocation info = (GsmCellLocation) cellLocation;

            String idStr = "GSM" + "/" + mcc + "/" +
                    mnc + "/" + info.getLac() + "/" +
                    info.getCid();

            RfEmitter emitter = new RfEmitter(
                    RfEmitter.EmitterType.MOBILE,
                    idStr, MINIMUM_ASU);
            mobileTowers.add(emitter);
        } else {
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
                        int asu = neighbor.getRssi();
                        RfEmitter emitter = new RfEmitter(
                                RfEmitter.EmitterType.MOBILE,
                                idStr, asu);
                        mobileTowers.add(emitter);
                    }
                }
            } else {
                Log.d(TAG, "deprecatedGetMobileTowers(): getNeighboringCellInfo() returned null or empty set.");
            }
        } catch (NoSuchMethodError e) {
            Log.d(TAG, "deprecatedGetMobileTowers(): no such method: getNeighboringCellInfo().");
        }
        return mobileTowers;
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

    private void setgpsMonitorRunning(boolean enable) {
        Log.d(TAG,"setgpsMonitorRunning(" + enable + ")");
        if(enable != gpsMonitorRunning) {
            if (enable) {
                bindService(new Intent(this, GpsMonitor.class), mConnection, Context.BIND_AUTO_CREATE);
            } else {
                unbindService(mConnection);
            }
            gpsMonitorRunning = enable;
        }
    }

    // WiFi scanning utilities

    private void onWiFisChanged() {
        if (wm != null) {
            List<ScanResult> scanResults = wm.getScanResults();
            Set<RfEmitter> wifiAPs = new HashSet<>();
            for (ScanResult sr : scanResults) {
                RfEmitter emitter = new RfEmitter(
                        RfEmitter.EmitterType.WLAN,
                        sr.BSSID.toLowerCase(Locale.US).replace(".", ":"),
                        WifiManager.calculateSignalLevel(sr.level, MAXIMUM_ASU));
                emitter.setNote(sr.SSID);
                wifiAPs.add(emitter);
            }
            if (!wifiAPs.isEmpty()) {
                startBackgroundProcessing(wifiAPs, RfEmitter.EmitterType.WLAN);
            }

        }
    }

    private synchronized void startBackgroundProcessing(Collection<RfEmitter> emitters,
                                                        RfEmitter.EmitterType rft) {

        Location loc = null;
        if (gpsLocation != null)
            loc = gpsLocation.getLocation();
        WorkItem work = new WorkItem(emitters, rft, loc, System.currentTimeMillis());
        workQueue.offer(work);

        if (backgroundThread != null) {
            // Log.d(TAG,"startBackgroundProcessing() - Thread exists.");
            return;
        }

        backgroundThread = new Thread(new Runnable() {
            @Override
            public void run() {
                WorkItem myWork = workQueue.poll();
                while (myWork != null) {
                    backGroundProcessing(myWork);
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

    private void backGroundProcessing(WorkItem myWork) {
        if (seenSet == null)
            seenSet = new HashSet<String>();
        if (expectedSet == null)
            expectedSet = new HashSet<String>();

        // Note all the emitters we've seen during this processing period.
        for (RfEmitter e : myWork.emitters) {
            seenSet.add(e.getId());
        }

        // Look up emitters for locations and update locations based on GPS as needed.
        Collection<Location> locations = updateDatabase(myWork.emitters,
                myWork.loc, myWork.time);
        computeLocation(locations, myWork.rfType, myWork.time);

        // If we are dealing with very movable emitters, then try to detect ones that
        // have moved out of the area. We do that by collecting the set of emitters
        // that we expected to see in this area based on the GPS.

        RfEmitter.RfCharacteristics rfChar = RfEmitter.getRfCharacteristics(myWork.rfType);
        if ((myWork.loc != null) && (myWork.loc.getAccuracy() < rfChar.reqdGpsAccuracy)) {
            BoundingBox bb = new BoundingBox(myWork.loc.getLatitude(),
                    myWork.loc.getLongitude(),
                    rfChar.typicalRange);

            // We may be in an area where RF propagation is longer than typical. . .
            // Adjust the bounding box based on the emitters we actually see.
            for (Location l : locations) {
                bb.update(l.getLatitude(), l.getLongitude());
            }

            //Log.d(TAG, "backGroundProcessing() - GPS bounding box is " + bb.toString());
            updateExpected(bb, myWork.rfType);
        }


        endOfPeriodProcessing(myWork.time);
    }

    private synchronized List<Location> updateDatabase(Collection<RfEmitter> emitters, Location gps, long curTime) {
        List<Location> locations = new LinkedList<>();
        if (database == null) {
            Log.d(TAG,"updateDatabase() - Database is null?!?");
            database = new Database(this);
        }
        database.beginTransaction();
        for (RfEmitter emitter : emitters) {
            emitter.updateLocation(database, gps);

            Location thisLoc = emitter.getLocation();
            if (thisLoc != null) {
                //Log.d(TAG,"updateDatabase() - Using " + emitter.logString());
                thisLoc.setTime(curTime);
                locations.add(thisLoc);
            //} else {
            //    Log.d(TAG, "updateDatase() - no location for " + emitter.logString());
            }
        }
        database.endTransaction();
        return locations;
    }

    private synchronized void computeLocation(Collection<Location> locations,
                                              RfEmitter.EmitterType rfType,
                                              long timeMs) {
        if (locations == null)
            return;

        RfEmitter.RfCharacteristics rfChar = RfEmitter.getRfCharacteristics(rfType);

        // Emitters, especially Wifi APs, can be mobile. We cull them by making
        // subsets where all members of the set are reasonably close to one
        // another and then take the largest group.
        //
        // To protect against moving WiFi APs,require the largest group
        // of APs has at least two members.
        locations = culledEmitters(locations, rfChar.moveDetectDistance);

        if ((locations == null) || (locations.size() < rfChar.minCount))
            return;

        switch (rfType) {
            case WLAN:
                wifiSeen = true;
                break;

            case MOBILE:
                mobileSeen = true;
                break;
        }

        //
        // Build up both a Kalman filter estimate of our location and a weighed average estimate.
        // The Kalman is excellent when we have multiple emitters in each sample period but
        // falsely converges on a single mobile cell tower if that is all we have. A weighted
        // average gives worse position with multiple emitters but does pretty well when all
        // we have to work with are single cell towers.
        //

        // First update the Kalman filter
        for (Location l:locations) {
            if (kalmanLocationEstimate == null) {
                kalmanLocationEstimate = new Kalman(l,POSITION_COORDINATE_NOISE);
            } else
                kalmanLocationEstimate.update(l);
        }

        // Now for the weighted average.
        //
        // To smooth out transitions between when we have lots of wifi APs and none or
        // when the cell tower the phone detects changes we will average in our last
        // position estimate. However we need to grow the uncertainty about the last
        // estimate based on how long it has been and how likely it is that we are moving.

        if (weightedAverageLocation != null) {
            float accuracy = weightedAverageLocation.getAccuracy();
            if (accuracy < MINIMUM_BELIEVABLE_ACCURACY)
                accuracy = MINIMUM_BELIEVABLE_ACCURACY;
            accuracy += EXPECTED_SPEED * (timeMs - lastLocationComputeTime);
            //Log.d(TAG,"computeLocation() - adjusting old accuracy from " + weightedAverageLocation.getAccuracy() + " to " + accuracy);
            weightedAverageLocation.setAccuracy(accuracy);

            // Average in previous value if it exits. Should smooth
            // motion from single cell report to multiple wifi AP report
            // and back.
            locations.add(weightedAverageLocation);
        }

        weightedAverageLocation = weightedAverage(locations, timeMs);
        lastLocationComputeTime = timeMs;
    }

    // Compute an average value weighted by the estimated coverage radius
    // with smaller coverage given higher weight.
    // We rely on having the accuracy values being positive non-zero.
    private Location weightedAverage(Collection<Location> locations, long timeMs) {
        double totalWeight = 0.0;
        double lon = 0.0;
        double lat = 0.0;
        double acc = 0.0;

        if (locations.size() > 0) {
            //Log.d(TAG, "weightedAverage(" + locations.size() + ") - entry.");
             for (Location l : locations) {
                 float thisAcc = l.getAccuracy();
                 if (thisAcc < MINIMUM_BELIEVABLE_ACCURACY)
                     thisAcc = MINIMUM_BELIEVABLE_ACCURACY;
                double thisWeight = 1000.0 / thisAcc;
                totalWeight += thisWeight;

                lon += l.getLongitude() * thisWeight;
                lat += l.getLatitude() * thisWeight;
                acc += (l.getAccuracy() * l.getAccuracy()) * thisWeight;
            }

            final Location location = new Location(LOCATION_PROVIDER);

            location.setTime(timeMs);
            if (Build.VERSION.SDK_INT >= 17)
                location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            location.setLatitude(lat / totalWeight);
            location.setLongitude(lon / totalWeight);
            float thisAcc = (float) (Math.sqrt(acc)/totalWeight);
            if (thisAcc < MINIMUM_BELIEVABLE_ACCURACY)
                thisAcc = MINIMUM_BELIEVABLE_ACCURACY;
            location.setAccuracy(thisAcc);

            Bundle extras = new Bundle();
            extras.putLong("AVERAGED_OF", locations.size());
            location.setExtras(extras);

            return location;
        }
        return null;
    }

    //
    // The collector service attempts to detect and not report moved/moving emitters.
    // But it can't be perfect. This routine looks at all the emitters and returns the
    // largest subset (group) that are within a reasonable distance of one another.
    //
    // The hope is that a single moved/moving emitters that is seen now but whose
    // location was detected miles away can be excluded from the set of APs
    // we use to determine where the phone is at this moment.
    //
    // We do this by creating collections of emitters where all the emitters in a group
    // are within a plausible distance of one another. A single emitters may end up
    // in multiple groups. When done, we return the largest group.
    //
    // If we are at the extreme limit of possible coverage (movedThreshold)
    // from two emitters then those emitters could be a distance of 2*movedThreshold apart.
    // So we will group the emitters based on that large distance.
    //
    private Set<Location> culledEmitters(Collection<Location> locations, float moveThreshold) {
        Set<Set<Location>> locationGroups = divideInGroups(locations, moveThreshold);

        List<Set<Location>> clsList = new ArrayList<Set<Location>>(locationGroups);
        Collections.sort(clsList, new Comparator<Set<Location>>() {
            @Override
            public int compare(Set<Location> lhs, Set<Location> rhs) {
                return rhs.size() - lhs.size();
            }
        });

        /*
        for (Set<Location> s : clsList) {
            Log.d(TAG, "culledEmitters() - set size: " + s.size());
        }
        */

        if (!clsList.isEmpty()) {
            return clsList.get(0);
        } else {
            return null;
        }
    }

    private Set<Set<Location>> divideInGroups(Collection<Location> locations,
                                                     double accuracy) {

        Set<Set<Location>> bins = new HashSet<Set<Location>>();

        // Create a bins
        for (Location location : locations) {
            Set<Location> locGroup = new HashSet<Location>();
            locGroup.add(location);
            bins.add(locGroup);
        }

        for (Location location : locations) {
            for (Set<Location> locGroup : bins) {
                if (locationCompatibleWithGroup(location, locGroup, accuracy)) {
                    locGroup.add(location);
                }
            }
        }
        return bins;
    }

    private boolean locationCompatibleWithGroup(Location location,
                                                Set<Location> locGroup,
                                                double accuracy) {

        // If the location is within range of all current members of the
        // group, then we are compatible.
        for (Location other : locGroup) {
            double testDistance = (location.distanceTo(other) -
                    location.getAccuracy() -
                    other.getAccuracy());

            if (testDistance > accuracy) {
                return false;
            }
        }
        return true;
    }

    private long currentProcessPeriodId(long timeMs) {
        return timeMs / COLLECTION_INTERVAL;
    }

    private void endOfPeriodProcessing(long timeMs) {
        if (database == null)
            return;
        if (seenSet == null)
            seenSet = new HashSet<String>();
        if (expectedSet == null)
            expectedSet = new HashSet<String>();
        long thisProcessPeriod = currentProcessPeriodId(timeMs);

        // End of process period. Adjust the trust values of all
        // the emitters we've seen and the ones we expected
        // to see but did not.
        if (thisProcessPeriod != collectionPeriod) {
            Log.d(TAG,"endOfPeriodProcessing() - Starting new process period.");

            // Group all database changes into one transaction in the hope that
            // it will mean one commit write to storage. The hope is that this
            // will reduce the amount of writing to flash storage.
            database.beginTransaction();
            for (String s : seenSet) {
                RfEmitter e = database.getEmitter(s);
                if (e != null)
                    e.incrementTrust(database);
            }

            for (String  u : expectedSet) {
                if (!seenSet.contains(u)) {
                    RfEmitter e = database.getEmitter(u);
                    if (e != null)
                        e.decrementTrust(database);
                }
            }
            database.endTransaction();

            // Report location to UnifiedNlp at end of each processing period. If we've seen
            // any WiFi APs then the kalman filtered location is probably better. If we've only
            // seen cell towers then a weighted average is probably better.
            if (wifiSeen && (kalmanLocationEstimate != null))
                report(kalmanLocationEstimate.getLocation());
            else if (mobileSeen && (weightedAverageLocation != null))
                report(weightedAverageLocation);

            collectionPeriod = thisProcessPeriod;
            seenSet = new HashSet<String>();
            expectedSet = new HashSet<String >();
            wifiSeen = false;
            mobileSeen = false;
        }
    }

    private void updateExpected(BoundingBox bb, RfEmitter.EmitterType rfType) {
        if (database == null)
            return;
        if (expectedSet == null)
            expectedSet = new HashSet<String>();
        HashSet<RfEmitter> eSet = database.getEmitters(rfType, bb);
        for (RfEmitter e : eSet) {
            expectedSet.add(e.getId());
        }
    }
}

