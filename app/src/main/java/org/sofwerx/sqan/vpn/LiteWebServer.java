package org.sofwerx.sqan.vpn;

import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.manet.common.SqAnDevice;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class LiteWebServer {
    private final static String TAG = "SqAN.WS";
    private WebServer server;
    public final static int PORT = 8080;
    public static final int MAX_BYTES = 524288;
    public final static String RPI_URL = "http://192.168.42.210:8181";

    public LiteWebServer() {
        server = new WebServer();
        try {
            server.start();
            Log.d(TAG,"Web server started");
        } catch(IOException ioe) {
            Log.w(TAG, "Web server could not start.");
        }
    }

    public void stop() {
        Log.d(TAG,"Web server shutting down");
        if (server != null)
            server.stop();
    }

    private class WebServer extends NanoHTTPD {
        public WebServer() {
            super(PORT);
        }

        public Response serve(IHTTPSession session) {
            Method method = session.getMethod();
            Log.d(TAG,"Method: "+method.name());
            SqAnDevice device = Config.getThisDevice();
            if (device == null)
                return null;

            Map<String, List<String>> decodedQueryParameters = decodeParameters(session.getQueryParameterString());
            if (decodedQueryParameters.containsKey(Config.PREFS_VIDEO_SRC_ADDR)) {
                Config.setVideoSrcAddr(decodedQueryParameters.get(Config.PREFS_VIDEO_SRC_ADDR).get(0));
            }

            StringBuilder sb = new StringBuilder();
            sb.append("<html><head><title>SqAN VPN Host</title></head><body>");
            sb.append("<h1>This site is hosted by SqAN on "+ device.getCallsign()+" at "+ device.getVpnIpv4AddressString()+":"+PORT+"</h1>");
            sb.append("<p><blockquote><b>URI</b> = ").append(String.valueOf(session.getUri())).append("<br />");
            sb.append("<b>Method</b> = ").append(String.valueOf(session.getMethod())).append("</blockquote></p>");
            sb.append("<h3>Headers</h3><p><blockquote>").append(toString(session.getHeaders())).append("</blockquote></p>");
            sb.append("<h3>Parms</h3><p><blockquote>").append(toString(session.getParms())).append("</blockquote></p>");
            sb.append("<h3>Parms (multi values?)</h3><p><blockquote>").append(toString(decodedQueryParameters)).append("</blockquote></p>");
            try {
                Map<String, String> files = new HashMap<String, String>();
                session.parseBody(files);
                sb.append("<h3>Files</h3><p><blockquote>").append(toString(files)).append("</blockquote></p>");
            } catch (Exception e) {
                e.printStackTrace();
            }
            sb.append("</body></html>");
            return newFixedLengthResponse(sb.toString());
        }


        public Response proxy(IHTTPSession session) {
            Method method = session.getMethod();
            Log.d(TAG, "Method: " + method.name());

            URL url = null;
            HttpURLConnection conn = null;
//            byte[] prox = new byte[MAX_BYTES];
            StringBuilder sb = new StringBuilder();
            Response prox = null;

            try {
                url = new URL(RPI_URL);

                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");

                conn.connect();
                Log.d(TAG, conn.getResponseCode() + " " + conn.getResponseMessage());

                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));

                String l;
                while ((l = in.readLine()) != null) {
                    sb.append(l);
                }
                in.close();

                prox = newFixedLengthResponse(sb.toString());

//                conn.setChunkedStreamingMode(Config.getMtuSize());
//                InputStream in = new BufferedInputStream(conn.getInputStream());
//                in.read(prox, 0, MAX_BYTES);

            } catch (IOException e) {
                Log.w(TAG, "Could not open connection to " + url.toString() + ": " + e.getMessage());
            } finally {
                if (null != conn) {
                    conn.disconnect();
                }
            }
            return prox;
        }


        private String toString(Map<String, ? extends Object> map) {
            if (map.size() == 0) {
                return "";
            }
            return unsortedList(map);
        }

        private String unsortedList(Map<String, ? extends Object> map) {
            StringBuilder sb = new StringBuilder();
            sb.append("<ul>");
            for (Map.Entry<String, ? extends Object> entry : map.entrySet()) {
                listItem(sb, entry);
            }
            sb.append("</ul>");
            return sb.toString();
        }
    }

    private void listItem(StringBuilder sb, Map.Entry<String, ? extends Object> entry) {
        sb.append("<li><code><b>").append(entry.getKey()).append("</b> = ").append(entry.getValue()).append("</code></li>");
    }
}
