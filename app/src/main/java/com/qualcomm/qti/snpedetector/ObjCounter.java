package com.qualcomm.qti.snpedetector;

import java.util.ArrayList;

public class ObjCounter implements Comparable<ObjCounter> {
    public String ObjType;
    public int count;
    public int index;

    public static ArrayList<ObjCounter> createCounters() {
        final ArrayList<ObjCounter> ObjCounts = new ArrayList<>();
        for (int i = 1; i <= 90; ++i) {
            ObjCounter obj = new ObjCounter();
            obj.index = i;
            obj.count = 0;
            ObjCounts.add(obj);
        }
        return ObjCounts;

    }

    @Override
    public int compareTo(ObjCounter o) {
        return (this.count - o.count);
    }
}