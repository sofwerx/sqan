package org.sofwerx.sqan.ui;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.constraint.ConstraintLayout;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import org.sofwerx.sqan.R;
import org.sofwerx.sqan.manet.SqAnDevice;
import org.sofwerx.sqan.util.CommsLog;
import org.sofwerx.sqan.util.StringUtil;

import java.io.StringWriter;

public class DeviceSummary extends ConstraintLayout /*implements DeviceDisplayInterface*/ {
    private TextView callsign, description;
    //private ImageView iconActivity;
    private ImageView iconConnectivity;
    private ImageView iconPower;
    private ImageView iconLink;
    private ImageView iconLoc;
    private boolean unavailable = false;
    private boolean significant = false;

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
        description = view.findViewById(R.id.deviceDetails);
        //iconActivity = view.findViewById(R.id.deviceSensor);
        iconConnectivity = view.findViewById(R.id.deviceConnectivity);
        iconPower = view.findViewById(R.id.deviceBattery);
        iconLink = view.findViewById(R.id.deviceLink);
        iconLoc = view.findViewById(R.id.deviceLocation);
    }

    public void update(SqAnDevice device) {
        if (device != null) {
            if (device.getUUID() == null)
                callsign.setText(device.getUUID()+"(waiting SqAN ID)");
            else
                callsign.setText("SqAN ID: "+device.getUUID()+" ("+device.getNetworkId()+")");
            StringWriter descOut = new StringWriter();
            descOut.append("Rx: ");
            descOut.append(StringUtil.toDataSize(device.getDataTally()));
            long lastLatency = device.getLastLatency();
            if (lastLatency > 0l) {
                descOut.append("; latency ");
                descOut.append(Long.toString(lastLatency));
                descOut.append("ms (avg ");
                descOut.append(Long.toString(device.getAverageLatency()));
                descOut.append("ms)");
            }
            CommsLog.Entry lastEntry = device.getLastEntry();
            if (lastEntry != null) {
                descOut.append("\r\n");
                descOut.append(lastEntry.toString());
            }

            description.setText(descOut.toString());
            //updateBattery(device.getPower());
            //updateConnection(device.getPrimaryConnection());
            //updateSensorActivity(device);
            //updateLocation(device);
            updateLinkDisplay(device);
            //device.setDisplayInterface(this);
        } else {
            callsign.setText("No sensor");
            description.setVisibility(View.INVISIBLE);
            //iconActivity.setVisibility(View.INVISIBLE);
            iconPower.setVisibility(View.INVISIBLE);
            iconConnectivity.setVisibility(View.INVISIBLE);
            iconLoc.setVisibility(View.INVISIBLE);
        }
    }

    private void updateLocation(SqAnDevice device) {
        /*Loc loc = device.getLocation();
        if ((loc == null) || !loc.isValid())
            iconLoc.setVisibility(View.INVISIBLE);
        else
            iconLoc.setVisibility(unavailable?View.INVISIBLE:View.VISIBLE);*/
    }

    private void updateLinkDisplay(SqAnDevice device) {
        //device.setDisplayInterface(this);
        switch (device.getStatus()) {
            case ONLINE:
                iconLink.setVisibility(View.INVISIBLE);
                unavailable = false;
                break;

            case CONNECTED:
                iconLink.setImageResource(R.drawable.icon_link);
                iconLink.setVisibility(View.VISIBLE);
                unavailable = false;
                break;

            case OFFLINE:
                iconLink.setImageResource(R.drawable.icon_off);
                iconLink.setVisibility(View.VISIBLE);
                unavailable = true;
                break;

            case STALE:
                iconLink.setImageResource(R.drawable.icon_link_old);
                iconLink.setVisibility(View.VISIBLE);
                unavailable = false;
                break;

            default:
                iconLink.setImageResource(R.drawable.icon_link_broken);
                iconLink.setVisibility(View.VISIBLE);
                unavailable = true;
        }
        if (unavailable) {
            callsign.setTextColor(getContext().getResources().getColor(R.color.light_grey));
            description.setTextColor(getContext().getResources().getColor(R.color.light_grey));
        } else {
            callsign.setTextColor(getContext().getResources().getColor(R.color.white));
            description.setTextColor(getContext().getResources().getColor(significant ? R.color.yellow : R.color.white_hint_green));
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
