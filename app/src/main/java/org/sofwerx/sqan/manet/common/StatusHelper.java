package org.sofwerx.sqan.manet.common;

import static org.sofwerx.sqan.manet.common.Status.*;

/**
 * A helper class to for MANET Status
 */
public class StatusHelper {

    /**
     * Should this status be considered an active status
     * @param status
     * @return
     */
    public static boolean isActive(Status status) {
        switch (status) {
            case ADVERTISING_AND_DISCOVERING:
            case CONNECTED:
            case CHANGING_MEMBERSHIP:
                return true;
            //TODO put in other cases here

            default:
                return false;
        }
    }

    /**
     * Does the change in status warrant a notification to the user
     * @param lastNotifiedStatus last status that the user was notified about
     * @param status the new status
     * @return true == user should be notified
     */
    public static boolean isNotificationWarranted(Status lastNotifiedStatus, Status status) {
        if (status == lastNotifiedStatus)
            return (status == ERROR); //always notify about errors
        if (status == CHANGING_MEMBERSHIP)
            return true;
        if (lastNotifiedStatus == CHANGING_MEMBERSHIP)
            return true;
        if (isActive(status))
            return !isActive(lastNotifiedStatus);
        return true;
    }

    public static String getName(Status status) {
        switch (status) {
            case OFF:
                return "Off";

            case ERROR:
                return "Error";

            case ADVERTISING:
                return "Advertising";

            case CHANGING_MEMBERSHIP:
                return "Adjusting Mesh";

            case DISCOVERING:
                return "Discovering";

            case ADVERTISING_AND_DISCOVERING:
                return "Advertising and Discovering";

            case CONNECTED:
                return "Connected";

            //TODO other cases as added

            default:
                return "Unknown";
        }
    }
}
