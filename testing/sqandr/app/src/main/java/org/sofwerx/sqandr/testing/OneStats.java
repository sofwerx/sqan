package org.sofwerx.sqandr.testing;

public class OneStats {
    private int complete;
    private int unique;
    private int total;

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
