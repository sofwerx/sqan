package org.sofwerx.sqan.manet.wifidirect;

@Deprecated
public class WiFiGroup {
    private String ssid;
    private String password;

    public WiFiGroup(String ssid, String password) {
        this.ssid = ssid;
        this.password = password;
    }

    public String getSsid() {
        return ssid;
    }

    public void setSsid(String ssid) {
        this.ssid = ssid;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
