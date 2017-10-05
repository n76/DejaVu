package org.fitchfamily.android.dejavu;

/**
 * Created by tfitch on 10/4/17.
 */

import org.fitchfamily.android.dejavu.RfEmitter.EmitterType;

import java.util.Comparator;

/**
 * This class forms a complete identification for a RF emitter.
 *
 * All it has are two fields: A rfID string that must be unique within a type
 * or class of emitters. And a rtType value that indicates the type of RF
 * emitter we are dealing with.
 */

public class RfIdentification implements Comparable<RfIdentification>{
    private String rfId;
    private EmitterType rfType;

    RfIdentification(String id, EmitterType t) {
        rfId = id;
        rfType = t;
    }

    public int compareTo(RfIdentification o) {
        int rslt = o.rfType.ordinal() - rfType.ordinal();
        if (rslt == 0)
            rslt = rfId.compareTo(o.rfId);
        return rslt;
    }

    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;

        return (toString().compareTo(o.toString()) == 0);
    }

    public String getRfId() {
        return rfId;
    }

    public EmitterType getRfType() {
        return rfType;
    }

    public int hashCode() {
        return toString().hashCode();
    }

    public String toString() {
        return "rfId=" + rfId + ", rfType=" + rfType;
    }

}
