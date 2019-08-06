package org.sofwerx.notdroid.tryout;

import org.sofwerx.notdroid.content.Context;
import org.sofwerx.notdroid.os.Handler;
import org.sofwerx.notdroid.os.HandlerThread;
import org.sofwerx.notdroid.tryout.Listener;
import org.sofwerx.sqan.SqAnServiceRPI;
import org.sofwerx.sqan.manet.common.issues.AbstractManetIssue;
import org.sofwerx.sqan.manet.sdr.SdrManet;
import org.sofwerx.sqandr.SqANDRService;

import java.util.ArrayList;
import java.util.Random;


// TODO: implement sqan.SqAnService functionality for rPi in SqAnServiceRPI
public class Main {


    public static void main(String[] argv) {
        Handler handler = new Handler();
        Context context = new Context();
        Listener listener = new Listener() {

        };
        SdrManet mesh = new SdrManet(handler, context, listener);

        SqAnServiceRPI sqAnService = new SqAnServiceRPI();

        HandlerThread manetThread = new HandlerThread("ManetOps") {

        };


        return;
    }

}
