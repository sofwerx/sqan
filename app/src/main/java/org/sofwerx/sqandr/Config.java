package org.sofwerx.sqandr;

public class Config {
    public static enum SqandrPlatforms {
        android,
        rpi,
        linux
    }
    public final static SqandrPlatforms PLATFORM = SqandrPlatforms.linux;

    public static boolean isAndroid() {
        return (PLATFORM == org.sofwerx.sqandr.Config.SqandrPlatforms.android);
    }

}
