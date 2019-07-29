package org.sofwerx.sqandr.testing;

import android.util.Log;

import org.sofwerx.sqan.Config;

import java.util.ArrayList;

public class OneStats {
    private final static String TAG = Config.TAG+".OneStats";
    private int complete = 0;
    private int unique = 0;
    private int total = 0;
    private ArrayList<TimeBucket> buckets = new ArrayList<>();

    private final static int TIME_BUCKET_STEPS_IN_SECONDS = 1;
    private final static long TIME_BUCKET_SIZE_MS = TIME_BUCKET_STEPS_IN_SECONDS * 1000l;
    private final static int BUCKETS_TO_COLLECT = 120;
    class TimeBucket {
        public long end;
        public int received = 0;
    }

    public void addBytes(int bytes) {
        Log.d(TAG,"addBytes("+bytes+"b)");
        TimeBucket bucket;
        if (buckets.isEmpty() || (System.currentTimeMillis() > buckets.get(buckets.size()-1).end)) {
            bucket = new TimeBucket();
            buckets.add(bucket);
            bucket.end = System.currentTimeMillis() + TIME_BUCKET_SIZE_MS;
            if (buckets.size() > BUCKETS_TO_COLLECT)
                buckets.remove(0);
        } else
            bucket = buckets.get(buckets.size()-1);
        bucket.received += getEffectiveSize(bytes);
    }

    public int getBandwidth() {
        if (buckets.isEmpty())
            return 0;
        int bps = 0;
        int secondsStored = buckets.size() * TIME_BUCKET_STEPS_IN_SECONDS;
        long first = System.currentTimeMillis() - 1000l * 60l;
        synchronized (buckets) {
            for (TimeBucket bucket : buckets) {
                if (bucket.end > first)
                    bps += bucket.received;
            }
        }
        return bps/secondsStored;
    }

    private int getEffectiveSize(int size) {
        return size - 5 - (15 * size/230);
    }

    public void clear() {
        complete = 0;
        unique = 0;
        total = 0;
        buckets.clear();
    }

    public int getSuccessRate() {
        if (total == 0)
            return 0;
        return 100*unique/total;
    }

    public int getComplete() {
        return complete;
    }

    public void setComplete(int complete) {
        this.complete = complete;
    }

    public int getUnique() {
        return unique;
    }

    public void setUnique(int unique) {
        this.unique = unique;
    }

    public int getTotal() {
        return total;
    }

    public void incrementTotalSent() {
        total++;
    }

    public void setTotal(int total) { this.total = total; }

    public void incrementComplete() { complete++; }

    public void incrementUnique() { unique++; }
}
