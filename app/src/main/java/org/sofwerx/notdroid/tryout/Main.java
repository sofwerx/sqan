package org.sofwerx.notdroid.tryout;

import org.sofwerx.notdroid.content.Context;
import org.sofwerx.notdroid.os.Handler;
import org.sofwerx.sqan.manet.sdr.SdrManet;

public class Main {
    public static void main(String[] argv) {
        Handler handler = new Handler();
        Context context = new Context();
        Listener listener = new Listener();

        SdrManet mesh = new SdrManet(handler, context, listener);

        return;
    }

}
