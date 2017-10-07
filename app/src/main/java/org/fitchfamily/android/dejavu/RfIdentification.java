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
