package org.sofwerx.sqan.manet.common.issues;

public class HardwareIssue extends AbstractManetIssue {
    protected String message;

    public HardwareIssue(boolean blocking,String message) {
        super();
        this.message = message;
        this.isBlocker = blocking;
    }

    @Override
    public String toString() {
        return message;
    }
}
