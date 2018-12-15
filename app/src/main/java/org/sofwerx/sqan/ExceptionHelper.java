package org.sofwerx.sqan;

import android.content.Context;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;

public class ExceptionHelper {
    private static final String FILENAME = "stacktrace.txt";

    public static void set(Context context) {
        if (!(Thread.getDefaultUncaughtExceptionHandler() instanceof ExceptionHandler))
            Thread.setDefaultUncaughtExceptionHandler(new ExceptionHandler(context));
    }

    static void writeToStacktraceFile(Context context, String msg) {
        try {
            OutputStream os = context.openFileOutput(FILENAME, Context.MODE_PRIVATE);
            os.write(msg.getBytes());
            os.flush();
            os.close();
        } catch (IOException ignored) {
        }
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
            Writer result = new StringWriter();
            PrintWriter printWriter = new PrintWriter(result);
            ex.printStackTrace(printWriter);
            String stacktrace = result.toString();
            printWriter.close();
            ExceptionHelper.writeToStacktraceFile(context, stacktrace);
            this.defaultHandler.uncaughtException(thread, ex);
        }
    }
}
