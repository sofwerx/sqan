package org.sofwerx.sqan.manet.wifidirect;

import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.util.CommsLog;

import java.util.HashMap;
import java.util.Map;

class WiFiDirectNSD {
    private final static String SQAN_INSTANCE_TAG = "_sqan";
    private WiFiDirectDiscoveryListener listener;

    WiFiDirectNSD(WiFiDirectDiscoveryListener listener) {
        this.listener = listener;
    }

    private boolean isDiscoveryMode = false;
    void startDiscovery(WifiP2pManager manager, WifiP2pManager.Channel channel) {
        if (!isDiscoveryMode) {
            isDiscoveryMode = true;
            String callsign = Config.getThisDevice().getSafeCallsign();
            Map record = new HashMap();
            record.put("callsign", Config.getThisDevice().getCallsign());
            //record.put("available", "visible"); //TODO put in any side-channel alert traffic here
            WifiP2pDnsSdServiceInfo serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(callsign+SQAN_INSTANCE_TAG, "_presence._udp", record);
            manager.addLocalService(channel, serviceInfo,
                    new WifiP2pManager.ActionListener() {
                        @Override
                        public void onSuccess() {
                                Log.d(Config.TAG,"addLocalService.onSuccess()");
                        }

                        @Override
                        public void onFailure(int code) {
                            Log.d(Config.TAG,"addLocalService.onFailure("+Util.getFailureStatusString(code)+")");
                        }
                    });
            manager.setDnsSdResponseListeners(channel, servListener, txtListener);
            WifiP2pDnsSdServiceRequest serviceRequest = WifiP2pDnsSdServiceRequest.newInstance();
            manager.addServiceRequest(channel, serviceRequest,
                    new WifiP2pManager.ActionListener() {
                        @Override
                        public void onSuccess() {
                            Log.d(Config.TAG,"serviceRequest.onSuccess()");
                        }

                        @Override
                        public void onFailure(int code) {
                            Log.d(Config.TAG,"serviceRequest.onFailure("+Util.getFailureStatusString(code)+")");
                        }
                    });
            manager.discoverServices(channel,
                    new WifiP2pManager.ActionListener() {
                        @Override
                        public void onSuccess() {
                            Log.d(Config.TAG,"discoverServices.onSuccess()");
                        }

                        @Override
                        public void onFailure(int code) {
                            Log.d(Config.TAG,"discoverServices.onFailure("+Util.getFailureStatusString(code)+")");
                        }
                    });
            CommsLog.log(CommsLog.Entry.Category.STATUS,"Discovery mode started");
        }
    }

    void stopDiscovery(WifiP2pManager manager, WifiP2pManager.Channel channel, WifiP2pManager.ActionListener listener) {
        if (isDiscoveryMode) {
            isDiscoveryMode = false;
            if (listener == null)
                manager.clearServiceRequests(channel,
                        new WifiP2pManager.ActionListener() {
                            @Override
                            public void onSuccess() {
                                Log.d(Config.TAG,"Net Service Discovery clearServiceRequests.onSuccess()");
                            }

                            @Override
                            public void onFailure(int code) {
                                Log.d(Config.TAG,"Net Service Discovery clearServiceRequests.onFailure("+Util.getFailureStatusString(code)+")");
                            }
                        });
            else
                manager.clearServiceRequests(channel, listener);
            CommsLog.log(CommsLog.Entry.Category.STATUS,"Discovery mode stopped");
        }
    }

    private WifiP2pManager.DnsSdTxtRecordListener txtListener = new WifiP2pManager.DnsSdTxtRecordListener() {
        @Override
        /* Callback includes:
         * fullDomain: full domain name: e.g "printer._ipp._tcp.local."
         * record: TXT record dta as a map of key/value pairs.
         * device: The device running the advertised service.
         */

        public void onDnsSdTxtRecordAvailable(String fullDomain, Map record, WifiP2pDevice device) {
            if (fullDomain.contains(SQAN_INSTANCE_TAG)) {
                Log.d(Config.TAG, "SQAN: DnsSdTxtRecord available on " + fullDomain + " : " + record.toString() + ", device " + device.deviceName);
                if (listener != null)
                    listener.onDeviceDiscovered(device);
            } else
                Log.d(Config.TAG, "Other: DnsSdTxtRecord available on "+fullDomain+" : "+record.toString()+", device "+device.deviceName);
        }
    };

    private WifiP2pManager.DnsSdServiceResponseListener servListener = (instanceName, registrationType, resourceType) -> {
        //TODO
        Log.d(Config.TAG, "onBonjourServiceAvailable " + instanceName+": "+resourceType.deviceName);
    };
}
