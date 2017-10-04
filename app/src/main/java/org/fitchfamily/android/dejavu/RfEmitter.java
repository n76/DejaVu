package org.fitchfamily.android.dejavu;

import android.location.Location;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import java.util.Locale;

/**
 * Created by tfitch on 8/27/17.
 */

public class RfEmitter {
    private final static String TAG = "DejaVu RfEmitter";

    private static final double DEG_TO_METER = 111225.0;
    private static final double METER_TO_DEG = 1.0 / DEG_TO_METER;

    private static final long SECONDS = 1000;               // In milliseconds
    private static final long MINUTES = 60 * SECONDS;       // In milliseconds
    private static final long HOURS = 60 * MINUTES;
    private static final long DAYS = HOURS * 24;

    private static final long METERS = 1;
    private static final long KM = METERS * 1000;

    private static final long MINIMUM_TRUST = 0;
    private static final long REQUIRED_TRUST = 30;
    private static final long MAXIMUM_TRUST = 100;

    public enum EmitterType {WLAN, MOBILE}

    public class Coverage {
        public double latitude;
        public double longitude;
        public float radius;
    }

    public static class RfCharacteristics {
        public float reqdGpsAccuracy;       // GPS accuracy needed in meters
        public float minimumRange;          // Minimum believable coverage radius in meters
        public float typicalRange;          // Typical range expected
        public float moveDetectDistance;    // Maximum believable coverage radius in meters
        public long discoveryTrust;         // Assumed trustiness of a rust an emitter seen for the first time.
        public long incrTrust;              // Amount to increase trust
        public long decrTrust;              // Amount to decrease trust
        public long minCount;               // Minimum number of emitters before we can estimate location

        RfCharacteristics( float gps,
                           float min,
                           float typical,
                           float moveDist,
                           long newTrust,
                           long incr,
                           long decr,
                           long minC) {
            reqdGpsAccuracy = gps;
            minimumRange = min;
            typicalRange = typical;
            moveDetectDistance = moveDist;
            discoveryTrust = newTrust;
            incrTrust = incr;
            decrTrust = decr;
            minCount = minC;
        }
    }

    private RfCharacteristics ourCharacteristics;

    private EmitterType type;
    private String id;
    private int asu;
    private long trust;
    private Coverage coverage;
    private String note;

    RfEmitter(String typeStr, String ident, int signal) {
        EmitterType mType;
        if (typeStr.equals(EmitterType.WLAN.toString()))
            mType = EmitterType.WLAN;
        else if (typeStr.equals(EmitterType.MOBILE.toString()))
            mType = EmitterType.MOBILE;
        else
            mType = EmitterType.MOBILE.WLAN;
        //Log.d(TAG, "RfEmitter('"+typeStr+"', '"+ident+"', "+ signal +") - Type="+mType);
        initSelf(mType, ident, signal);
    }

    RfEmitter(EmitterType mType, String ident, int signal) {
        initSelf(mType, ident, signal);
    }

    private void initSelf(EmitterType mType, String ident, int signal) {
        type = mType;
        id = ident;
        if (signal > BackendService.MAXIMUM_ASU)
            asu = BackendService.MAXIMUM_ASU;
        else if (signal < BackendService.MINIMUM_ASU)
            asu = BackendService.MINIMUM_ASU;
        else
            asu = signal;
        coverage = null;
        ourCharacteristics = getRfCharacteristics(mType);
        trust = ourCharacteristics.discoveryTrust;
        note = "";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RfEmitter)) return false;

        RfEmitter e = (RfEmitter) o;
        if (!id.equals(e.id)) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = id.hashCode();
        return result;
    }

    public EmitterType getType() {
        return type;
    }

    public String getTypeString() {
        return type.toString();
    }

    public String getId() {
        return id;
    }

    public long getTrust() {
        return trust;
    }

    public double getLat() {
        if (coverage != null)
            return coverage.latitude;
        return 0.0;
    }

    public double getLon() {
        if (coverage != null)
            return coverage.longitude;
        return 0.0;
    }

    public double getRadius() {
        if (coverage != null)
            return coverage.radius;
        return 0.0;
    }

    public void setNote(String n) {
        note = n;
    }

    public String getNote() {
        return note;
    }

    public String logString() {
        return "RF Emitter: Type=" + type + ", ID='" + id + "', ASU=" + asu + ", Note='" + note + "'";
    }

    public static RfCharacteristics getRfCharacteristics(EmitterType t) {
        switch (t) {
            case WLAN:
                return new RfCharacteristics(
                        20 * METERS,        // reqdGpsAccuracy
                        50 * METERS,        // minimumRange
                        150 * METERS,       // typicalRange
                        1*KM,               // moveDetectDistance - Seen pretty long detection in very rural areas
                        0,                  // discoveryTrust
                        REQUIRED_TRUST/3,   // incrTrust
                        1,                  // decrTrust
                        2                   // minCount
                );

            case MOBILE:
                return new RfCharacteristics(
                        200 * METERS,       // reqdGpsAccuracy
                        500 * METERS,       // minimumRange
                        2 * KM,             // typicalRange
                        100 * KM,           // moveDetectDistance - In the desert there towers cover large areas
                        MAXIMUM_TRUST,      // discoveryTrust
                        MAXIMUM_TRUST,      // incrTrust
                        0,                  // decrTrust
                        1                   // minCount
                );
        }

        // Unknown emitter type, just throw out some values that make it unlikely that
        // we will ever use it (require too accurate a GPS location, never increment trust, etc.).
        return new RfCharacteristics(
                2 * METERS,         // reqdGpsAccuracy
                50 * METERS,        // minimumRange
                50 * METERS,        // typicalRange
                100 * METERS,       // moveDetectDistance
                0,                  // discoveryTrust
                0,                  // incrTrust
                1,                  // decrTrust
                99                  // minCount
        );
    }

    public void incrementTrust(Database db) {
        //Log.d(TAG, "incrementTrust('"+id+"') - entry.");
        long newTrust = trust + ourCharacteristics.incrTrust;
        if (newTrust > MAXIMUM_TRUST)
            newTrust = MAXIMUM_TRUST;
        if (newTrust != trust) {
            Log.d(TAG, "incrementTrust('"+logString()+"') - trust change: "+trust +"->" + newTrust);
            trust = newTrust;
            db.update(this);
        }
    }

    public void decrementTrust(Database db) {
        long oldTrust = trust;
        trust -= ourCharacteristics.decrTrust;
        if (trust < 0) {
            Log.d(TAG, "decrementTrust('"+logString()+"') - Trust below zero, dropping from database.");
            db.drop(this);
        } else if (oldTrust != trust){
            Log.d(TAG, "decrementTrust('"+logString()+"') - trust change: "+oldTrust +"->" + trust);
            db.update(this);
        }
    }

    public void updateInfo(Database.EmitterInfo emitterInfo) {
        if (emitterInfo != null) {
            if (coverage == null)
                coverage = new Coverage();
            //Log.d(TAG,"updateInfo() - Setting info for '"+id+"'");
            coverage.latitude = emitterInfo.latitude;
            coverage.longitude = emitterInfo.longitude;
            coverage.radius = emitterInfo.radius;
            trust = emitterInfo.trust;
            note = emitterInfo.note;
        }
    }

    public void updateLocation(Database db, Location gpsLoc) {
        Database.EmitterInfo emitterInfo = db.getEmitterInfo(this);

        if (blacklistEmitter()) {
            Log.d(TAG,"updateLocation() - emitter '"+this.note+"' blacklisted");
            if (emitterInfo != null)
                db.drop(this);
            return;
        }

        if (emitterInfo == null) {
            if ((gpsLoc != null) && (gpsLoc.getAccuracy() <= ourCharacteristics.reqdGpsAccuracy)) {
                Log.d(TAG, "updateLocation("+id+") - adding to database");
                if (coverage == null)
                    coverage = new Coverage();
                coverage.latitude = gpsLoc.getLatitude();
                coverage.longitude = gpsLoc.getLongitude();
                coverage.radius = 0.0f;
                db.insert(this);
            } else {
                String gpsAcc = "unknown";
                if (gpsLoc != null)
                    gpsAcc = String.valueOf(gpsLoc.getAccuracy());
                Log.d(TAG, "updateLocation("+id+") Unknown emitter, GPS not accurate (" + gpsAcc + ")");
            }
            return;
        }

//        Log.d(TAG, "updateLocation("+id+") - emitter in database");
        if (coverage == null)
            coverage = new Coverage();
        coverage.latitude = emitterInfo.latitude;
        coverage.longitude = emitterInfo.longitude;
        coverage.radius = emitterInfo.radius;
        trust = emitterInfo.trust;

        if ((gpsLoc == null) || (gpsLoc.getAccuracy() > ourCharacteristics.reqdGpsAccuracy)) {
            String gpsAcc = "unknown";
            if (gpsLoc != null)
                gpsAcc = String.valueOf(gpsLoc.getAccuracy());
            //Log.d(TAG, "updateLocation("+id+") GPS not accurate enough to update coverage (" + gpsAcc + " > " + ourCharacteristics.reqdGpsAccuracy + ")");
            return;
        }

        // If the emitter has moved, reset our data on it.
        float sampleDistance = gpsLoc.distanceTo(_getLocation());
        if (sampleDistance >= ourCharacteristics.moveDetectDistance) {
            Log.d(TAG, "updateLocation("+id+") transmitter has moved (" + gpsLoc.distanceTo(_getLocation()) + ")");
            coverage.latitude = gpsLoc.getLatitude();
            coverage.longitude = gpsLoc.getLongitude();
            coverage.radius = 0.0f;
            trust = ourCharacteristics.discoveryTrust;
            db.update(this);
            return;
        }

        //
        // See if the bounding box has increased. If anything changes, then update the database.
        boolean changed = false;
        String changeReason = "";
        if (sampleDistance > coverage.radius) {
            double north = coverage.latitude + (coverage.radius * METER_TO_DEG);
            double south = coverage.latitude - (coverage.radius * METER_TO_DEG);
            double cosLat = Math.cos(Math.toRadians(coverage.latitude));
            double east = coverage.longitude + (coverage.radius * METER_TO_DEG) * cosLat;
            double west = coverage.longitude - (coverage.radius * METER_TO_DEG) * cosLat;

            if (gpsLoc.getLatitude() > north) {
                north = gpsLoc.getLatitude();
                changed = true;
                changeReason = changeReason + "(north)";
            }
            if (gpsLoc.getLatitude() < south) {
                south = gpsLoc.getLatitude();
                changed = true;
                changeReason = changeReason + "(south)";
            }
            if (gpsLoc.getLongitude() > east) {
                east = gpsLoc.getLongitude();
                changed = true;
                changeReason = changeReason + "(east)";
            }
            if (gpsLoc.getLongitude() < west) {
                west = gpsLoc.getLongitude();
                changeReason = changeReason + "(west)";
                changed = true;
            }
            coverage.latitude = (north + south)/2.0;
            coverage.longitude = (east + west)/2.0;
            coverage.radius = (float)((north - coverage.latitude) * DEG_TO_METER);
            cosLat = Math.cos(Math.toRadians(coverage.latitude));
            if (cosLat != 0.0) {
                float ewRadius = (float) (((east - coverage.longitude) * DEG_TO_METER) / cosLat);
                if (ewRadius > coverage.radius)
                    coverage.radius = ewRadius;
            }
        }
        if (emitterInfo.note.compareTo(this.note) != 0) {
            changed = true;
            changeReason = changeReason + "(note: "+emitterInfo.note+"->"+this.note+")";
        }
        if (changed) {
            Log.d(TAG, "updateLocation("+id+") - emitter updated " + changeReason);
            db.update(this);
        }
    }

    // User facing location value. Differs from internal one in that we don't report
    // locations that are guarded due to being new or moved. And we work internally
    // with radius values that fit within a bounding box but we report a radius that
    // extends to the corners of the bounding box.
    public  Location getLocation() {
        if (trust < REQUIRED_TRUST)
            return null;
        Location boundaryBoxSized = _getLocation();
        if (boundaryBoxSized == null)
            return null;

        // Our radius is sized to fit be tangent to the sides of the
        // bounding box. But we really ought to cover the corners of
        // the box, so multiply by the square root of 2 to convert.
        boundaryBoxSized.setAccuracy((boundaryBoxSized.getAccuracy() * 1.41421356f));
        return boundaryBoxSized;
    }

    private Location _getLocation() {
        if (coverage == null)
            return null;

        final Location location = new Location(BackendService.LOCATION_PROVIDER);
        Long timeMs = System.currentTimeMillis();

        location.setTime(timeMs);
        if (Build.VERSION.SDK_INT >= 17)
            location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        location.setLatitude(coverage.latitude);
        location.setLongitude(coverage.longitude);

        // At this point, accuracy is the maximum coverage area. Scale it based on
        // the ASU as we assume we are closer to the center of the coverage if we
        // have a high signal.

        float scale = BackendService.MAXIMUM_ASU - asu + BackendService.MINIMUM_ASU;
        scale = scale / BackendService.MAXIMUM_ASU;
        float accuracy = coverage.radius * scale;

        // Hard limit the minimum accuracy based on the type of emitter
        location.setAccuracy(Math.max(accuracy,ourCharacteristics.minimumRange));

        return location;
    }

    private boolean blacklistEmitter() {
        switch (this.type) {
            case WLAN:
                return blacklistWifi();

            case MOBILE:
                return false;       // Not expecting mobile towers to move around.

        }
        return false;
    }

    private boolean blacklistWifi() {
        final String lc = note.toLowerCase(Locale.US);

        // Seen a large number of WiFi networks where the SSID is the last
        // three octets of the MAC address. Often in rural areas where the
        // only obvious source would be other automobiles. So suspect that
        // this is the default setup for a number of vehicle manufactures.
        final String macSuffix = id.substring(id.length()-8).toLowerCase(Locale.US).replace(":", "");

        return (
                // Mobile phone brands
                lc.contains("android") ||                   // mobile tethering
                lc.contains("ipad") ||                      // mobile tethering
                lc.contains("iphone") ||                    // mobile tethering
                lc.contains("motorola") ||                  // mobile tethering
                lc.endsWith(" phone") ||                    // "Lans Phone" seen
                lc.startsWith("moto ") ||                   // "Moto E (4) 9509" seen
                note.startsWith("MOTO") ||                  // "MOTO9564" and "MOTO9916" seen
                note.startsWith("Samsung Galaxy") ||        // mobile tethering
                lc.startsWith("lg aristo") ||               // "LG Aristo 7124" seen

                // Mobile network brands
                lc.contains("mobile hotspot") ||            // e.g "MetroPCS Portable Mobile Hotspot"
                note.startsWith("CellSpot") ||              // T-Mobile US portable cell based WiFi
                note.startsWith("Verizon-") ||              // Verizon mobile hotspot

                // Per some instructional videos on YouTube, recent (2015 and later)
                // General Motors built vehicles come with a default WiFi SSID of the
                // form "WiFi Hotspot 1234" where the 1234 is different for each car.
                // The SSID can be changed but the recommended SSID to change to
                // is of the form "first_name vehicle_model" (e.g. "Bryces Silverado").
                lc.startsWith("wifi hotspot ") ||           // Default GM vehicle WiFi name
                lc.endsWith("corvette") ||                 // Chevy Corvette. "TS Corvette" seen.
                lc.endsWith("silverado") ||                // GMC Silverado. "Bryces Silverado" seen.
                lc.endsWith("chevy") ||                    // Chevrolet. "Davids Chevy" seen
                lc.endsWith("truck") ||                    // "Morgans Truck" and "Wally Truck" seen
                lc.endsWith("suburban") ||                 // Chevy/GMC Suburban. "Laura Suburban" seen
                lc.endsWith("terrain") ||                  // GMC Terrain. "Nelson Terrain" seen
                lc.endsWith("sierra") ||                   // GMC pickup. "dees sierra" seen

                // Per an instructional video on YouTube, recent (2014 and later) Chrysler-Fiat
                // vehicles have a SSID of the form "Chrysler uconnect xxxxxx" where xxxxxx
                // seems to be a hex digit string (suffix of BSSID?).
                lc.contains(" uconnect ") ||                // Chrysler built vehicles

                // Per instructional video on YouTube, Mercedes cars have and SSID of
                // "MB WLAN nnnnn" where nnnnn is a 5 digit number.
                lc.startsWith("mb wlan ") ||                // Mercedes

                // Other automobile manufactures default naming

                lc.equals(macSuffix) ||                     // Apparent default SSID name for many cars
                note.startsWith("Audi") ||                  // some cars seem to have this AP on-board
                note.startsWith("Chevy ") ||                // "Chevy Cruz 7774" seen.
                note.startsWith("GMC WiFi") ||              // General Motors
                note.startsWith("MyVolvo") ||               // Volvo in car WiFi

                // Transit agencies
                lc.contains("admin@ms ") ||                 // WLAN network on Hurtigruten ships
                lc.contains("contiki-wifi") ||              // WLAN network on board of bus
                lc.contains("db ic bus") ||                 // WLAN network on board of German bus
                lc.contains("deinbus.de") ||                // WLAN network on board of German bus
                lc.contains("ecolines") ||                  // WLAN network on board of German bus
                lc.contains("eurolines_wifi") ||            // WLAN network on board of German bus
                lc.contains("fernbus") ||                   // WLAN network on board of German bus
                lc.contains("flixbus") ||                   // WLAN network on board of German bus
                lc.contains("guest@ms ") ||                 // WLAN network on Hurtigruten ships
                lc.contains("muenchenlinie") ||             // WLAN network on board of bus
                lc.contains("postbus") ||                   // WLAN network on board of bus line
                lc.contains("telekom_ice") ||               // WLAN network on DB trains
                lc.contentEquals("amtrak") ||               // WLAN network on USA Amtrak trains
                lc.contentEquals("amtrakconnect") ||        // WLAN network on USA Amtrak trains
                lc.contentEquals("megabus") ||              // WLAN network on MegaBus US bus
                note.startsWith("BusWiFi") ||               // Some transit buses in LA Calif metro area
                note.startsWith("CoachAmerica") ||          // Charter bus service with on board WiFi
                note.startsWith("DisneyLandResortExpress") || // Bus with on board WiFi
                note.startsWith("TaxiLinQ") ||              // Taxi cab wifi system.
                note.startsWith("TransitWirelessWiFi") ||   // New York City public transport wifi

                // Dash cams
                note.startsWith("YICarCam") ||              // Dashcam WiFi.

                // Other
                lc.contains("mobile") ||                    // What I'd put into a mobile hotspot name
                lc.contains("nsb_interakti")                // ???

                // lc.endsWith("_nomap")                    // Google unsubscibe option
        );
    }
}
