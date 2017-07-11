package com.sensordroid;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.NotificationCompat;
import android.util.Log;

import com.sensordroid.Activities.ConfigurationActivity;
import com.sensordroid.Handlers.DispatchFileHandler;
import com.sensordroid.Handlers.DispatchTCPHandler;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class RemoteService extends Service {
    private static final String TAG = "RemoteService";
    public static final String PROVIDER_RESULT = "com.sensordroid.UPDATE_COUNT";

    //private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private LocalBroadcastManager broadcaster;

    // Update count variables
    private static boolean update = true;
    private static int count;

    // Wakelock
    private PowerManager.WakeLock wakeLock;

    // TCP variables
    public static String SERVER_IP;
    public static int SERVER_PORT;
    public static boolean tcp = true;
    private static Socket socket;
    private static OutputStream output;
    private static PrintWriter printWriter;

    // File variables
    public static boolean toFile = true;
    private static FileWriter fileOut;
    private static String filePath = "datasamples.txt";


    /*
        Implementation of the interface defined in MainServiceConnection.aidl
     */
    private final IMainServiceConnection.Stub binder = new IMainServiceConnection.Stub() {
        /*
            Receives string from a remote process and passes it a sender-thread
         */

        /**
         * Writes <tt>json</tt> to file/socket
         * @param json
         *              String to be written
         */
        @Override
        public void putJson(String json) {
            if (executor.isShutdown()) {
                return;
            }

            if (toFile) {
                executor.submit(new DispatchFileHandler(json, fileOut));
            } else {
                executor.submit(new DispatchTCPHandler(json, output, printWriter));
            }

            if(update) {
                count++;
                updateCount();
            }
        }
    };

    /**
        Updates TextField in MainActivity
            - The count is appended to make sure the count is correct even
              if the user change foreground activity
     */
    public void updateCount() {
        Intent intent = new Intent(PROVIDER_RESULT);
        intent.putExtra("COUNT", count);
        intent.setPackage(getPackageName());
        broadcaster.sendBroadcast(intent);
    }

    private String getDateTimeAsISOString(Date date) {
        TimeZone timeZone = TimeZone.getTimeZone("UTC");
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
        dateFormat.setTimeZone(timeZone);

        return dateFormat.format(date);
    }


    /*
        Return binder object to expose the implemented interface to remote processes.
     */
    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "Got connection from some wrapper.");
        return binder;
    }

    /*
        Initialize variables
     */
    public void onCreate(){
        super.onCreate();

        // Initialize variables
        count = 0;
        broadcaster = LocalBroadcastManager.getInstance(this);
        PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Collector");

        // Acquire wakelock
        wakeLock.acquire();

        // Set the Service to the foreground to decrease chance of getting killed
        toForeground();

        // Collect value from shared preferences and set up TCP connection if tcp is selected
        SharedPreferences sharedPreferences = getApplicationContext().getSharedPreferences(
                ConfigurationActivity.sharedKey, Context.MODE_PRIVATE);

        toFile      = sharedPreferences.getBoolean(ConfigurationActivity.usefileKey, false);
        filePath    = sharedPreferences.getString(ConfigurationActivity.fileNameKey, "datasamples.txt");
        SERVER_IP   = sharedPreferences.getString(ConfigurationActivity.ipKey, "vor.ifi.uio.no");
        SERVER_PORT = sharedPreferences.getInt(ConfigurationActivity.portKey, 12345);
        update      = sharedPreferences.getBoolean(ConfigurationActivity.updateCountKey, true);

        if (toFile){
            openFile(filePath);

            // Write session start metadata to file.
            {
                try {
                    JSONObject data = new JSONObject();
                    data.put("type", "session-start");
                    data.put("time", getDateTimeAsISOString(new Date()));
                    String str = data.toString();
                    executor.execute(new DispatchFileHandler(str, fileOut));

                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }

        }else {
            new ConnectTCPTask().execute();
        }
    }


    /**
     * Opens the file specified by the argument
     * @param filepath
     *                  file to open
     */
    public void openFile(String filepath){
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state)){
            try {
                File outfile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), filepath);

                fileOut = new FileWriter(outfile, true);
                Intent intent =
                        new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                intent.setData(Uri.fromFile(outfile));
                sendBroadcast(intent);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }


    /**
     * Connects to the specified IP/Port using TCP
     */
    class ConnectTCPTask extends AsyncTask<String, Void, String>{
        @Override
        protected String doInBackground(String... strings) {
            Log.d("TCP-setup", "connecting to "+ SERVER_IP);
            try {
                socket = new Socket(SERVER_IP, SERVER_PORT);
                output = socket.getOutputStream();
                printWriter = new PrintWriter(output);
            } catch (IOException e) {
                e.printStackTrace();
            }
            return null;
        }
    }

    /**
        Sets the current service to the foreground
     */
    public void toForeground() {
        final NotificationCompat.Builder builder = new NotificationCompat.Builder(this);
        builder.setSmallIcon(R.drawable.stat_notify_chat);
        builder.setContentTitle("Collector");
        builder.setTicker("Forwarding");
        builder.setContentText("Forwarding data");

        Intent i = new Intent(this, RemoteService.class);
        i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP |
                Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, 0);
        builder.setContentIntent(pi);

        final Notification note = builder.build();

        startForeground(android.os.Process.myPid(), note);
    }

    public void onDestroy(){
        Log.d("ON DESTROY", "Service destroyed");

        // Close tcp-connection
        if (socket != null && socket.isConnected()) {
            try {
                socket.close();
                socket = null;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        if (toFile && fileOut != null){
            try {
                // Create and write end session to file.
                {
                    JSONObject data = new JSONObject();
                    data.put("type", "session-end");
                    data.put("time", getDateTimeAsISOString(new Date()));
                    // Submit.
                    executor.execute(new DispatchFileHandler(data.toString(), fileOut));
                }
                executor.shutdown();
                boolean didShutDownInTime = executor.awaitTermination(2, TimeUnit.SECONDS);
                if (!didShutDownInTime) {
                    // did not finish in time!
                    Log.w(TAG, "Did not shut down in time!");
                    executor.shutdownNow();
                }

                fileOut.flush();
                fileOut.close();
            } catch (IOException e) {
                e.printStackTrace();
            } catch (JSONException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        // Remove wakelock
        wakeLock.release();

        // Move the service back from the foreground
        stopForeground(true);

        // Call super's destroy
        super.onDestroy();
    }
}
