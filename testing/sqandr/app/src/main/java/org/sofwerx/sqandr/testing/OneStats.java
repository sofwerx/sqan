package org.sofwerx.sqandr.testing;

import java.util.ArrayList;

public class OneStats {
    private int complete;
    private int unique;
    private int total;
    private ArrayList<TimeBucket> buckets = new ArrayList<>();

    private final static long TIME_BUCKET_STEPS = 1000l * 5l;
    private final static long BUCKETS_PER_MIN = 1000l * 60l / TIME_BUCKET_STEPS;
    class TimeBucket {
        public long end;
        public int received = 0;
    }

    private int getEffectiveSize(int size) {
        return size - 5 - (15 * size/230);
    }

    public void addBytes(int bytes) {
        TimeBucket bucket;
        if (buckets.isEmpty() || (System.currentTimeMillis() > buckets.get(buckets.size()-1).end)) {
            bucket = new TimeBucket();
            bucket.end = System.currentTimeMillis() + TIME_BUCKET_STEPS;
            if (buckets.size() > BUCKETS_PER_MIN)
                buckets.remove(0);
        } else
            bucket = buckets.get(buckets.size()-1);
        bucket.received += getEffectiveSize(bytes);
    }

    public int getBandwidth() {
        if (buckets.isEmpty())
            return 0;
        int bps = 0;
        long first = System.currentTimeMillis() - 1000l * 60l;
        synchronized (buckets) {
            for (TimeBucket bucket : buckets) {
                if (bucket.end > first)
                    bps += bucket.received;
            }
        }
        return bps/60;
    }

    public void clear() {
        complete = 0;
        unique = 0;
        total = 0;
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
