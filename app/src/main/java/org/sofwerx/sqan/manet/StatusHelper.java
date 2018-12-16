package org.sofwerx.sqan.manet;

import static org.sofwerx.sqan.manet.Status.*;

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

            //TODO other cases as added
        }
        return "Ubnknown";
    }
}
