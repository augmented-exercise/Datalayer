/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.wearable.datalayer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.MainThread;
import androidx.annotation.WorkerThread;

import com.android.volley.NetworkResponse;
import com.android.volley.ParseError;
import com.android.volley.Request;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.HttpHeaderParser;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.JsonRequest;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.DataClient;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.android.volley.RequestQueue;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Receives its own events using a listener API designed for foreground activities. Updates a data
 * item every second while it is open. Also allows user to take a photo and send that as an asset to
 * the paired wearable.
 */

public class MainActivity extends Activity
        implements DataClient.OnDataChangedListener,
                MessageClient.OnMessageReceivedListener,
                CapabilityClient.OnCapabilityChangedListener{

    private static final String TAG = "MainActivity";

    private static final int REQUEST_IMAGE_CAPTURE = 1;

    private static final String START_ACTIVITY_PATH = "/start-activity";
    private static final String COUNT_PATH = "/count";
    private static final String IMAGE_PATH = "/image";
    private static final String IMAGE_KEY = "photo";
    private static final String COUNT_KEY = "count";

    private boolean mCameraSupported = false;

    private ListView mDataItemList;
    private Button mSendPhotoBtn;
    private ImageView mThumbView;
    private Bitmap mImageBitmap;
    private View mStartActivityBtn;

    private DataItemAdapter mDataItemListAdapter;

    // Send DataItems.
    private ScheduledExecutorService mGeneratorExecutor;
    private ScheduledFuture<?> mDataItemGeneratorFuture;

    private List<List<Float>> DatamAccel;
    private List<List<Float>> DatamGyro;

    private String[] tempParseAccel;
    private String[] tempParseGyro;

    List <Float> tempData;
    File file;
    String FILENAME = "test_file";
    BufferedWriter BW;
    FileWriter fw;
    boolean isCollectingData = false;

    public static TextView collectionStatusText;



    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LOGD(TAG, "onCreate");

        mCameraSupported = getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA);
        setContentView(R.layout.main_activity);
        setupViews();

        // Stores DataItems received by the local broadcaster or from the paired watch.
        mDataItemListAdapter = new DataItemAdapter(this, android.R.layout.simple_list_item_1);
        mDataItemList.setAdapter(mDataItemListAdapter);

        mGeneratorExecutor = new ScheduledThreadPoolExecutor(1);
        isCollectingData = false;
        collectionStatusText = (TextView) findViewById(R.id.collection_status);
    }

    @Override
    public void onResume() {
        super.onResume();
        mDataItemGeneratorFuture =
                mGeneratorExecutor.scheduleWithFixedDelay(
                        new DataItemGenerator(), 1, 5, TimeUnit.SECONDS);

        //mStartActivityBtn.setEnabled(true);
        //mSendPhotoBtn.setEnabled(mCameraSupported);

        // Instantiates clients without member variables, as clients are inexpensive to create and
        // won't lose their listeners. (They are cached and shared between GoogleApi instances.)
        Wearable.getDataClient(this).addListener(this);
        Wearable.getMessageClient(this).addListener(this);
        Wearable.getCapabilityClient(this)
                .addListener(this, Uri.parse("wear://"), CapabilityClient.FILTER_REACHABLE);

        if (isCollectingData) {
            file = new File(getExternalFilesDir(null), FILENAME);

            try {
                fw = new FileWriter(file, true);
                BW = new BufferedWriter(fw);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        //Settings.System.putInt(getContentResolver(),Settings.System.SCREEN_OFF_TIMEOUT, -1);

    }

    @Override
    public void onPause() {
        super.onPause();
        mDataItemGeneratorFuture.cancel(true /* mayInterruptIfRunning */);

        Wearable.getDataClient(this).removeListener(this);
        Wearable.getMessageClient(this).removeListener(this);
        Wearable.getCapabilityClient(this).removeListener(this);
        if (isCollectingData) {
            try {
                BW.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_IMAGE_CAPTURE && resultCode == RESULT_OK) {
            Bundle extras = data.getExtras();
            mImageBitmap = (Bitmap) extras.get("data");
            mThumbView.setImageBitmap(mImageBitmap);
        }
    }

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        LOGD(TAG, "onDataChanged: " + dataEvents);

        for (DataEvent event : dataEvents) {
            if (event.getType() == DataEvent.TYPE_CHANGED) {
/*                mDataItemListAdapter.add(
                        new Event("DataItem Changed", event.getDataItem().toString()));*/
            } else if (event.getType() == DataEvent.TYPE_DELETED) {
                /*mDataItemListAdapter.add(
                        new Event("DataItem Deleted", event.getDataItem().toString()));*/
            }
        }
    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        LOGD(
                TAG,
                "onMessageReceived() A message from watch was received:"
                        + messageEvent.getRequestId()
                        + " "
                        + messageEvent.getPath());
        if (messageEvent.toString().contains("Open File")) {
            openFile();
            isCollectingData = true;
        } else if (messageEvent.toString().contains("Close File")) {
            closeFile();
            isCollectingData = false;
        }
        writeToFile(messageEvent);
    }

    public void writeToFile(MessageEvent messageEvent) {
        if (isCollectingData) {
            if (messageEvent.toString().contains("DataA")) {
                //mDataItemListAdapter.add(new Event("Message from watch", messageEvent.toString()));
                tempParseAccel = messageEvent.toString().split(",");

                Log.e("SENSOR TEST Accel", Long.parseLong(tempParseAccel[2]) + ", "+ Float.parseFloat(tempParseAccel[3]) + ", " + Float.parseFloat(tempParseAccel[4])+ ", " + Float.parseFloat(tempParseAccel[5]));
                try {
                    BW.write("a, " + tempParseAccel[2] + ", " + tempParseAccel[3] + ", " + tempParseAccel[4] + ", " + tempParseAccel[5] + "\n");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else if (messageEvent.toString().contains("DataG")) {
                tempParseGyro = messageEvent.toString().split(",");
                //Log.e("SENSOR TEST Gyro", "x: "+ tempParseGyro[2] + ", y: " + tempParseGyro[3] + ", z: " + tempParseGyro[4]);

                try {
                    BW.write("g, " + tempParseGyro[2] + ", " + tempParseGyro[3] + ", " + tempParseGyro[4] + ", " + tempParseGyro[5] + "\n");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    public void onCapabilityChanged(final CapabilityInfo capabilityInfo) {
        LOGD(TAG, "onCapabilityChanged: " + capabilityInfo);

        mDataItemListAdapter.add(new Event("onCapabilityChanged", capabilityInfo.toString()));
    }

    /** Sets up UI components and their callback handlers. */
    private void setupViews() {
        //mSendPhotoBtn = findViewById(R.id.sendPhoto);
       // mThumbView = findViewById(R.id.imageView);
        mDataItemList = findViewById(R.id.data_item_list);
        mStartActivityBtn = findViewById(R.id.start_wearable_activity);
    }

    private void openFile() {
        if (!isCollectingData){
            isCollectingData = true;
            FILENAME = new String();
            FILENAME = Calendar.getInstance().getTime().toString();
            Log.e("FILENAME TEST", FILENAME);
            file = new File(getExternalFilesDir(null), FILENAME);


            try {
                file.createNewFile();
                fw = new FileWriter(file, true);
                BW = new BufferedWriter(fw);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        collectionStatusText.setText("dataCollectionStarted");
        mDataItemListAdapter.add(new Event("Message from watch", "OPENED FILE"));
    }

    private void closeFile() {
        Log.e("FLASK SERVER", "here!");
        isCollectingData = false;
        collectionStatusText.setText("dataCollectionEnded");
        mDataItemListAdapter.add(new Event("Message from watch", "CLOSED FILE"));
        if (isCollectingData) {
            try {
                BW.close();
                fw.close();
            } catch (IOException e) {
                e.printStackTrace();

            }
        }
        try {
            Log.e("FLASK SERVER", "Attempting to send request");
            dataProcessRequest(getStringFromFile(file.getPath()));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void onDataStartClick(View view) {
       openFile();
    }

    public void onDataStopClick(View view) {
        closeFile();
/*            Timer timer = new Timer();
            timer.schedule(new TimerTask() {
                public void run() {
                    Log.e("Handler Task", "end handler");

                    //TextView collectionStatusText = (TextView) findViewById(R.id.collection_status);
                    //MainActivity.collectionStatusText.setText("dataCollectionStarted");

                }
            }, 5000);*/
    }


    public void onTakePhotoClick(View view) {
        dispatchTakePictureIntent();
    }

    public void onSendPhotoClick(View view) {
        if (null != mImageBitmap) {
            sendPhoto(toAsset(mImageBitmap));
        }
    }

    /** Sends an RPC to start a fullscreen Activity on the wearable. */
    public void onStartWearableActivityClick(View view) {
        LOGD(TAG, "Generating RPC");

        // Trigger an AsyncTask that will query for a list of connected nodes and send a
        // "start-activity" message to each connected node.
        new StartWearableActivityTask().execute();
    }

    @WorkerThread
    private void sendStartActivityMessage(String node) {

        Task<Integer> sendMessageTask =
                Wearable.getMessageClient(this).sendMessage(node, START_ACTIVITY_PATH, new byte[0]);

        try {
            // Block on a task and get the result synchronously (because this is on a background
            // thread).
            Integer result = Tasks.await(sendMessageTask);
            LOGD(TAG, "Message sent: " + result);

        } catch (ExecutionException exception) {
            Log.e(TAG, "Task failed: " + exception);

        } catch (InterruptedException exception) {
            Log.e(TAG, "Interrupt occurred: " + exception);
        }
    }

    public void dataProcessRequest(String input) {
        String url = "http://34.216.227.134/";

        RequestQueue queue = Volley.newRequestQueue(this);

        JSONObject in = new JSONObject();

        try {
            in.put("", input);
            in.put("exercise", "BP");
        } catch (JSONException e){
            e.printStackTrace();
        }

        JsonObjectRequest jsonObjectRequest = new JsonObjectRequest(Request.Method.POST, url, in, new Response.Listener<JSONObject>() {
            @Override
            public void onResponse(JSONObject response) {
                // Display the first 500 characters of the response string.
                try {
                    Log.e("FLASK SERVER", response.getString("Exercise"));
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                Log.e("FLASK SERVER", "error");
            }
        });

        queue.add(jsonObjectRequest);
    }

    public static String getStringFromFile (String filePath) throws Exception {
        File fl = new File(filePath);
        FileInputStream fin = new FileInputStream(fl);
        String ret = convertStreamToString(fin);
        //Make sure you close all streams.
        fin.close();
        Log.e("FLASK SERVER", ret);
        return ret;
    }

    public static String convertStreamToString(InputStream is) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line = null;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        reader.close();
        return sb.toString();
    }

    /**
     * Dispatches an {@link android.content.Intent} to take a photo. Result will be returned back in
     * onActivityResult().
     */
    private void dispatchTakePictureIntent() {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
            startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE);
        }
    }

    /**
     * Builds an {@link com.google.android.gms.wearable.Asset} from a bitmap. The image that we get
     * back from the camera in "data" is a thumbnail size. Typically, your image should not exceed
     * 320x320 and if you want to have zoom and parallax effect in your app, limit the size of your
     * image to 640x400. Resize your image before transferring to your wearable device.
     */
    private static Asset toAsset(Bitmap bitmap) {
        ByteArrayOutputStream byteStream = null;
        try {
            byteStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteStream);
            return Asset.createFromBytes(byteStream.toByteArray());
        } finally {
            if (null != byteStream) {
                try {
                    byteStream.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }

    /**
     * Sends the asset that was created from the photo we took by adding it to the Data Item store.
     */
    private void sendPhoto(Asset asset) {
        PutDataMapRequest dataMap = PutDataMapRequest.create(IMAGE_PATH);
        dataMap.getDataMap().putAsset(IMAGE_KEY, asset);
        dataMap.getDataMap().putLong("time", new Date().getTime());
        PutDataRequest request = dataMap.asPutDataRequest();
        request.setUrgent();

        Task<DataItem> dataItemTask = Wearable.getDataClient(this).putDataItem(request);

        dataItemTask.addOnSuccessListener(
                new OnSuccessListener<DataItem>() {
                    @Override
                    public void onSuccess(DataItem dataItem) {
                        LOGD(TAG, "Sending image was successful: " + dataItem);
                    }
                });
    }

    @WorkerThread
    private Collection<String> getNodes() {
        HashSet<String> results = new HashSet<>();

        Task<List<Node>> nodeListTask =
                Wearable.getNodeClient(getApplicationContext()).getConnectedNodes();

        try {
            // Block on a task and get the result synchronously (because this is on a background
            // thread).
            List<Node> nodes = Tasks.await(nodeListTask);

            for (Node node : nodes) {
                results.add(node.getId());
            }

        } catch (ExecutionException exception) {
            Log.e(TAG, "Task failed: " + exception);

        } catch (InterruptedException exception) {
            Log.e(TAG, "Interrupt occurred: " + exception);
        }

        return results;
    }

    /** As simple wrapper around Log.d */
    private static void LOGD(final String tag, String message) {
        if (Log.isLoggable(tag, Log.DEBUG)) {
            Log.d(tag, message);
        }
    }

    /** A View Adapter for presenting the Event objects in a list */
    private static class DataItemAdapter extends ArrayAdapter<Event> {

        private final Context mContext;

        public DataItemAdapter(Context context, int unusedResource) {
            super(context, unusedResource);
            mContext = context;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                holder = new ViewHolder();
                LayoutInflater inflater =
                        (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                convertView = inflater.inflate(android.R.layout.two_line_list_item, null);
                convertView.setTag(holder);
                holder.text1 = (TextView) convertView.findViewById(android.R.id.text1);
                holder.text2 = (TextView) convertView.findViewById(android.R.id.text2);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }
            Event event = getItem(position);
            holder.text1.setText(event.title);
            holder.text2.setText(event.text);
            return convertView;
        }

        private class ViewHolder {
            TextView text1;
            TextView text2;
        }
    }

    private class Event {

        String title;
        String text;

        public Event(String title, String text) {
            this.title = title;
            this.text = text;
        }
    }

    private class StartWearableActivityTask extends AsyncTask<Void, Void, Void> {

        @Override
        protected Void doInBackground(Void... args) {
            Collection<String> nodes = getNodes();
            for (String node : nodes) {
                sendStartActivityMessage(node);
            }
            return null;
        }
    }

    /** Generates a DataItem based on an incrementing count. */
    private class DataItemGenerator implements Runnable {

        private int count = 0;

        @Override
        public void run() {
            PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(COUNT_PATH);
            putDataMapRequest.getDataMap().putInt(COUNT_KEY, count++);

            PutDataRequest request = putDataMapRequest.asPutDataRequest();
            request.setUrgent();

            LOGD(TAG, "Generating DataItem: " + request);

            Task<DataItem> dataItemTask =
                    Wearable.getDataClient(getApplicationContext()).putDataItem(request);

            try {
                // Block on a task and get the result synchronously (because this is on a background
                // thread).
                DataItem dataItem = Tasks.await(dataItemTask);

                LOGD(TAG, "DataItem saved: " + dataItem);

            } catch (ExecutionException exception) {
                Log.e(TAG, "Task failed: " + exception);

            } catch (InterruptedException exception) {
                Log.e(TAG, "Interrupt occurred: " + exception);
            }
        }
    }
}
