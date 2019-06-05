package org.sofwerx.sqandr.sdr;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.annotation.NonNull;

import org.sofwerx.sqan.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.sofwerx.sqandr.util.StringUtils.toStringRepresentation;

public class UsbStorage {
    public final static int USB_FILE_ACCESS_PERMISSION = 1040;
    private final static String TAG = Config.TAG+".store";
    private static Uri root = null;
    private static Uri rx = null;
    private static Uri tx = null;
    private static Uri sqanDir = null;
    private static StorageVolume storageVolume = null;
    private static UsbStorage instance;
    private static Context context;

    private final static String SQANDR_DIR = "sqandr";
    private final static String TX_DIR = "tx";
    private final static String RX_DIR = "rx";

    public static void requestPermission(@NonNull Activity activity) {
        if (root != null)
            return; //permission already granted
        StorageManager sm = activity.getSystemService(StorageManager.class);
        List<StorageVolume> volumes = sm.getStorageVolumes();
        if ((volumes != null) && !volumes.isEmpty()) {
            String desc;
            for (StorageVolume volume:volumes) {
                desc = volume.getDescription(activity);
                if ((desc != null) && desc.contains("SDR")) {
                    Log.d(TAG,"SDR USB Drive found");
                    storageVolume = volume;
                }
            }
        }
        if (storageVolume == null) {
            Log.w(TAG,"Cannot mount as storage volume is null");
            return;
        }
        if (root != null) {
            Log.d(TAG,"Mount request for storage volume ignored - already mounted");
            return;
        }

        Intent intent = storageVolume.createAccessIntent(null);
        try {
            activity.startActivityForResult(intent, USB_FILE_ACCESS_PERMISSION);
        } catch (Exception e) {
            Log.e(TAG,"Unable to startActivityForResult: "+e.getMessage());
        }
    }

    public static void init(Context context, Uri path) {
        UsbStorage.context = context;
        root = path;
        if ((root != null) && (instance == null))
            instance = new UsbStorage();
        buildTxRx();
    }

    public static UsbStorage getInstance() { return instance; }

    /* Checks if external storage is available for read and write */
    public static boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state)) {
            return true;
        }
        return false;
    }

    /* Checks if external storage is available to at least read */
    public static boolean isExternalStorageReadable() {
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state) ||
                Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
            return true;
        }
        return false;
    }

    public static void stop() {
        storageVolume = null;
        root = null;
        context = null;
        //instance = null;
    }

    public boolean isMounted() {
        return (root != null);
    }


    public String getFileNames(Context context) {
        return getFileNames(context,false);
    }

    public String getFileNames(Context context, boolean indent) {
        ArrayList<String> files = listFiles(context);
        if ((files == null) || (files.size() == 0))
            return "No files available";

        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String file:files) {
            if (first)
                first = false;
            else
                sb.append('\n');
            if (indent)
                sb.append("  ");
            sb.append(file);
        }
        return sb.toString();
    }

    public ArrayList<String> listFiles(Context context) {
        if (root == null)
            return null;
        Uri uri = root;
        ContentResolver contentResolver = context.getContentResolver();
        //Uri docUri = DocumentsContract.buildDocumentUriUsingTree(uri,
        //        DocumentsContract.getTreeDocumentId(uri));
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri,
                DocumentsContract.getTreeDocumentId(uri));

        try {
            Log.d(TAG, DocumentsContract.getDocumentId(root));
            //Log.d(TAG, DocumentsContract.getDocumentId(childrenUri));
        } catch (Exception e) {
            Log.e(TAG,"Unable to get DocumentsContract: "+e.getMessage());
        }

        ArrayList<String> files = new ArrayList<>();
        Cursor childCursor = contentResolver.query(childrenUri, new String[]{
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null);

        try {
            while (childCursor.moveToNext()) {
                files.add(childCursor.getString(0));
            }
        } finally {
            if (childCursor != null) {
                try {
                    childCursor.close();
                } catch (Exception ignored) {
                }
            }
        }
        return files;
    }

    private static Uri findChild(Uri folder, String name) {
        if ((context == null) || (root == null) || (name == null)) {
            Log.w(TAG,"Cannot find child as context, folder or name are null - initialize UsbStorage first");
            return null;
        }

        ContentResolver cr = context.getContentResolver();

        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folder,
                DocumentsContract.getTreeDocumentId(folder));

        Cursor childCursor = cr.query(childrenUri, new String[]{
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID}, DocumentsContract.Document.COLUMN_DISPLAY_NAME + " = '" + name + "' ", null, null);

        String configID = null;

        try {
            boolean keepGoing = true;
            while (childCursor.moveToNext() && keepGoing) {
                //if (childCursor.getString(0).equalsIgnoreCase(name)) {
                    configID = childCursor.getString(1);
                    keepGoing = false;
                //}
            }
        } finally {
            if (childCursor != null) {
                try {
                    childCursor.close();
                } catch (Exception ignored) {
                }
            }
        }

        if (configID == null)
            return null;
        return DocumentsContract.buildDocumentUriUsingTree(folder, configID);
    }

    /*private static String read(Uri uri) {
        //TODO for testing
        ContentResolver cr = context.getContentResolver();
        StringBuilder sb = new StringBuilder();
        try {
            InputStream is = cr.openInputStream(uri);
            byte[] buf = new byte[1024];
            int len;
            while((len=is.read(buf))>0){
                byte[] bytes = Arrays.copyOf(buf, len);
                sb.append(new String(bytes));
            }
            is.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return sb.toString();
    }*/

    private static void buildTxRx() {
        if ((context == null) || (root == null)) {
            Log.w(TAG,"Cannot build the Tx and Rx folders as context or root are null - initialize UsbStorage first");
            return;
        }
        ContentResolver cr = context.getContentResolver();
        if (sqanDir == null) {
            sqanDir = findChild(root,SQANDR_DIR);
        }
        if (sqanDir == null) {
            Log.e(TAG,"ERROR - cannot build the SqANDR folder.");
            return;
        }
        tx = findChild(sqanDir,TX_DIR);
        rx = findChild(sqanDir,RX_DIR);
        if ((tx == null) || (rx == null))
            Log.e(TAG,"ERROR - cannot build the SqANDR folder.");
    }

    public static File readNext() {
        if ((context == null) || (root == null)) {
            Log.w(TAG,"Cannot read next file as context or root are null - initialize UsbStorage first");
            return null;
        }
        if (rx == null)
            buildTxRx();
        if (rx == null)
            return null;
        /*TODO ContentResolver cr = context.getContentResolver();
        try {
            cr.openInputStream(root);
            sdfsdf
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }*/
        return null;
    }

    /**
     * Writes the data to an output file that is appropriately named in the output directory
     * @param data
     * @param format true == this is raw data, format it into the required file structure
     */
    public void writeBurst(byte[] data, boolean format) {
        if (data == null) {
            Log.w(TAG,"Cannot write an empty data array; ignoring.");
            return;
        }
        byte[] out;
        if (format) {
            StringBuilder sb = new StringBuilder();
            for (int i=0;i<data.length;i++) {
                sb.append(toStringRepresentation(data[i]));
                sb.append('\n');
            }
            sb.append("a");
            out = sb.toString().getBytes(StandardCharsets.UTF_8);
        } else
            out = data;

        write(out,System.currentTimeMillis()+".txt");
    }

    public void write(byte[] data, String name) {
        if ((context == null) || (root == null)) {
            Log.w(TAG,"Cannot write "+name+" as context or root are null - initialize UsbStorage first");
            return;
        }
        if (data == null) {
            Log.w(TAG,"Cannot write an empty data array; ignoring.");
            return;
        }
        if (tx == null)
            buildTxRx();
        if (tx == null)
            return;

        //TODO
    }
}
