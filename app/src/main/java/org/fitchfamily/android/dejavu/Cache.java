package org.fitchfamily.android.dejavu;

/**
 * Created by tfitch on 10/4/17.
 */

import android.util.Log;

import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

/**
 * All access to the database is done through this cache:
 *
 * When a RF emitter is seen a get() call is made to the cache. If we have a cache hit
 * the information is directly returned. If we have a cache miss we create a new record
 * and populate it with either default information or information from the flash based
 * database (if it exists in the database).
 *
 * Periodically we are asked to sync any new or changed RF emitter information to the
 * database. When that occurs we group all the changes in one database transaction for
 * speed.
 *
 * If an emitter has not been used for a while we will remove it from the cache (only
 * immediately after a sync() operation so the record will be clean). If the cache grows
 * too large we will clear it to conservery RAM (this should never happen). Again the
 * clear operation will only occur after a sync() so any dirty records will be flushed
 * to the database.
 */
public class Cache {
    private static final int MAX_WORKING_SET_SIZE = 200;
    private static final int MAX_AGE = 30;

    private static final String TAG="DejaVu Cache";

    /**
     * DB positive query cache (found in the db).
     */
    private final Map<String,RfEmitter> workingSet = new HashMap<String,RfEmitter>();

    public RfEmitter get(String id, RfEmitter.EmitterType t, Database db) {
        if ((id == null) || (db == null))
            return null;
        RfIdentification ident = new RfIdentification(id,t);
        return get(ident, db);
    }

    /**
     * Queries the cache with the given RfIdentification
     * @param id
     * @return the emitter
     *
     * If the emitter does not exist in the cache, it is
     * added (from the database if known or a new "unknown"
     * entry is created).
     */
    public synchronized RfEmitter get(RfIdentification id, Database db) {
        if ((id == null) || (db == null))
            return null;

        String key = id.toString();
        RfEmitter rslt = workingSet.get(key);
        if (rslt == null) {
            rslt = db.getEmitter(id);
            if (rslt == null)
                rslt = new RfEmitter(id);
            workingSet.put(key,rslt);
            //Log.d(TAG,"get('"+key+"') - Added to cache.");
        }
        rslt.resetAge();
        return rslt;
    }

    public synchronized Cache clear() {
        workingSet.clear();
        Log.d(TAG,"clear() - entry");
        return this;
    }

    /**
     * Updates the database entry for any new or changed emitters
     * @param db The database we are using
     *
     * Once the database has been synchronized, we reset our cache.
     */
    public synchronized void sync(Database db) {
        boolean doSync = false;

        // Scan all of our emitters to see
        // 1. If any have dirty data to sync to the flash database
        // 2. If any have been unused long enough to remove from cache

        Set<RfIdentification> agedSet = new HashSet<RfIdentification>();
        for (Map.Entry<String,RfEmitter> e : workingSet.entrySet()) {
            RfEmitter rfE = e.getValue();
            doSync |= rfE.syncNeeded();

            //Log.d(TAG,"sync('"+rfE.getRfIdent()+"') - Age: " + rfE.getAge());
            if (rfE.getAge() >= MAX_AGE)
                agedSet.add(rfE.getRfIdent());
            rfE.incrementAge();
        }

        // Remove aged out items from cache
        for (RfIdentification id : agedSet) {
            String key = id.toString();
            //Log.d(TAG,"sync('"+key+"') - Aged out, removed from cache.");
            workingSet.remove(key);
        }

        if (doSync) {
            db.beginTransaction();
            for (Map.Entry<String, RfEmitter> e : workingSet.entrySet()) {
                e.getValue().sync(db);
            }
            db.endTransaction();
        }
        if (workingSet.size() > MAX_WORKING_SET_SIZE) {
            Log.d(TAG, "sync() - Clearing working set.");
            workingSet.clear();
        }
    }

}
