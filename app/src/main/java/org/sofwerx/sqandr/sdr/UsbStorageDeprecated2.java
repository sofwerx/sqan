package org.sofwerx.sqandr.sdr;

import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqandr.util.StringUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

//import de.waldheinz.fs.util.FileDisk;

@Deprecated
public class UsbStorageDeprecated2 {
    /*private final static String TAG = Config.TAG+".store";
    private UsbMassStorageDevice device;
    private FileSystem fs;
    private UsbFile root;
    private FileDisk fileDisk;

    public UsbStorageDeprecated2(UsbMassStorageDevice device) {
        this.device = device;
    }

    public void init() throws SdrException {
        if (device == null)
            throw new SdrException("Unable to initiate UsbStorage as UsbMassStorageDevice is null");

        //try mounting the file disk
        String path = device.getUsbDevice().getDeviceName();
        File file = new File(path);
        try {
            fileDisk = new FileDisk(file,false);
        } catch (FileNotFoundException e) {
            Log.d(TAG,"Failed to mount the file disk");
            throw new SdrException("Failed to mount the file disk");
        }

        try {
            device.init();
            Log.d(TAG,"USB Storage initialized");
            prepFileSystem();
        } catch (IOException e) {
            Log.e(TAG,"Unable to initialize usbStorage: "+e.getMessage());
            throw new SdrException("Unable to initialize USB storage: "+e.getMessage());
        }
    }

    public void stop() {
        if (device != null) {
            Log.d(TAG,"Stopping USB Storage");
            device.close();
            device = null;
        }
    }

    public boolean isConnectedTo(UsbMassStorageDevice usbMassStorageDevice) {
        if (usbMassStorageDevice == null)
            return false;
        return usbMassStorageDevice == device;
    }

    private void prepFileSystem() {
        if (device == null)
            return;
        if (fs == null) {
            try {
                List<Partition> partitions = device.getPartitions();
                if ((partitions == null) || partitions.isEmpty()) {
                    Log.e(TAG,"Unable to set the file system: no partitions");
                    return;
                }
                Partition partition = partitions.get(0);
                fs = partition.getFileSystem();
            } catch (Exception e) {
                Log.e(TAG,"Unable to set the file system: "+e.getMessage());
                return;
            }
        }
        if (root == null) {
            try {
                root = fs.getRootDirectory();
            } catch (Exception e) {
                Log.e(TAG,"Unable to set the file system: "+e.getMessage());
                return;
            }
        }
    }

    public UsbFile[] getFiles() {
        prepFileSystem();
        if (root == null) {
            Log.e(TAG,"Unable to getFiles as root cannot be found");
            return null;
        }
        try {
            return root.listFiles();
        } catch (IOException e) {
            Log.e(TAG,"Unable to getFiles: "+e.getMessage());
        }
        return null;
    }

    public String getFileNames() {
        UsbFile[] files = getFiles();
        if ((files == null) || (files.length == 0))
            return "No files";
        StringBuilder sb = new StringBuilder();
        boolean first = true;

        for (UsbFile file:files) {
            if (first)
                first = false;
            else
                sb.append('\n');
            if (file.isDirectory())
                sb.append('[');
            sb.append(file.getName());
            if (file.isDirectory())
                sb.append(']');
            else {
                sb.append(' ');
                sb.append(StringUtils.toDataSize(file.getLength()));
            }
        }

        return sb.toString();
    }*/
}
