package org.sofwerx.sqan.manet.common;

import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.SavedTeammate;
import org.sofwerx.sqan.manet.bt.helper.Core;
import org.sofwerx.sqan.util.CommsLog;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Random;

/**
 * Helper class used to prioritize connections
 */
public class TeammateConnectionPlanner {

    /**
     * Reorders an array of devices to descending priority connection order for new WiFi connections
     * @param wifiDevices
     * @return
     */
    public static ArrayList<SqAnDevice> getDescendingPriorityWiFiConnections(ArrayList<SqAnDevice> wifiDevices) {
        if ((wifiDevices == null) || wifiDevices.isEmpty())
            return null;
        ArrayList<SqAnDevice> priorityDevices = new ArrayList<>();

        //put devices that arent directly connected and with one connection on top
        int i=0;
        SqAnDevice device;
        while (i<wifiDevices.size()) {
            device = wifiDevices.get(i);
            if ((device != null) && device.isActive()) {
                if (!device.isDirectBt() && !device.isDirectWiFi()) {
                    if ((device.getRelayConnections() == null) || (device.getRelayConnections().size() < 2)) {
                        priorityDevices.add(device);
                        wifiDevices.remove(i);
                        continue;
                    }
                }
                i++;
            } else
                wifiDevices.remove(i);
        }

        //then add the rest of the devices that arent directly connected
        i=0;
        while (i<wifiDevices.size()) {
            device = wifiDevices.get(i);
            if (!device.isDirectBt() && !device.isDirectWiFi()) {
                priorityDevices.add(device);
                wifiDevices.remove(i);
            } else
                i++;
        }

        //then add the rest of the devices that just have bluetooth connectivity
        i=0;
        while (i<wifiDevices.size()) {
            device = wifiDevices.get(i);
            if (!device.isDirectWiFi()) {
                priorityDevices.add(device);
                wifiDevices.remove(i);
            } else
                i++;
        }

        //then add the rest of the devices that don't have high performance wifi
        i=0;
        while (i<wifiDevices.size()) {
            device = wifiDevices.get(i);
            if (!device.isDirectWiFiHighPerformance()) {
                priorityDevices.add(device);
                wifiDevices.remove(i);
            } else
                i++;
        }

        return priorityDevices;
    }


    /**
     * Get an array of teammates in descending priority connection order
     * @return
     */
    public static ArrayList<SavedTeammate> getDescendingPriorityTeammates() {
        ArrayList<SavedTeammate> teammates = Config.getSavedTeammates();
        if ((teammates == null) || teammates.isEmpty())
            return null;
        ArrayList<SavedTeammate> prioritized = new ArrayList<>();

        //ignore any devices already connected via bluetooth
        for (SavedTeammate teammate:teammates) {
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
                SavedTeammate tmp = prioritized.get(a);
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
                    SavedTeammate tmp = prioritized.get(i);
                    prioritized.set(i,prioritized.get(i-1));
                    prioritized.set(i-1,tmp);
                    sorted = false;
                    break;
                }
                i++;
            }
        }

        StringWriter out = new StringWriter();
        if ((prioritized == null) || prioritized.isEmpty())
            out.append("NONE");
        else {
            boolean first = true;
            for (SavedTeammate priTeammate : prioritized) {
                if (first)
                    first = false;
                else
                    out.append(", ");
                if (priTeammate == null)
                    out.append("null device [this should not happen]");
                else
                    out.append(priTeammate.getLabel());
            }
        }

        CommsLog.log(CommsLog.Entry.Category.CONNECTION,"Prioritizing teammates for connections: "+out.toString());

        return prioritized;
    }
}
