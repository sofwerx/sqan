package org.sofwerx.sqan.manet.common.issues;

public class SqAnAppIssue extends AbstractManetIssue {
    private String message;

    public SqAnAppIssue(boolean blocking, String message) {
        super();
        this.message = message;
        this.isBlocker = blocking;
    }

    @Override
    public String toString() {
        if (message == null)
            return "App code"+(isBlocker?" (blocking)":"");
        return message+(isBlocker?" (blocking)":"");
    }
}
