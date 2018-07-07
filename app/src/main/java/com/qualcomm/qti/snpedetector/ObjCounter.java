package com.qualcomm.qti.snpedetector;

import java.util.ArrayList;

public class ObjCounter implements Comparable<ObjCounter> {
    public String ObjType;
    public int count;

    public static ArrayList<ObjCounter> createCounters() {
        final ArrayList<ObjCounter> ObjCounts = new ArrayList<>();
        for (int i = 0; i < 90; ++i)
            ObjCounts.add(new ObjCounter());
        return ObjCounts;

    }

    @Override
    public int compareTo(ObjCounter o) {
        return (this.count - o.count);
    }
}