package org.sofwerx.sqandr;

public class Config {
    public static enum SqandrPlatforms {
        android,
        rpi,
        linux
    }
    public static SqandrPlatforms PLATFORM = SqandrPlatforms.linux;

    public void init() {
        String platformProperty = System.getProperty("platform");
        if (platformProperty == null) {
            PLATFORM = SqandrPlatforms.linux;
        } else {

            switch (platformProperty) {
                case "android":
                case "Android":
                    PLATFORM = SqandrPlatforms.android;
                    break;
                case "rPi":
                case "rpi":
                case "pi":
                case "raspberry pi":
                case "Raspberry Pi":
                case "raspian":
                    PLATFORM = SqandrPlatforms.rpi;
                    break;
                default:
                    PLATFORM = SqandrPlatforms.linux;
            }
        }
    }

    public static boolean isAndroid() {
        return (PLATFORM == org.sofwerx.sqandr.Config.SqandrPlatforms.android);
    }

}
