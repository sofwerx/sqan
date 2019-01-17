package org.sofwerx.sqan.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.Toast;

import org.sofwerx.sqan.SqAnService;
import org.sofwerx.sqan.manet.common.issues.AbstractManetIssue;

import java.io.StringWriter;
import java.util.ArrayList;

/**
 * Shows the current hardware/system status and any issues impacting the MANET
 */
public class SystemStatusDialog {
    public static void show(Activity context) {
        if (SqAnService.hasSystemIssues()) {
            StringWriter out = new StringWriter();
            boolean first = true;

            ArrayList<AbstractManetIssue> issues = SqAnService.getIssues();
            if ((issues == null) || issues.isEmpty())
                out.append("Unknown issues");
            else {
                for (AbstractManetIssue issue:issues) {
                    if (first)
                        first = false;
                    else
                        out.append("\r\n");
                    out.append("• ");
                    if (issue.isBlocker())
                        out.append("[Critical] ");
                    out.append(issue.toString());
                }
            }

            new AlertDialog.Builder(context)
                    .setTitle("System is currently "+(SqAnService.hasBlockerSystemIssues()?"Down":"Degraded"))
                    .setMessage(out.toString())
                    .setPositiveButton("Dismiss", (dialog, which) -> {
                        //TODO ignore for now but possibly implement some aut-fix options
                    })
                    .show();
        } else
            Toast.makeText(context,"The system is not reporting any issues at this time.",Toast.LENGTH_LONG).show();

    }
}
