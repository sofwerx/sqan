package org.sofwerx.sqan.manet.wifidirect;

import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.SavedTeammate;
import org.sofwerx.sqan.util.CommsLog;

import java.util.HashMap;
import java.util.Map;

class WiFiDirectNSD {
    private final static String TAG = Config.TAG+".WiFiNSD";
    private final static String SQAN_INSTANCE_TAG = "_sqan";
    private final static String FIELD_UUID = "uuid";
    private final static String FIELD_GROUP_SSID = "a";
    private final static String FIELD_GROUP_PASSWORD = "b";
    private WiFiDirectDiscoveryListener listener;

    WiFiDirectNSD(WiFiDirectDiscoveryListener listener) {
        this.listener = listener;
    }

    private boolean isDiscoveryMode = false;
    private boolean isAdvertisingMode = false;

    /**
     * Starts advertising mode
     * @param manager
     * @param channel
     * @param force restart advertising mode even if it is already running (attempting to address issue with advertising stopping after connections
     */

    void startAdvertising(WifiP2pManager manager, WifiP2pManager.Channel channel, boolean force) {
        if (!isAdvertisingMode || force) {
            Log.d(TAG,"startAdvertising called");
            isAdvertisingMode = true;
            String callsign = Config.getThisDevice().getSafeCallsign();
            HashMap<String,String> record = new HashMap();
            record.put(FIELD_UUID, Integer.toString(Config.getThisDevice().getUUID()));
            /*if (group != null) {
                record.put(FIELD_GROUP_SSID,group.getSsid());
                record.put(FIELD_GROUP_PASSWORD,group.getPassword());
            }*/
            WifiP2pDnsSdServiceInfo serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(callsign+SQAN_INSTANCE_TAG, "_presence._udp", record);
            manager.addLocalService(channel, serviceInfo,
                    new WifiP2pManager.ActionListener() {
                        @Override
                        public void onSuccess() {
                                Log.d(TAG,"Advertising addLocalService.onSuccess()");
                            if (listener != null)
                                listener.onAdvertisingStarted();
                        }

                        @Override
                        public void onFailure(int code) {
                            Log.d(TAG,"Advertising addLocalService.onFailure("+ Util.getFailureStatusString(code)+")");
                        }
                    });
            manager.setDnsSdResponseListeners(channel, servListener, txtListener);
            CommsLog.log(CommsLog.Entry.Category.STATUS,"Advertising mode started");
        }
    }

    void startDiscovery(WifiP2pManager manager, WifiP2pManager.Channel channel) {
        if (!isDiscoveryMode) {
            isDiscoveryMode = true;
            WifiP2pDnsSdServiceRequest serviceRequest = WifiP2pDnsSdServiceRequest.newInstance();
            manager.addServiceRequest(channel, serviceRequest,
                    new WifiP2pManager.ActionListener() {
                        @Override
                        public void onSuccess() {
                            Log.d(TAG,"serviceRequest.onSuccess()");
                            manager.discoverServices(channel,
                                    new WifiP2pManager.ActionListener() {
                                        @Override
                                        public void onSuccess() {
                                            CommsLog.log(CommsLog.Entry.Category.CONNECTION,"WiFi Direct Discovery started");
                                            Log.d(TAG,"discoverServices.onSuccess()");
                                            if (listener != null)
                                                listener.onDiscoveryStarted();
                                        }

                                        @Override
                                        public void onFailure(int code) {
                                            CommsLog.log(CommsLog.Entry.Category.CONNECTION,"WiFi Direct Discovery failed: "+Util.getFailureStatusString(code));
                                            Log.d(TAG,"discoverServices.onFailure("+Util.getFailureStatusString(code)+")");
                                        }
                                    });
                        }

                        @Override
                        public void onFailure(int code) {
                            CommsLog.log(CommsLog.Entry.Category.CONNECTION,"WiFi Direct Discovery failed: "+Util.getFailureStatusString(code));
                            Log.d(TAG,"serviceRequest.onFailure("+Util.getFailureStatusString(code)+")");
                        }
                    });
        }
    }

    void stopAdvertising(WifiP2pManager manager, WifiP2pManager.Channel channel, WifiP2pManager.ActionListener listener) {
        Log.d(TAG,"stopAdvertising called");
        if (isAdvertisingMode) {
            isAdvertisingMode = false;
            if (listener == null) {
                manager.clearLocalServices(channel, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        CommsLog.log(CommsLog.Entry.Category.CONNECTION,"WiFi Direct Advertising started");
                        Log.d(TAG,"clearLocalServices.onSuccess()");
                    }

                    @Override
                    public void onFailure(int code) {
                        CommsLog.log(CommsLog.Entry.Category.CONNECTION,"WiFi Direct Advertising failed: "+Util.getFailureStatusString(code));
                        Log.d(TAG,"clearLocalServices.onFailure("+Util.getFailureStatusString(code)+")");
                    }
                });
            } else
                manager.clearLocalServices(channel,listener);
            CommsLog.log(CommsLog.Entry.Category.STATUS,"WiFi Direct Advertising stopped");
        } else if (listener != null)
            listener.onSuccess();
    }

    void stopDiscovery(WifiP2pManager manager, WifiP2pManager.Channel channel, WifiP2pManager.ActionListener listener) {
        if (isDiscoveryMode) {
            isDiscoveryMode = false;
            manager.stopPeerDiscovery(channel,null);
            if (listener == null)
                manager.clearServiceRequests(channel,
                        new WifiP2pManager.ActionListener() {
                            @Override
                            public void onSuccess() {
                                CommsLog.log(CommsLog.Entry.Category.STATUS,"WiFi Direct Discovery stopped");
                                Log.d(TAG,"Net Service Discovery clearServiceRequests.onSuccess()");
                            }

                            @Override
                            public void onFailure(int code) {
                                Log.d(TAG,"Net Service Discovery clearServiceRequests.onFailure("+Util.getFailureStatusString(code)+")");
                            }
                        });
            else
                manager.clearServiceRequests(channel, listener);
        } else if (listener != null)
            listener.onSuccess();
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
                if ((record != null) && record.containsKey(FIELD_UUID)) {
                    try {
                        int uuid = Integer.parseInt((String)record.get(FIELD_UUID));
                        SavedTeammate teammate = Config.getTeammate(uuid);
                        if (teammate == null) {
                            Config.saveTeammate(uuid, device.deviceAddress, null,null);
                            CommsLog.log(CommsLog.Entry.Category.COMMS,"New teammate discovered");
                        } else
                            CommsLog.log(CommsLog.Entry.Category.COMMS, "Teammate " + teammate.getLabel() + "(" + device.deviceAddress + ") discovered via DNS-SD");
                        teammate.setWiFiDirectMac(device.deviceAddress);
                        /*if ((record.containsKey(FIELD_GROUP_SSID) && record.containsKey(FIELD_GROUP_PASSWORD))) {
                            try {
                                WiFiGroup group = new WiFiGroup((String) record.get(FIELD_GROUP_SSID), (String) record.get(FIELD_GROUP_PASSWORD));
                                CommsLog.log(CommsLog.Entry.Category.STATUS,"SqAN WiFi Group "+group.getSsid()+" found");
                                if (listener != null)
                                    listener.onGroupDiscovered(group);
                            } catch (Exception ignore) {
                            }
                        }*/
                    } catch (Exception ignore) {
                    }
                }
                Log.d(TAG, "SQAN: DnsSdTxtRecord available on "+fullDomain+" : "+record.toString() + ", device " + ((device.deviceName==null)?device.deviceAddress:device.deviceName));
                if (listener != null)
                    listener.onDeviceDiscovered(device);
            } else
                Log.d(TAG, "Other: DnsSdTxtRecord available on "+fullDomain+" : "+record.toString()+", device "+device.deviceName);
        }
    };

    private WifiP2pManager.DnsSdServiceResponseListener servListener = (instanceName, registrationType, device) -> {
        //ignoring for now since a DnsSdTxtRecord should also be delivered and will provide the device SqAN UUID needed to connect
        Log.d(TAG, "Service Available " + instanceName+": "+device.deviceName+", but no action taken in WifiP2pManager.DnsSdServiceResponseListener");
    };
}
