package org.sofwerx.sqan.manet.common;

import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.manet.bt.helper.Core;

import java.util.ArrayList;
import java.util.Random;

/**
 * Helper class used to prioritize connections
 */
public class TeammateConnectionPlanner {

    /**
     * Get an array of teammates in descending priotiy connection order
     * @return
     */
    public static ArrayList<Config.SavedTeammate> getDescendingPriorityTeammates() {
        ArrayList<Config.SavedTeammate> teammates = Config.getSavedTeammates();
        if ((teammates == null) || teammates.isEmpty())
            return null;
        ArrayList<Config.SavedTeammate> prioritized = new ArrayList<>();

        //ignore any devices already connected via bluetooth
        for (Config.SavedTeammate teammate:teammates) {
            MacAddress mac = teammate.getBluetoothMac();
            if ((mac != null) && !Core.isMacConnected(mac))
                prioritized.add(teammate);
        }

        if (prioritized.isEmpty())
            return null;
        if (prioritized.size() == 1)
            return prioritized;
        Random random = new Random();
        int restructureTimes = random.nextInt(10);
        while (restructureTimes > 0) {
            int a = random.nextInt(prioritized.size());
            int b = random.nextInt(prioritized.size());
            if (a != b) {
                Config.SavedTeammate tmp = prioritized.get(a);
                prioritized.set(a,prioritized.get(b));
                prioritized.set(b,tmp);
            }
            restructureTimes--;
        }

        ArrayList<SqAnDevice> devices = SqAnDevice.getDevices();
        if ((devices == null) || devices.isEmpty())
            return prioritized;

        boolean sorted = false;
        int i;
        while (!sorted) {
            sorted = true;
            i=1;
            while (i < prioritized.size()) {
                int idI = prioritized.get(i).getSqAnAddress();
                int idI1 = prioritized.get(i-1).getSqAnAddress();
                int minHopsI1 = Integer.MAX_VALUE;
                int minHopsI = Integer.MAX_VALUE;
                for (SqAnDevice device:devices) {
                    int hopsI = device.getHopsToDevice(idI);
                    int hopsI1 = device.getHopsToDevice(idI1);
                    if (hopsI < minHopsI)
                        minHopsI = hopsI;
                    if (hopsI1 < minHopsI1)
                        minHopsI1 = hopsI1;
                }
                if (minHopsI1<minHopsI) {
                    Config.SavedTeammate tmp = prioritized.get(i);
                    prioritized.set(i,prioritized.get(i-1));
                    prioritized.set(i-1,tmp);
                    sorted = false;
                    break;
                }
                i++;
            }
        }

        Log.d(Config.TAG,prioritized.size()+" prioritized teammates, with #1 being "+prioritized.get(0).getSqAnAddress());

        return prioritized;
    }
}
