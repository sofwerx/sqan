package org.sofwerx.sqandr.util;

public interface SqANDRLoaderListener {
    void onSuccess();
    void onFailure(String message);
    void onProgressPercent(int percent);
}
