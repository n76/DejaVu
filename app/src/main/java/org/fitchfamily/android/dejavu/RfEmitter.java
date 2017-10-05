package org.fitchfamily.android.dejavu;

import android.location.Location;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import java.util.Locale;

/**
 * Created by tfitch on 8/27/17.
 */

/**
 * Models everything we know about an RF emitter: Its identification, most recently received
 * signal level, an estimate of its coverage (center point and radius), how much we trust
 * the emitter (can we use information about it to compute a position), etc.
 *
 * When an RF emitter is first observed we create a new object and, if information exists in
 * the database, populate it from saved information.
 *
 * Periodically we sync our current information about the emitter back to the flash memory
 * based storage.
 *
 * Trust is incremented everytime we see the emitter and the new observation has data compatible
 * with our current model. We decrease (or set to zero) our trust if it we think we should have
 * seen the emitter at our current location or if it looks like the emitter may have moved.
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

    public enum EmitterStatus {
        STATUS_UNKNOWN,             // Newly discovered emitter, no data for it at all
        STATUS_NEW,                 // Not in database but we've got location data for it
        STATUS_CHANGED,             // In database but something has changed
        STATUS_CACHED,              // In database no changes pending
        STATUS_BLACKLISTED          // Has been blacklisted
    };

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

    private int ageSinceLastUse;        // Count of periods since last used (for caching purposes)

    private EmitterStatus status;

    RfEmitter(RfIdentification ident) {
        initSelf(ident.getRfType(), ident.getRfId(), 0);
    }

    RfEmitter(RfIdentification ident, int signal) {
        initSelf(ident.getRfType(), ident.getRfId(), signal);
    }

    RfEmitter(Observation o) {
        initSelf(o.getIdent().getRfType(), o.getIdent().getRfId(), o.getAsu());
    }

    RfEmitter(EmitterType mType, String ident, int signal) {
        initSelf(mType, ident, signal);
    }

    private void initSelf(EmitterType mType, String ident, int signal) {
        type = mType;
        id = ident;
        setAsu(signal);
        coverage = null;
        ourCharacteristics = getRfCharacteristics(mType);
        trust = ourCharacteristics.discoveryTrust;
        note = "";
        resetAge();
        status = EmitterStatus.STATUS_UNKNOWN;
        if (blacklistEmitter())
            changeStatus(EmitterStatus.STATUS_BLACKLISTED, "initSelf()");
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

    public RfIdentification getRfIdent() {
        return new RfIdentification(id, type);
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

    public void setAsu(int signal) {
        if (signal > BackendService.MAXIMUM_ASU)
            asu = BackendService.MAXIMUM_ASU;
        else if (signal < BackendService.MINIMUM_ASU)
            asu = BackendService.MINIMUM_ASU;
        else
            asu = signal;
    }

    public void setNote(String n) {
        if (note != n) {
            note = n;
            // TODO: If note changes to one that is blacklisted we should blacklist it.
        }
    }

    public String getNote() {
        return note;
    }

    public int getAge() {
        return ageSinceLastUse;
    }

    public void resetAge() {
        ageSinceLastUse = 0;
    }

    public void incrementAge() {
        ageSinceLastUse++;
    }

    public boolean syncNeeded() {
        return (status == EmitterStatus.STATUS_NEW) ||
                (status == EmitterStatus.STATUS_CHANGED) ||
                ((status == EmitterStatus.STATUS_BLACKLISTED) &&
                        (coverage != null));
    }

    public void sync(Database db) {
        EmitterStatus newStatus = status;

        switch (status) {
            case STATUS_UNKNOWN:
                // Not in database, we have no location. Nothing to sync.
                break;

            case STATUS_BLACKLISTED:
                // If our coverage value is not null it implies that we exist in the
                // database. If so we ought to remove the entry.
                if (coverage != null) {
                    db.drop(this);
                    coverage = null;
                }
                break;

            case STATUS_NEW:
                // Not in database, we have location. Add to database
                db.insert(this);
                newStatus = EmitterStatus.STATUS_CACHED;
                break;

            case STATUS_CHANGED:
                // In database but we have changes
                if (trust < 0) {
                    Log.d(TAG, "sync('" + logString() + "') - Trust below zero, dropping from database.");
                    db.drop(this);
                } else
                    db.update(this);
                newStatus = EmitterStatus.STATUS_CACHED;
                break;

            case STATUS_CACHED:
                // In database but we don't have any changes
                break;
        }
        changeStatus(newStatus, "sync('"+logString()+"')");

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

    public void incrementTrust() {
        //Log.d(TAG, "incrementTrust('"+id+"') - entry.");
        if (canUpdate()) {
            long newTrust = trust + ourCharacteristics.incrTrust;
            if (newTrust > MAXIMUM_TRUST)
                newTrust = MAXIMUM_TRUST;
            if (newTrust != trust) {
                // Log.d(TAG, "incrementTrust('" + logString() + "') - trust change: " + trust + "->" + newTrust);
                trust = newTrust;
                changeStatus(EmitterStatus.STATUS_CHANGED, "incrementTrust('"+logString()+"')");
            }
        }
    }

    public void decrementTrust() {
        if (canUpdate()) {
            long oldTrust = trust;
            trust -= ourCharacteristics.decrTrust;
            // Log.d(TAG, "decrementTrust('" + logString() + "') - trust change: " + oldTrust + "->" + trust);
            changeStatus(EmitterStatus.STATUS_CHANGED, "decrementTrust('"+logString()+"')");
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
            changeStatus(EmitterStatus.STATUS_CACHED, "updateInfo('"+logString()+"')");
        }
    }

    public void updateLocation(Location gpsLoc) {

        if (status == EmitterStatus.STATUS_BLACKLISTED)
            return;

        if ((gpsLoc == null) || (gpsLoc.getAccuracy() > ourCharacteristics.reqdGpsAccuracy)) {
            //String gpsAcc = "unknown";
            //if (gpsLoc != null)
            //    gpsAcc = String.valueOf(gpsLoc.getAccuracy());
            //Log.d(TAG, "updateLocation("+id+") GPS not accurate enough to update coverage (" + gpsAcc + " > " + ourCharacteristics.reqdGpsAccuracy + ")");
            return;
        }

        if (coverage == null) {
            Log.d(TAG, "updateLocation("+id+") emitter is new.");
            coverage = new Coverage();
            coverage.latitude = gpsLoc.getLatitude();
            coverage.longitude = gpsLoc.getLongitude();
            coverage.radius = 0.0f;
            changeStatus(EmitterStatus.STATUS_NEW, "updateLocation('"+logString()+"')");
            return;
        }

        // If the emitter has moved, reset our data on it.
        float sampleDistance = gpsLoc.distanceTo(_getLocation());
        if (sampleDistance >= ourCharacteristics.moveDetectDistance) {
            Log.d(TAG, "updateLocation("+id+") emitter has moved (" + gpsLoc.distanceTo(_getLocation()) + ")");
            coverage.latitude = gpsLoc.getLatitude();
            coverage.longitude = gpsLoc.getLongitude();
            coverage.radius = 0.0f;
            trust = ourCharacteristics.discoveryTrust;
            changeStatus(EmitterStatus.STATUS_CHANGED, "updateLocation('"+logString()+"')");
            return;
        }

        //
        // See if the bounding box has increased.

        boolean changed = false;
        if (sampleDistance > coverage.radius) {
            double north = coverage.latitude + (coverage.radius * METER_TO_DEG);
            double south = coverage.latitude - (coverage.radius * METER_TO_DEG);
            double cosLat = Math.cos(Math.toRadians(coverage.latitude));
            double east = coverage.longitude + (coverage.radius * METER_TO_DEG) * cosLat;
            double west = coverage.longitude - (coverage.radius * METER_TO_DEG) * cosLat;

            if (gpsLoc.getLatitude() > north) {
                north = gpsLoc.getLatitude();
                changed = true;
            }
            if (gpsLoc.getLatitude() < south) {
                south = gpsLoc.getLatitude();
                changed = true;
            }
            if (gpsLoc.getLongitude() > east) {
                east = gpsLoc.getLongitude();
                changed = true;
            }
            if (gpsLoc.getLongitude() < west) {
                west = gpsLoc.getLongitude();
                changed = true;
            }
            if (changed) {
                changeStatus(EmitterStatus.STATUS_CHANGED, "updateLocation('"+logString()+"')");
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
        }
    }

    // User facing location value. Differs from internal one in that we don't report
    // locations that are guarded due to being new or moved. And we work internally
    // with radius values that fit within a bounding box but we report a radius that
    // extends to the corners of the bounding box.
    public  Location getLocation() {
        if ((trust < REQUIRED_TRUST) || (status == EmitterStatus.STATUS_BLACKLISTED))
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

    private boolean canUpdate() {
        boolean rslt = true;
        switch (status) {
            case STATUS_BLACKLISTED:
            case STATUS_UNKNOWN:
                rslt = false;
                break;
        }
        return rslt;
    }

    private void changeStatus( EmitterStatus newStatus, String info) {
        if (newStatus == status)
            return;

        EmitterStatus finalStatus = status;
        switch (finalStatus) {
            case STATUS_BLACKLISTED:
                // Once blacklisted cannot change.
                break;

            case STATUS_CACHED:
            case STATUS_CHANGED:
                switch (newStatus) {
                    case STATUS_BLACKLISTED:
                    case STATUS_CACHED:
                    case STATUS_CHANGED:
                        finalStatus = newStatus;
                        break;
                }
                break;

            case STATUS_NEW:
                switch (newStatus) {
                    case STATUS_BLACKLISTED:
                    case STATUS_CACHED:
                        finalStatus = newStatus;
                        break;
                }
                break;

            case STATUS_UNKNOWN:
                switch (newStatus) {
                    case STATUS_BLACKLISTED:
                    case STATUS_CACHED:
                    case STATUS_NEW:
                        finalStatus = newStatus;
                }
                break;
        }

        //Log.d(TAG,"changeStatus("+newStatus+", "+ info + ") " + status + " -> " + finalStatus);
        status = finalStatus;
    }
}
