package org.fitchfamily.android.dejavu;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.util.Log;

import java.util.HashSet;

/**
 * Created by tfitch on 9/1/17.
 */

public class Database extends SQLiteOpenHelper {


    private static final String TAG = "DejaVu DB";

    private static final int VERSION = 1;
    private static final String NAME = "rf.db";

    public static final String TABLE_SAMPLES = "emitters";

    public static final String COL_TYPE = "rfType";
    public static final String COL_RFID = "rfID";
    public static final String COL_TRUST = "trust";
    public static final String COL_LAT = "latitude";
    public static final String COL_LON = "longitude";
    public static final String COL_RAD = "radius";
    public static final String COL_NOTE = "note";

    private SQLiteDatabase database;
    private boolean withinTransaction;
    private boolean updatesMade;

    private SQLiteStatement sqlSampleInsert;
    private SQLiteStatement sqlSampleUpdate;
    private SQLiteStatement sqlAPdrop;

    public class EmitterInfo {
        public double latitude;
        public double longitude;
        public float radius;
        public long trust;
        public String note;
    }

    public Database(Context context) {
        super(context, NAME, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        database = db;
        withinTransaction = false;
        // Always create version 1 of database, then update the schema
        // in the same order it might occur "in the wild". Avoids having
        // to check to see if the table exists (may be old version)
        // or not (can be new version).
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_SAMPLES + "(" +
                COL_RFID + " STRING PRIMARY KEY, " +
                COL_TYPE + " STRING, " +
                COL_TRUST + " INTEGER, " +
                COL_LAT + " REAL, " +
                COL_LON + " REAL, " +
                COL_RAD + " REAL, " +
                COL_NOTE + " STRING);");

        onUpgrade(db, 1, VERSION);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // no old versions (yet)
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
    }

    public synchronized void beginTransaction() {
        //Log.d(TAG,"beginTransaction()");
        if (withinTransaction) {
            Log.d(TAG,"beginTransaction() - Already in a transaction?");
            return;
        }
        withinTransaction = true;
        updatesMade = false;
        database = getWritableDatabase();

        sqlSampleInsert = database.compileStatement("INSERT INTO " +
                TABLE_SAMPLES + "("+
                COL_RFID + ", " +
                COL_TYPE + ", " +
                COL_TRUST + ", " +
                COL_LAT + ", " +
                COL_LON + ", " +
                COL_RAD + ", " +
                COL_NOTE + ") " +
                "VALUES (?, ?, ?, ?, ?, ?, ?);");

        sqlSampleUpdate = database.compileStatement("UPDATE " +
                TABLE_SAMPLES + " SET "+
                COL_TRUST + "=?, " +
                COL_LAT + "=?, " +
                COL_LON + "=?, " +
                COL_RAD + "=?, " +
                COL_NOTE + "=? " +
                "WHERE " + COL_RFID + "=? AND " + COL_TYPE + "=?;");

        sqlAPdrop = database.compileStatement("DELETE FROM " +
                TABLE_SAMPLES +
                " WHERE " + COL_RFID + "=? AND " + COL_TYPE  + "=?;");

        database.beginTransaction();
    }

    public synchronized void endTransaction() {
        //Log.d(TAG,"endTransaction()");
        if (!withinTransaction) {
            Log.d(TAG,"Asked to end transaction but we are not in one???");
        }

        if (updatesMade) {
            //Log.d(TAG,"endTransaction() - Setting transaction successful.");
            database.setTransactionSuccessful();
        }
        updatesMade = false;
        database.endTransaction();
        withinTransaction = false;
    }

    public synchronized void drop(RfEmitter emitter) {
        //Log.d(TAG, "Dropping " + emitter.logString() + " from db");

        sqlAPdrop.bindString(1, emitter.getId());
        sqlAPdrop.bindString(2, emitter.getTypeString());
        sqlAPdrop.executeInsert();
        sqlAPdrop.clearBindings();
        updatesMade = true;
    }

    public synchronized void insert(RfEmitter emitter) {
        synchronized (sqlSampleInsert) {
            //Log.d(TAG, "Inserting " + emitter.logString() + " into db");
            sqlSampleInsert.bindString(1, emitter.getId());
            sqlSampleInsert.bindString(2, String.valueOf(emitter.getType()));
            sqlSampleInsert.bindString(3, String.valueOf(emitter.getTrust()));
            sqlSampleInsert.bindString(4, String.valueOf(emitter.getLat()));
            sqlSampleInsert.bindString(5, String.valueOf(emitter.getLon()));
            sqlSampleInsert.bindString(6, String.valueOf(emitter.getRadius()));
            sqlSampleInsert.bindString(7, emitter.getNote());

            sqlSampleInsert.executeInsert();
            sqlSampleInsert.clearBindings();
            updatesMade = true;
        }
    }

    public synchronized void update(RfEmitter emitter) {
        //Log.d(TAG, "Updating " + emitter.logString() + " in db");
        // the data fields
        sqlSampleUpdate.bindString(1, String.valueOf(emitter.getTrust()));
        sqlSampleUpdate.bindString(2, String.valueOf(emitter.getLat()));
        sqlSampleUpdate.bindString(3, String.valueOf(emitter.getLon()));
        sqlSampleUpdate.bindString(4, String.valueOf(emitter.getRadius()));
        sqlSampleUpdate.bindString(5, emitter.getNote());

        // the Where fields
        sqlSampleUpdate.bindString(6, emitter.getId());
        sqlSampleUpdate.bindString(7, String.valueOf(emitter.getType()));
        sqlSampleUpdate.executeInsert();
        sqlSampleUpdate.clearBindings();
        updatesMade = true;
    }

    public synchronized EmitterInfo getEmitterInfo(RfEmitter emitter) {
        EmitterInfo rslt = null;
        String query = "SELECT " + COL_TRUST + ", " +
                COL_LAT + ", " +
                COL_LON + ", " +
                COL_RAD + ", " +
                COL_NOTE + " " +
                " FROM " + TABLE_SAMPLES +
                " WHERE " + COL_TYPE + "='" + emitter.getType() +
                "' AND " + COL_RFID + "='" + emitter.getId() + "';";

        //Log.d(TAG, "getEmitterInfo(): query='"+query+"'");
        Cursor cursor = getReadableDatabase().rawQuery(query, null);
        try {
            if (cursor.moveToFirst()) {
                rslt = new EmitterInfo();
                rslt.trust = (int) cursor.getLong(0);
                rslt.latitude = (double) cursor.getDouble(1);
                rslt.longitude = (double) cursor.getDouble(2);
                rslt.radius = (float) cursor.getDouble(3);
                rslt.note = cursor.getString(4);

                if (rslt.note == null)
                    rslt.note = "";
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return rslt;
    }

    public synchronized HashSet<RfEmitter> getEmitters(RfEmitter.EmitterType rfType, BoundingBox bb) {
        HashSet<RfEmitter> rslt = new HashSet<RfEmitter>();
        String query = "SELECT " +
                COL_RFID + ", " +
                COL_TRUST + ", " +
                COL_LAT + ", " +
                COL_LON + ", " +
                COL_RAD + ", " +
                COL_NOTE + " " +
                " FROM " + TABLE_SAMPLES +
                " WHERE " + COL_TYPE + "='" + rfType +
                "' AND " + COL_LAT + ">='" + bb.getSouth() +
                "' AND " + COL_LAT + "<='" + bb.getNorth() +
                "' AND " + COL_LON + ">='" + bb.getWest() +
                "' AND " + COL_LON + "<='" + bb.getEast() + "';";

        //Log.d(TAG, "getEmitters(): query='"+query+"'");
        Cursor cursor = getReadableDatabase().rawQuery(query, null);
        try {
            if (cursor.moveToFirst()) {
                do {
                    RfEmitter e = new RfEmitter(rfType, cursor.getString(0), 0);
                    EmitterInfo ei = new EmitterInfo();
                    ei.trust = (int) cursor.getLong(1);
                    ei.latitude = (double) cursor.getDouble(2);
                    ei.longitude = (double) cursor.getDouble(3);
                    ei.radius = (float) cursor.getDouble(4);
                    ei.note = cursor.getString(5);
                    if (ei.note == null)
                        ei.note = "";
                    e.updateInfo(ei);
                    rslt.add(e);
                } while (cursor.moveToNext());
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return rslt;
    }

    public synchronized RfEmitter getEmitter(String id) {
        RfEmitter rslt = null;
        String query = "SELECT " +
                COL_TYPE + ", " +
                COL_TRUST + ", " +
                COL_LAT + ", " +
                COL_LON + ", " +
                COL_RAD + ", " +
                COL_NOTE + " " +
                " FROM " + TABLE_SAMPLES +
                " WHERE " + COL_RFID + "='" + id + "';";

        // Log.d(TAG, "getEmitter(): query='"+query+"'");
        Cursor cursor = getReadableDatabase().rawQuery(query, null);
        try {
            if (cursor.moveToFirst()) {
                rslt = new RfEmitter(cursor.getString(0), id, 0);
                EmitterInfo ei = new EmitterInfo();
                ei.trust = (int) cursor.getLong(1);
                ei.latitude = (double) cursor.getDouble(2);
                ei.longitude = (double) cursor.getDouble(3);
                ei.radius = (float) cursor.getDouble(4);
                ei.note = cursor.getString(5);
                if (ei.note == null)
                    ei.note = "";
                rslt.updateInfo(ei);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return rslt;
    }

}
