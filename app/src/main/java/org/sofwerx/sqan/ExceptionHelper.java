package org.sofwerx.sqan;

import android.content.Context;

import org.sofwerx.sqan.util.CommsLog;
import org.sofwerx.sqan.util.StringUtil;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;

public class ExceptionHelper {
    public static void set(Context context) {
        if (!(Thread.getDefaultUncaughtExceptionHandler() instanceof ExceptionHandler))
            Thread.setDefaultUncaughtExceptionHandler(new ExceptionHandler(context));
    }

    private static class ExceptionHandler implements Thread.UncaughtExceptionHandler {
        private Thread.UncaughtExceptionHandler defaultHandler;
        private Context context;

        public ExceptionHandler(Context context) {
            this.context = context;
            this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        }

        @Override
        public void uncaughtException(Thread thread, Throwable ex) {
            try {
                Writer result = new StringWriter();
                PrintWriter printWriter = new PrintWriter(result);
                ex.printStackTrace(printWriter);
                String stacktrace = result.toString();
                CommsLog.log(stacktrace);
                CommsLog.close();
                printWriter.close();
                result.close();
            } catch (Exception e) {
            } finally {
                this.defaultHandler.uncaughtException(thread, ex);
            }
        }
    }
}
