package org.sofwerx.sqan.manet.common.issues;

import org.sofwerx.sqan.util.StringUtil;

public abstract class AbstractCommsIssue {
    protected long time = System.currentTimeMillis();

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(this.getClass().getSimpleName());
        if (time > 0l)
            sb.append(", "+StringUtil.toDuration(System.currentTimeMillis() - time));
        return sb.toString();
    }
}
