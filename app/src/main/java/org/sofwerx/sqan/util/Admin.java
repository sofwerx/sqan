package org.sofwerx.sqan.util;

public class Admin {
    private final static String[] CREDITS = {
            "Some geodesy tools derived from <a href=\"https://github.com/OpenSextant/geodesy\">OpenSextant, MITRE Corporation</a> licensed under <a href=\"http://www.apache.org/licenses/LICENSE-2.0\">Apache License, Version 2.0</a>",
    };

    public final static String getCredits() {
        StringBuffer out = new StringBuffer();
        boolean first = true;
        for (String credit:CREDITS) {
            if (first)
                first = false;
            else
                out.append("<br>");
            out.append("• ");
            out.append(credit);
        }
        return out.toString();
    }

    public final static String getLicenses() {
        StringBuffer out = new StringBuffer();
        boolean first = true;
        for (String license:LICENSES) {
            if (first)
                first = false;
            else
                out.append("<br><br>     ======================================<br><br>");
            out.append(license);
        }
        return out.toString();
    }

    public final static String[] LICENSES = {
            "Some code derived from:<br><b>OpenSextant</b><br>"+
                    "OpenSextant - Geospatial extraction and tagging <br>" +
                    "   Copyright 2013, MITRE Corporation, All rights reserved<br>" +
                    "               http://github.com/OpenSextant<br>" +
                    //"<br>                   _  _  _  _| _  _<br>" +
                    //"                  (_|(/_(_)(_)(/__\\ \\/<br>" +
                    //"                   _|               /             <br>" +
                    "          http://github.com/OpenSextant/geodesy<br>",

                    "<br>   Licensed under the Apache License, Version 2.0 (the \"License\");<br>" +
                    "   you may not use this file except in compliance with the License.<br>" +
                    "   You may obtain a copy of the License at<br>" +
                    "       http://www.apache.org/licenses/LICENSE-2.0<br>" +
                    "   Unless required by applicable law or agreed to in writing, software<br>" +
                    "   distributed under the License is distributed on an \"AS IS\" BASIS,<br>" +
                    "   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.<br>" +
                    "   See the License for the specific language governing permissions and<br>" +
                    "   limitations under the License.<br>",

                    "Wi-Fi®, the Wi-Fi logo, the Wi-Fi CERTIFIED logo, Wi-Fi Protected Access® (WPA), " +
                    "WiGig®, the Wi-Fi Protected Setup logo, Wi-Fi Direct®, Wi-Fi Alliance®, WMM®, " +
                    "Miracast®, Wi-Fi CERTIFIED Passpoint® , and Passpoint® are registered trademarks " +
                    "of Wi-Fi Alliance. Wi-Fi CERTIFIED™, Wi-Fi Protected Setup™, Wi-Fi Multimedia™, " +
                    "WPA2™, WPA3™, Wi-Fi CERTIFIED Miracast™, Wi-Fi ZONE™, the Wi-Fi ZONE logo, " +
                    "Wi-Fi Aware™, Wi-Fi CERTIFIED HaLow™, Wi-Fi HaLow™, Wi-Fi CERTIFIED WiGig™, " +
                    "Wi-Fi CERTIFIED Vantage™, Wi-Fi Vantage™, Wi-Fi CERTIFIED TimeSync™, Wi-Fi " +
                    "TimeSync™, Wi-Fi CERTIFIED Location™, Wi-Fi Location™, Wi-Fi CERTIFIED Home " +
                    "Design™, Wi-Fi Home Design™, Wi-Fi CERTIFIED Agile Multiband™, Wi-Fi Agile " +
                    "Multiband™, Wi-Fi CERTIFIED Optimized Connectivity™, Wi-Fi Optimized Connectivity™, " +
                    "Wi-Fi CERTIFIED EasyMesh™, Wi-Fi EasyMesh™, Wi-Fi CERTIFIED Enhanced Open™, Wi-Fi " +
                    "Enhanced Open™, Wi-Fi CERTIFIED Easy Connect™, Wi-Fi Easy Connect™, Wi-Fi CERTIFIED " +
                    "6™ and the Wi-Fi Alliance logo are trademarks of Wi-Fi Alliance." +
            "<br>     ======================================<br>",
    };
}
