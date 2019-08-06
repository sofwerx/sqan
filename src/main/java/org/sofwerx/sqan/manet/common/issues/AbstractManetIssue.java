package org.sofwerx.sqan.manet.common.issues;

/**
 * Used to identify some current issue that is blocking or degrading the MANET
 */
public abstract class AbstractManetIssue {
    protected long time;
    protected boolean isBlocker;
    public AbstractManetIssue() {
        time = System.currentTimeMillis();
    }

    public boolean isBlocker() { return isBlocker; }
    public void setBlocker(boolean blocker) { isBlocker = blocker; }

    public abstract String toString();
}
