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
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.RequiresApi;
import androidx.annotation.WorkerThread;

import com.android.volley.NetworkResponse;
import com.android.volley.NoConnectionError;
import com.android.volley.Request;
import com.android.volley.Response;
import com.android.volley.TimeoutError;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonObjectRequest;
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
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private static final String ROOT_URL = "http://34.216.227.134";


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

                //Log.e("SENSOR TEST Accel", Long.parseLong(tempParseAccel[2]) + ", "+ Float.parseFloat(tempParseAccel[3]) + ", " + Float.parseFloat(tempParseAccel[4])+ ", " + Float.parseFloat(tempParseAccel[5]));
                try {
                    if (tempParseAccel[1] != null && tempParseAccel[2] != null && tempParseAccel[3] != null && tempParseAccel[4] != null && tempParseAccel[5] != null) {
                        BW.write("a, " + tempParseAccel[2] + ", " + tempParseAccel[3] + ", " + tempParseAccel[4] + ", " + tempParseAccel[5] + "\n");
                    } else {
                        Log.e("Error check", "Bad Data");
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else if (messageEvent.toString().contains("DataG")) {
                tempParseGyro = messageEvent.toString().split(",");
                //Log.e("SENSOR TEST Gyro", "x: "+ tempParseGyro[3] + ", y: " + tempParseGyro[4] + ", z: " + tempParseGyro[5]);

                try {
                    if (tempParseAccel[1] != null && tempParseAccel[2] != null && tempParseAccel[3] != null && tempParseAccel[4] != null && tempParseAccel[5] != null) {
                        BW.write("g, " + tempParseGyro[2] + ", " + tempParseGyro[3] + ", " + tempParseGyro[4] + ", " + tempParseGyro[5] + "\n");
                    } else {
                        Log.e("Error check", "Bad Data");
                    }
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
        Log.e("FLASK SERVER", "starting to close file");
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
        Log.e("FLASK SERVER", "Preparing to send request");
        sendRequest();
    }

    public void onDataStartClick(View view) {
       openFile();
    }

    public void onDataStopClick(View view) {
        Log.e("FLASK SERVER", "entered onDataStopClick()");
        closeFile();
        Log.e("FLASK SERVER", "Preparing to send request");
        sendRequest();

/*            Timer timer = new Timer();
            timer.schedule(new TimerTask() {
                public void run() {
                    Log.e("Handler Task", "end handler");

                    //TextView collectionStatusText = (TextView) findViewById(R.id.collection_status);
                    //MainActivity.collectionStatusText.setText("dataCollectionStarted");

                }
            }, 5000);*/
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

    private void sendRequest() {
        // loading or check internet connection or something...
        // ... then
        String url = ROOT_URL;

        VolleyMultipartRequest multipartRequest = new VolleyMultipartRequest(Request.Method.POST, url, new Response.Listener<NetworkResponse>() {
            @Override
            public void onResponse(NetworkResponse response) {
                Log.e("FLASK TEST", "got response");
                Log.e("FLASK TEST", response.toString());
                LOGD("FLASK TEST", response.toString());
                collectionStatusText.setText(response.toString());

                String resultResponse = new String(response.data);
                try {
                    JSONObject result = new JSONObject(resultResponse);
                    String exercise = result.getString("Exercise");
                    String form = result.getString("Form");
                    String reps = result.getString("reps");
                    Log.e("FLASK TEST", exercise);
                    Log.e("FLASK TEST", form);
                    Log.e("FLASK TEST", reps);

                    /*if (status.equals(Constant.REQUEST_SUCCESS)) {
                        // tell everybody you have succed upload image and post strings
                        Log.i("Messsage", message);
                    } else {
                        Log.i("Unexpected", message);
                    }*/
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                NetworkResponse networkResponse = error.networkResponse;
                String errorMessage = "Unknown error";
                if (networkResponse == null) {
                    if (error.getClass().equals(TimeoutError.class)) {
                        errorMessage = "Request timeout";
                    } else if (error.getClass().equals(NoConnectionError.class)) {
                        errorMessage = "Failed to connect server";
                    }
                } else {
                    String result = new String(networkResponse.data);

                        Log.e("ERROR RESULT", result);

                        if (networkResponse.statusCode == 404) {
                            errorMessage = "Resource not found";
                        } else if (networkResponse.statusCode == 401) {
                            errorMessage = " Please login again";
                        } else if (networkResponse.statusCode == 400) {
                            errorMessage = " Check your inputs";
                        } else if (networkResponse.statusCode == 500) {
                            errorMessage = " Something is getting wrong";
                        }
                }
                Log.i("Error", errorMessage);
                error.printStackTrace();
            }
        }) {
            @Override
            protected Map<String, String> getParams() {
                Map<String, String> params = new HashMap<>();
                params.put("exercise", "BP");
                return params;
            }
            @RequiresApi(api = Build.VERSION_CODES.O)
            @Override
            protected Map<String, DataPart> getByteData() {
                Map<String, DataPart> params = new HashMap<>();
                // file name could found file base or direct access from real path
                // for now just get bitmap data from ImageView
                try {
                    params.put("data", new DataPart(FILENAME, AppHelper.getFileDataFromFileName(getBaseContext(), FILENAME), "text/txt"));
                } catch (IOException e) {
                    e.printStackTrace();
                }
                return params;
            }
        };

        VolleySingleton.getInstance(getBaseContext()).addToRequestQueue(multipartRequest);
    }

}
