package org.fitchfamily.android.dejavu;

import android.accessibilityservice.AccessibilityService;
import android.location.Location;

/**
 * Created by tfitch on 9/28/17.
 */

public class BoundingBox {
    private static final double DEG_TO_METER = 111225.0;
    private static final double METER_TO_DEG = 1.0 / DEG_TO_METER;

    private double north;
    private double south;
    private double east;
    private double west;

    BoundingBox() {
        reset();
    }

    BoundingBox(Location loc) {
        reset();
        update(loc);
    }

    BoundingBox(double lat, double lon, float radius) {
        reset();
        update(lat, lon, radius);
    }

    public void update(Location loc) {
        update(loc.getLatitude(), loc.getLongitude(), loc.getAccuracy());
    }

    public void update(double lat, double lon, float radius) {
        double locNorth = lat + (radius * METER_TO_DEG);
        double locSouth = lat - (radius * METER_TO_DEG);
        double cosLat = Math.cos(Math.toRadians(lat));
        double locEast = lon + (radius * METER_TO_DEG) * cosLat;
        double locWest = lon - (radius * METER_TO_DEG) * cosLat;

        north = Math.max(north,locNorth);
        south = Math.min(south,locSouth);
        east = Math.max(east,locEast);
        west = Math.min(west,locWest);
    }

    public void update(double lat, double lon) {
        north = Math.max(north,lat);
        south = Math.min(south,lat);
        east = Math.max(east,lon);
        west = Math.min(west,lon);
    }

    public double getNorth() {
        return north;
    }

    public double getSouth() {
        return south;
    }

    public double getEast() {
        return east;
    }

    public double getWest() {
        return west;
    }

    @Override
    public String toString() {
        return "(" + north + "," + south + "," + east + "," + west + ")";
    }

    private void reset() {
        north = -91.0;      // Impossibly south
        south = 91.0;       // Impossibly north
        east = -181.0;      // Impossibly west
        west = 181.0;       // Impossibly east
    }

}
