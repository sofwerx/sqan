package org.sofwerx.sqan.manet.common.issues;

public class SdrConfigIssue extends AbstractManetIssue {
    private String message;

    public SdrConfigIssue(boolean blocking, String message) {
        super();
        this.message = message;
        this.isBlocker = blocking;
    }

    @Override
    public String toString() {
        if (message == null)
            return "SDR Config"+(isBlocker?" (blocking)":"");
        return message+(isBlocker?" (blocking)":"");
    }
}
