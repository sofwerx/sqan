package org.sofwerx.sqantest.ui;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import androidx.appcompat.app.AppCompatActivity;

import android.content.ServiceConnection;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.osmdroid.api.IGeoPoint;
import org.osmdroid.api.IMapController;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.OverlayManager;
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay;
import org.sofwerx.sqan.ipc.BftBroadcast;
import org.sofwerx.sqan.ipc.BftDevice;
import org.sofwerx.sqan.ipc.ChatMessage;
import org.sofwerx.sqantest.IpcBroadcastTransceiver;
import org.sofwerx.sqantest.R;
import org.sofwerx.sqantest.SqAnTestService;
import org.sofwerx.sqantest.tests.AbstractTest;
import org.sofwerx.sqantest.tests.support.TestException;
import org.sofwerx.sqantest.tests.support.TestPacket;
import org.sofwerx.sqantest.tests.support.TestProgress;
import org.sofwerx.sqantest.util.PackageUtil;

import org.osmdroid.views.MapView;
import org.sofwerx.sqantest.util.PermissionsHelper;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements IpcBroadcastTransceiver.IpcListener {
    private final static long HELPER_UPDATE_RATE = 1000l * 5l;
    private final static String TAG = "SqAnTestSvc";
    private final static IGeoPoint CENTER_OF_CONUS = new GeoPoint(39.831341, -98.579933);
    private StringWriter convo = new StringWriter();
    private boolean first = true;
    private ScrollView scrollContainer;
    private TextView convoText, textSummaryText;
    private Button buttonTestType;
    private ImageView buttonTestStartStop;
    private ProgressBar progressTest;
    private MapView map;
    private SqAnTestService service;
    private boolean serviceBound = false;
    private boolean permissionsNagFired = false;
    private HashMap<Integer, Marker> markers = new HashMap<>();
    private boolean firstMoved = false;
    private Drawable iconDevice, iconThisDevice;
    private ArrayList<Polyline> polylines = new ArrayList<>();
    private ArrayList<BftDevice> devices;
    private long lastChatMessage = Long.MIN_VALUE;
    private Handler handler;

    /*public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_main, menu);
        return true;
    }

    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        switch (id) {
            case R.id.action_about:
                startActivity(new Intent(this, AboutActivity.class));
                return true;

            case R.id.action_settings:
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
        }

        return super.onOptionsItemSelected(item);
    }*/

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        View buttonSendMessage = findViewById(R.id.mainButtonSend);
        EditText editText = findViewById(R.id.mainText);
        scrollContainer = findViewById(R.id.mainScrollView);
        convoText = findViewById(R.id.mainConvo);
        iconDevice = getDrawable(R.drawable.map_icon_device);
        iconThisDevice = getDrawable(R.drawable.map_icon_this_device);
        textSummaryText = findViewById(R.id.mainTestSummary);
        buttonTestStartStop = findViewById(R.id.mainButtonStart);
        buttonTestType = findViewById(R.id.mainButtonTestName); //TODO
        progressTest = findViewById(R.id.mainProgressTest);
        map = findViewById(R.id.mainMap);
        View buttonAbout = findViewById(R.id.mainButtonAbout);
        buttonAbout.setOnClickListener(v -> startActivity(new Intent(MainActivity.this,AboutActivity.class)));
        setupMap();

        if (buttonSendMessage != null) {
            buttonSendMessage.setOnClickListener(view -> {
                String message = editText.getText().toString();
                if ((message != null) && (message.length() == 0))
                    message = null;
                if (message == null)
                    Toast.makeText(MainActivity.this,"No message to send",Toast.LENGTH_LONG).show();
                else {
                    ChatMessage chat = new ChatMessage(System.currentTimeMillis(),message);
                    IpcBroadcastTransceiver.broadcastChat(this, chat.toBytes());
                    editText.setText(null);
                    if (first)
                        first = false;
                    else
                        convo.append("\r\n");
                    convo.append("you: ");
                    convo.append(message);
                    convoText.setText(convo.toString());
                    scrollContainer.post(() -> scrollContainer.fullScroll(View.FOCUS_DOWN));
                }
            });
        }
        if (buttonTestStartStop != null) {
            buttonTestStartStop.setOnClickListener(v -> {
                if (service != null) {
                    AbstractTest test = service.getTest();
                    if (test != null) {
                        byte[] commandData = new byte[1];
                        if (test.isRunning()) {
                            test.stop();
                            commandData[0] = AbstractTest.COMMAND_STOP_TEST;
                            test.burst(new TestPacket(service.getDeviceId(),commandData));
                            startActivity(new Intent(MainActivity.this, ReportActivity.class));
                        } else {
                            test.start();
                            commandData[0] = test.getCommandType();
                            test.burst(new TestPacket(service.getDeviceId(),commandData));
                        }
                        if (service != null)
                            service.notifyOfTest(test);
                        updateTestDisplay();
                    }
                }
            });
        }

        if (!PackageUtil.doesSqAnExist(this)) {
            Toast.makeText(this,"SqAN is not installed, so it cannot be tested.",Toast.LENGTH_LONG).show();
            finish();
        }
        startService();
    }

    @Override
    public void onStop() {
        super.onStop();
        if (serviceBound && (service != null)) {
            try {
                unbindService(mConnection);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void updateTestDisplay() {
        AbstractTest test = null;
        if (service != null)
            test = service.getTest();
        if (test == null) {
            textSummaryText.setVisibility(View.INVISIBLE);
            buttonTestStartStop.setVisibility(View.INVISIBLE);
            progressTest.setVisibility(View.INVISIBLE);
            buttonTestType.setText("No Test Selected");
            return;
        }
        TestProgress progress = test.getProgress();
        if (progress == null)
            textSummaryText.setText("Problem starting test");
        else
            textSummaryText.setText(progress.getShortStatus(test.isRunning()));
        textSummaryText.setVisibility(View.VISIBLE);
        buttonTestStartStop.setImageResource(test.isRunning()?android.R.drawable.ic_media_pause:android.R.drawable.ic_media_play);
        buttonTestStartStop.setVisibility(View.VISIBLE);
        buttonTestType.setText(test.getName());
        progressTest.setVisibility(test.isRunning()?View.VISIBLE:View.INVISIBLE);
    }

    private void startService() {
        if (!serviceBound) {
            Log.d(TAG,"Starting SqAN Testing Service");
            startService(new Intent(this, SqAnTestService.class));
            Intent intent = new Intent(this, SqAnTestService.class);
            bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        map.onResume();
        if (!permissionsNagFired) {
            permissionsNagFired = true;
            PermissionsHelper.checkForPermissions(this);
        }
        if (service != null) {
            service.setUiIpcListener(this);
            updateMap(service.getLastBftBroadcast());
        }
        if (handler == null)
            handler = new Handler();
        handler.postDelayed(periodicHelper,HELPER_UPDATE_RATE);
    }

    @Override
    public void onPause() {
        if (service != null)
            service.setUiIpcListener(null);
        map.onPause();
        if (handler != null)
            handler.removeCallbacks(null);
        super.onPause();
    }

    private final Runnable periodicHelper = new Runnable() {
        @Override
        public void run() {
            updateTestDisplay();
            //TODO
            if (handler != null)
                handler.postDelayed(periodicHelper,HELPER_UPDATE_RATE);
        }
    };

    protected ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder iBinder) {
            Log.d(TAG,"SqAnTestService bound to this activity");
            SqAnTestService.SqAnTestBinder binder = (SqAnTestService.SqAnTestBinder) iBinder;
            service = binder.getService();
            serviceBound = true;
            service.setUiIpcListener(MainActivity.this);
            updateMap(service.getLastBftBroadcast());
            updateTestDisplay();
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            serviceBound = false;
        }
    };


    @Override
    public void onBackPressed() {
        if ((service != null) && ((service.getTest() == null) || !service.getTest().isRunning())) {
            if (serviceBound && (service != null))
                service.shutdown();
            MainActivity.this.finish();
        } else
            new AlertDialog.Builder(this)
                .setTitle("Quit SqAN Testing")
                .setMessage("Are you sure you want to quit the current SqAN Test session")
                .setNegativeButton("Quit", (dialog, which) -> {
                    if (serviceBound && (service != null))
                        service.shutdown();
                    MainActivity.this.finish();
                })
                .setPositiveButton("Run in Background", (arg0, arg1) -> MainActivity.this.finish()).create().show();
    }

    private void setupMap() {
        RotationGestureOverlay mRotationGestureOverlay = new RotationGestureOverlay(map);
        mRotationGestureOverlay.setEnabled(true);
        MapEventsOverlay mapEventsOverlay = new MapEventsOverlay(MainActivity.this, new MapEventsReceiver() {
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint p) { return false; }

            @Override
            public boolean longPressHelper(GeoPoint p) {
                /*if (target != null) {
                    Loc loc = new Loc();
                    loc.set(p.getLatitude(),p.getLongitude(),p.getAltitude());
                    target.setLoc(loc);
                    target.setName(editName.getText().toString());
                    MdxService.getInstance().burst(target);
                    update();
                    return false;
                } else */
                    return false;
            }
        });
        //map.getOverlays().add(0, mapEventsOverlay);
        //map.setBuiltInZoomControls(false);
        map.setMultiTouchControls(true); //needed for pinch zooms
        map.setTilesScaledToDpi(true); //scales tiles to the current screen's DPI, helps with readability of labels
        //map.setTileSource(TileSourceFactory.USGS_SAT);
        //map.setTileSource(TileSourceFactory.MAPNIK);
        IMapController mapController = map.getController();
        mapController.setZoom(3.5);
        mapController.setCenter(CENTER_OF_CONUS);
    }

    @Override
    public void onChatPacketReceived(final int src, final byte[] data) {
        runOnUiThread(() -> {
            if (data != null) {
                ChatMessage message = new ChatMessage(data);
                if (message.getTime() > lastChatMessage) { //cheap way to ignore duplicates
                    lastChatMessage = message.getTime();
                    if (first)
                        first = false;
                    else
                        convo.append("\r\n");
                    convo.append(getCallsign(src));
                    convo.append(": ");
                    convo.append(message.getMessage());
                    convoText.setText(convo.toString());
                    scrollContainer.post(() -> scrollContainer.fullScroll(View.FOCUS_DOWN));
                }
            }
        });
    }

    private String getCallsign(int src) {
        if (src > 0l) {
            if (service == null)
                return Integer.toString(src);
            BftDevice device = service.getDevice(src);
            if ((device == null) || (device.getCallsign()==null))
                return Integer.toString(src);
            return device.getCallsign();
        }
        return "unknown";
    }

    @Override
    public void onSaBroadcastReceived(final BftBroadcast broadcast) {
        runOnUiThread(() -> updateMap(broadcast));
    }

    @Override
    public void onTestPacketReceived(TestPacket packet) {
        //ignore
    }

    @Override
    public void onError(TestException error) {
        //ignore
    }

    @Override
    public void onOtherDataReceived(int origin, int size) {
        //ignore
    }

    @Override
    public void onTestCommand(byte command) {
        runOnUiThread(() -> updateTestDisplay());
    }

    private GeoPoint findPoint(int uuid) {
        if ((devices != null) && !devices.isEmpty()) {
            for (BftDevice device:devices) {
                if (device != null) {
                    if (device.getUUID() == uuid) {
                        if (Double.isNaN(device.getLatitude()) || Double.isNaN(device.getLongitude()))
                            return null;
                        return new GeoPoint(device.getLatitude(),device.getLongitude());
                    }
                }
            }
        }
        return null;
    }

    private void updateMap(BftBroadcast broadcast) {
        if (broadcast != null) {
            HashMap<Integer,Marker> current = new HashMap<>();
            devices = broadcast.getDevices();
            Marker marker;
            OverlayManager overlays = map.getOverlayManager();
            if (!polylines.isEmpty())
                overlays.removeAll(polylines);
            polylines.clear();
            if ((devices != null) && !devices.isEmpty()) {
                boolean isFirst = true;
                ArrayList<BftDevice.Link> links;
                GeoPoint otherLoc;
                Polyline polyline;
                List<GeoPoint> pts;

                //links
                for (BftDevice device:devices) {
                    if (device != null) {
                        if (!Double.isNaN(device.getLatitude()) && !Double.isNaN(device.getLongitude())) {
                            GeoPoint deviceLoc = new GeoPoint(device.getLatitude(),device.getLongitude());

                            if (isFirst) { //skip my direct links
                                isFirst = false;
                                continue;
                            }

                            links = device.getLinks();
                            if ((links != null) && !links.isEmpty()) {
                                for (BftDevice.Link link:links) {
                                    if (link != null) {
                                        otherLoc = findPoint(link.getUUID());
                                        if (otherLoc != null) {
                                            polyline = new Polyline();
                                            pts = new ArrayList<>();
                                            pts.add(deviceLoc);
                                            pts.add(otherLoc);
                                            polyline.setPoints(pts);
                                            if (link.isDirectBt()) {
                                                if (link.isDirectWiFi())
                                                    polyline.setColor(R.color.green);
                                                else
                                                    polyline.setColor(R.color.yellow);
                                            } else {
                                                if (link.isDirectWiFi())
                                                    polyline.setColor(R.color.blue);
                                                else
                                                    polyline.setColor(R.color.white_hint_green);
                                            }
                                            overlays.add(polyline);
                                            polylines.add(polyline);
                                        }
                                    }
                                }
                            }
                        }
                    }
                    isFirst = false;
                }

                //devices
                isFirst = true;
                for (BftDevice device:devices) {
                    if (device != null) {
                        if (Double.isNaN(device.getLatitude()) || Double.isNaN(device.getLongitude()))
                            markers.remove(device.getUUID());
                        else {
                            GeoPoint deviceLoc = new GeoPoint(device.getLatitude(),device.getLongitude());
                            marker = markers.get(device.getUUID());
                            if (marker == null) {
                                marker = new org.osmdroid.views.overlay.Marker(map);
                                marker.setPosition(deviceLoc);
                                marker.setTextLabelBackgroundColor(getColor(R.color.white));
                                marker.setTextLabelFontSize(28);
                                if (isFirst) {
                                    marker.setTextIcon("This Device");
                                    marker.setTextLabelForegroundColor(getColor(R.color.yellow));
                                } else {
                                    marker.setTextIcon(device.getCallsign());
                                    marker.setTextLabelForegroundColor(getColor(R.color.green));
                                }
                                //marker.setIcon(isFirst?iconThisDevice:iconDevice);
                                marker.setAnchor(0.5f,0.5f);
                                marker.setTitle(device.getCallsign());
                                map.getOverlays().add(marker);
                            } else {
                                marker.setPosition(deviceLoc);
                                markers.remove(device.getUUID());
                            }
                            if (!firstMoved) {
                                firstMoved = true;
                                IMapController mapController = map.getController();
                                mapController.animateTo(new GeoPoint(device.getLatitude(),device.getLongitude()),20d,1000l);
                            }
                            current.put(device.getUUID(), marker);
                        }
                    }
                    isFirst = false;
                }
            }

            //remove any marker not drawn
            Iterator it = markers.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry pair = (Map.Entry)it.next();
                marker = (Marker)pair.getValue();
                if (marker != null)
                    marker.remove(map);
            }

            markers = current;
        }
    }
}
