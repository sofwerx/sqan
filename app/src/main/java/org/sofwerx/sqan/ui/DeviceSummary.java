package org.sofwerx.sqan.ui;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.TextView;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.R;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.util.CommsLog;
import org.sofwerx.sqan.util.NetUtil;
import org.sofwerx.sqan.util.StringUtil;

import java.io.StringWriter;

public class DeviceSummary extends ConstraintLayout {
    private final static long TIME_TO_SHOW_FORWARDING = 1000l * 15l; //how long after a forward operation should the forwarding icon be visible
    private TextView callsign, uuid, ipv4, description;
    private TextView hops, links;
    //private ImageView iconConnectivity;
    private ImageView iconPower;
    private ImageView iconLink;
    private ImageView iconLoc;
    private ImageView iconType;
    private View markerBackhaul;
    private TextView markerWiFiAware;
    private ImageView iconPing,iconForward;
    private TextView textDistance,textDistanceAccuracy;
    private boolean unavailable = false;
    private boolean significant = false;
    private Animation pingAnimation;

    public DeviceSummary(@NonNull Context context) {
        super(context);
        init(context);
    }

    public DeviceSummary(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public DeviceSummary(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context);
    }

    private void init(Context context) {
        View view = inflate(context,R.layout.device_summary,this);
        callsign = view.findViewById(R.id.deviceCallsign);
        uuid = view.findViewById(R.id.deviceUUID);
        ipv4 = view.findViewById(R.id.deviceIP);
        description = view.findViewById(R.id.deviceDetails);
        //iconConnectivity = view.findViewById(R.id.deviceConnectivity);
        iconPower = view.findViewById(R.id.deviceBattery);
        iconLink = view.findViewById(R.id.deviceLink);
        iconLoc = view.findViewById(R.id.deviceLocation);
        iconType = view.findViewById(R.id.deviceTypeIcon);
        markerBackhaul = view.findViewById(R.id.deviceBackhaul);
        markerWiFiAware = view.findViewById(R.id.deviceWiFiAware);
        textDistance = view.findViewById(R.id.deviceDistance);
        textDistanceAccuracy = view.findViewById(R.id.deviceDistanceAccuracy);
        iconPing = view.findViewById(R.id.devicePing);
        iconForward = view.findViewById(R.id.deviceForward);
        hops = view.findViewById(R.id.deviceHops);
        links = view.findViewById(R.id.deviceConnections);
        pingAnimation = AnimationUtils.loadAnimation(context.getApplicationContext(), R.anim.ping);
    }

    private void showPing() {
        iconPing.setAlpha(1f);
        iconPing.startAnimation(pingAnimation);
    }

    public void update(SqAnDevice device) {
        if (device != null) {
            device.setUiSummary(this);
            if (device.isUuidKnown()) {
                uuid.setText("SqAN UUID: " + device.getUUID());
                ipv4.setText("SqAN IP: " + device.getVpnIpv4AddressString());
            } else {
                uuid.setText(device.getLabel());
                ipv4.setText("Unknown SqAN IP");
            }
            callsign.setText(device.getCallsign());
            StringWriter descOut = new StringWriter();
            descOut.append("Rx: ");
            if (device.isDataTallyGuiNeedUpdate()) {
                device.markDataTallyDisplayed();
                if (device.isActive())
                    showPing();
            }
            descOut.append(StringUtil.toDataSize(device.getDataTally()));
            long elapsedTime = (System.currentTimeMillis() - device.getFirstConnection())/1000l;
            if (elapsedTime > 60l) {
                descOut.append(" (");
                descOut.append(StringUtil.getDataRate(device.getDataTally(),elapsedTime));
                descOut.append(')');
            }
            long lastLatency = device.getLastLatency();
            if (lastLatency > 0l) {
                descOut.append("; latency ");
                descOut.append(Long.toString(lastLatency));
                descOut.append("ms (avg ");
                descOut.append(Long.toString(device.getAverageLatency()));
                descOut.append("ms)");
            }
            if (device.getPacketsDropped() > 0) {
                descOut.append("; ");
                descOut.append(Integer.toString(device.getPacketsDropped()));
                descOut.append((device.getPacketsDropped()==1)?" pkt drop":" pkts drop");
            }
            CommsLog.Entry lastEntry = device.getLastEntry();
            if (lastEntry != null) {
                descOut.append("\r\n");
                descOut.append(lastEntry.toString());
            }

            description.setText(descOut.toString());
            updateLinkDisplay(device);
            if (iconType != null)
                iconType.setVisibility(VISIBLE);
            if (device.getStatus() == SqAnDevice.Status.CONNECTED) {
                int numHops = device.getHopsAway();
                if (numHops < 0)
                    hops.setVisibility(View.INVISIBLE);
                else {
                    hops.setVisibility(View.VISIBLE);
                    if (numHops == 0)
                        hops.setText("Direct");
                    else
                        hops.setText(numHops + " hop" + ((numHops > 1) ? "s" : ""));
                }
                int numLinks = device.getActiveRelays();
                if (numLinks < 1)
                    links.setVisibility(View.INVISIBLE);
                else {
                    links.setVisibility(View.VISIBLE);
                    links.setText(numLinks + " connection" + ((numLinks > 1) ? "s" : ""));
                }
            } else {
                hops.setVisibility(View.INVISIBLE);
                links.setVisibility(View.INVISIBLE);
            }
        } else {
            Log.e(Config.TAG,"DeviceSummary has been assigned a null device - this should never happen");
            callsign.setText("No sensor");
            description.setVisibility(View.INVISIBLE);
            uuid.setVisibility(View.INVISIBLE);
            ipv4.setVisibility(View.INVISIBLE);
            iconPower.setVisibility(View.INVISIBLE);
            //iconConnectivity.setVisibility(View.INVISIBLE);
            markerBackhaul.setVisibility(View.INVISIBLE);
            hops.setVisibility(View.INVISIBLE);
            links.setVisibility(View.INVISIBLE);
            iconForward.setVisibility(View.INVISIBLE);
            if (iconType != null)
                iconType.setVisibility(INVISIBLE);
        }
    }

    private void updateLinkDisplay(SqAnDevice device) {
        switch (device.getStatus()) {
            case ONLINE:
                iconLink.setVisibility(View.INVISIBLE);
                unavailable = false;
                setBackgroundResource(R.drawable.bg_item_2_yellow);
                break;

            case CONNECTED:
                iconLink.setImageResource(R.drawable.icon_link);
                iconLink.setVisibility(View.VISIBLE);
                unavailable = false;
                setBackgroundResource(R.drawable.bg_item_2);
                break;

            case OFFLINE:
                iconLink.setImageResource(R.drawable.icon_off);
                iconLink.setVisibility(View.VISIBLE);
                setBackgroundResource(R.drawable.bg_item_2_grey);
                unavailable = true;
                break;

            case STALE:
                iconLink.setImageResource(R.drawable.icon_link_old);
                iconLink.setVisibility(View.VISIBLE);
                setBackgroundResource(R.drawable.bg_item_2_grey);
                unavailable = true;
                break;

            default:
                iconLink.setImageResource(R.drawable.icon_link_broken);
                iconLink.setVisibility(View.VISIBLE);
                setBackgroundResource(R.drawable.bg_item_2_yellow);
                unavailable = true;
        }
        if (unavailable) {
            callsign.setTextColor(getContext().getResources().getColor(R.color.light_grey));
            description.setTextColor(getContext().getResources().getColor(R.color.light_grey));
            uuid.setTextColor(getContext().getResources().getColor(R.color.light_grey));
            ipv4.setTextColor(getContext().getResources().getColor(R.color.light_grey));
            if (iconType != null)
                iconType.setColorFilter(getResources().getColor(R.color.light_grey));
            if (iconLoc != null)
                iconLoc.setVisibility(View.INVISIBLE);
            if (textDistance != null)
                textDistance.setVisibility(View.INVISIBLE);
            if (textDistanceAccuracy != null)
                textDistanceAccuracy.setVisibility(View.INVISIBLE);
            hops.setVisibility(View.INVISIBLE);
            links.setVisibility(View.INVISIBLE);
            iconForward.setVisibility(View.INVISIBLE);
            markerBackhaul.setVisibility(View.INVISIBLE);
            markerWiFiAware.setVisibility(View.INVISIBLE);
        } else {
            callsign.setTextColor(getContext().getResources().getColor(R.color.yellow));
            uuid.setTextColor(getContext().getResources().getColor(R.color.white));
            ipv4.setTextColor(getContext().getResources().getColor(R.color.white));
            description.setTextColor(getContext().getResources().getColor(significant ? R.color.yellow : R.color.white_hint_green));
            markerBackhaul.setVisibility(device.isBackhaulConnection()?View.VISIBLE:View.INVISIBLE);
            if ((device.getAwareMac()!=null)&&device.getAwareMac().isValid()) {
                if (device.isDirectWiFiHighPerformance())
                    markerWiFiAware.setTextColor(getResources().getColor(R.color.green));
                else
                    markerWiFiAware.setTextColor(getResources().getColor(R.color.yellow));
                markerWiFiAware.setVisibility(View.VISIBLE);
            } else
                markerWiFiAware.setVisibility(View.INVISIBLE);
            if (iconType != null)
                iconType.setColorFilter(getResources().getColor(R.color.white_hint_green));
            if (iconLoc != null) {
                if (device.isLocationKnown()) {
                    iconLoc.setVisibility(View.VISIBLE);
                    if (device.isLocationCurrent())
                        iconLoc.setColorFilter(getResources().getColor(R.color.green));
                    else
                        iconLoc.setColorFilter(getResources().getColor(R.color.light_grey));
                    if (textDistance != null) {
                        String distanceString = device.getDistanceText(Config.getThisDevice());
                        if (distanceString == null)
                            textDistance.setVisibility(View.INVISIBLE);
                        else {
                            textDistance.setVisibility(View.VISIBLE);
                            textDistance.setText(distanceString);
                        }
                        if (textDistanceAccuracy != null) {
                            String accuracyText = device.getAggregateAccuracy(Config.getThisDevice());
                            textDistanceAccuracy.setText(accuracyText);
                            textDistanceAccuracy.setVisibility(View.VISIBLE);
                        }
                    }
                } else {
                    iconLoc.setVisibility(View.INVISIBLE);
                    if (textDistance != null)
                        textDistance.setVisibility(View.INVISIBLE);
                    if (textDistanceAccuracy != null)
                        textDistanceAccuracy.setVisibility(View.INVISIBLE);
                }
            }
            if (System.currentTimeMillis() > device.getLastForward() + TIME_TO_SHOW_FORWARDING)
                iconForward.setVisibility(View.INVISIBLE);
            else
                iconForward.setVisibility(View.VISIBLE);
        }
    }

    /*private void updateConnection(Connection connection) {
        if (connection == null)
            iconConnectivity.setVisibility(View.INVISIBLE);
        else {
            int percent = connection.getSignalStrengthAsPercent();
            int level;
            if (percent <=0)
                level = 0;
            else {
                if (percent < 75) {
                    if (percent < 50) {
                        if (percent < 25)
                            level = 1;
                        else
                            level = 2;
                    } else
                        level = 3;
                } else
                    level = 4;
            }
            boolean isWifi = (connection.getType() == Connection.Type.WIFI_ADHOC) || (connection.getType() == Connection.Type.WIFI_TO_SERVER);
            switch (level) {
                case 1:
                    if (isWifi)
                        iconConnectivity.setImageResource(unavailable?R.drawable.icon_wifi_1_old:R.drawable.icon_wifi_1);
                    else
                        iconConnectivity.setImageResource(unavailable?R.drawable.icon_signal_1_old:R.drawable.icon_signal_1);
                    break;

                case 2:
                    if (isWifi)
                        iconConnectivity.setImageResource(unavailable?R.drawable.icon_wifi_2_old:R.drawable.icon_wifi_2);
                    else
                        iconConnectivity.setImageResource(unavailable?R.drawable.icon_signal_2_old:R.drawable.icon_signal_2);
                    break;

                case 3:
                    if (isWifi)
                        iconConnectivity.setImageResource(unavailable?R.drawable.icon_wifi_3_old:R.drawable.icon_wifi_3);
                    else
                        iconConnectivity.setImageResource(unavailable?R.drawable.icon_signal_3_old:R.drawable.icon_signal_3);
                    break;

                case 4:
                    if (isWifi)
                        iconConnectivity.setImageResource(unavailable?R.drawable.icon_wifi_4_old:R.drawable.icon_wifi_4);
                    else
                        iconConnectivity.setImageResource(unavailable?R.drawable.icon_signal_4_old:R.drawable.icon_signal_4);
                    break;

                default:
                    if (isWifi)
                        iconConnectivity.setImageResource(unavailable?R.drawable.icon_wifi_off_old:R.drawable.icon_wifi_off);
                    else
                        iconConnectivity.setImageResource(unavailable?R.drawable.icon_signal_off_old:R.drawable.icon_signal_off);
                    break;
            }
            iconConnectivity.setVisibility(View.VISIBLE);
        }
    }*/

    /*private void updateBattery(Power power) {
        if (power == null)
            iconPower.setVisibility(View.INVISIBLE);
        else {
            if (power.isDegraded()) {
                if (unavailable)
                    iconPower.setImageResource(R.drawable.icon_battery_alert_old);
                else
                    iconPower.setImageResource(R.drawable.icon_battery_alert);
                iconPower.setVisibility(View.VISIBLE);
            } else {
                int value = power.getBatteryPercent();
                if ((value < 0) || (value > 100))
                    iconPower.setVisibility(View.INVISIBLE);
                else {
                    boolean ac = power.isPluggedIn();
                    if (value < 95) {
                        if (value < 85) {
                            if (value < 70) {
                                if (value < 55) {
                                    if (value < 40) {
                                        if (value < 20) {
                                            if (unavailable)
                                                iconPower.setImageResource(R.drawable.icon_battery_20_old);
                                            else
                                                iconPower.setImageResource(ac?R.drawable.icon_battery_20_charging:R.drawable.icon_battery_20);
                                        } else {
                                            if (unavailable)
                                                iconPower.setImageResource(R.drawable.icon_battery_30_old);
                                            else
                                                iconPower.setImageResource(ac?R.drawable.icon_battery_30_charging : R.drawable.icon_battery_30);
                                        }
                                    } else {
                                        if (unavailable)
                                            iconPower.setImageResource(R.drawable.icon_battery_50_old);
                                        else
                                            iconPower.setImageResource(ac?R.drawable.icon_battery_50_charging : R.drawable.icon_battery_50);
                                    }
                                } else {
                                    if (unavailable)
                                        iconPower.setImageResource(R.drawable.icon_battery_60_old);
                                    else
                                        iconPower.setImageResource(ac?R.drawable.icon_battery_60_charging : R.drawable.icon_battery_60);
                                }
                            } else {
                                if (unavailable)
                                    iconPower.setImageResource(R.drawable.icon_battery_80_old);
                                else
                                    iconPower.setImageResource(ac?R.drawable.icon_battery_80_charging : R.drawable.icon_battery_80);
                            }
                        } else {
                            if (unavailable)
                                iconPower.setImageResource(R.drawable.icon_battery_90_old);
                            else
                                iconPower.setImageResource(ac?R.drawable.icon_battery_90_charging : R.drawable.icon_battery_90);
                        }
                    } else {
                        if (unavailable)
                            iconPower.setImageResource(R.drawable.icon_battery_full_old);
                        else
                            iconPower.setImageResource(ac?R.drawable.icon_battery_full_charging : R.drawable.icon_battery_full);
                    }
                    iconPower.setVisibility(View.VISIBLE);
                }
            }
        }
    }*/

    /*@Override
    public void onUpdate(SqAnDevice device) {
        update(device);
    }*/
}
