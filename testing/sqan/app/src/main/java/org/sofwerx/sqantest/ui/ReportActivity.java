package org.sofwerx.sqantest.ui;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.sofwerx.sqantest.R;
import org.sofwerx.sqantest.util.FileUtil;

import java.io.File;

public class ReportActivity extends AppCompatActivity {
    private TextView text;
    private File report;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_report);
        text = findViewById(R.id.reportText);
    }

    @Override
    public void onResume() {
        super.onResume();
        report = getMostRecentReport();
        String content = FileUtil.getText(report);
        if (content == null)
            content = "No report found";
        text.setText(content);
    }

    private File getMostRecentReport() {
        File dir = FileUtil.getReportDir(this);
        if ((dir == null) || !dir.exists())
            return null;
        File[] files = dir.listFiles();
        File newest = null;
        if (files != null) {
            for (File file:files) {
                if (newest == null)
                    newest = file;
                else {
                    if (file.lastModified() > newest.lastModified())
                        newest = file;
                }
            }
        }
        return newest;
    }
}
