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

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.wear.ambient.AmbientModeSupport;
import androidx.wear.widget.WearableRecyclerView;

import com.example.android.wearable.datalayer.DataLayerScreen.CapabilityDiscoveryData;
import com.example.android.wearable.datalayer.DataLayerScreen.DataLayerScreenData;
import com.example.android.wearable.datalayer.DataLayerScreen.EventLoggingData;
//import com.example.android.wearable.datalayer.DataLayerScreen.ImageAssetData;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.ChannelApi;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.DataClient;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.io.InputStream;
import java.nio.Buffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/**
 * Displays {@link WearableRecyclerView} with three main rows of data showing off various features
 * of the Data Layer APIs:
 *
 * <p>
 *
 * <ul>
 *   <li>Row 1: Shows a log of DataItems received from the phone application using {@link
 *       MessageClient}
 *   <li>Row 2: Shows a photo sent from the phone application using {@link DataClient}
 *   <li>Row 3: Displays two buttons to check the connected phone and watch devices using the {@link
 *       CapabilityClient}
 * </ul>
 */

public class MainActivity extends FragmentActivity
        implements AmbientModeSupport.AmbientCallbackProvider,
                DataClient.OnDataChangedListener,
                MessageClient.OnMessageReceivedListener,
                CapabilityClient.OnCapabilityChangedListener,
        SensorEventListener {

    private static final String TAG = "MainActivity";

    private static final String CAPABILITY_1_NAME = "capability_1";
    private static final String CAPABILITY_2_NAME = "capability_2";

    ArrayList<DataLayerScreenData> mDataLayerScreenData;

    private WearableRecyclerView mWearableRecyclerView;
    private RecyclerView.LayoutManager mLayoutManager;
    private CustomRecyclerAdapter mCustomRecyclerAdapter;

    private SensorManager sensorManager;

    private Sensor mGyro;
    private Sensor mAccel;
    private float [] mGyroValues = new float [3];
    private float [] mAccelValues = new float [3];

    private ArrayList<float[]> BufmAccel;
    private ArrayList<float[]> BufmGyro;

    private String hosturiID;
    byte[] hostPayload;
    Long BaseTime;
    boolean isSendingData = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_activity);

        AmbientModeSupport.attach(this);

        mWearableRecyclerView = findViewById(R.id.recycler_view);

        // Aligns the first and last items on the list vertically centered on the screen.
        mWearableRecyclerView.setEdgeItemsCenteringEnabled(true);

        // Improves performance because we know changes in content do not change the layout size of
        // the RecyclerView.
        mWearableRecyclerView.setHasFixedSize(true);

        mLayoutManager = new LinearLayoutManager(this);
        mWearableRecyclerView.setLayoutManager(mLayoutManager);

        mDataLayerScreenData = new ArrayList<>();

        Bitmap defaultBitmap =
                ((BitmapDrawable)
                                ResourcesCompat.getDrawable(
                                        getResources(), R.drawable.photo_placeholder, null))
                        .getBitmap();

        mDataLayerScreenData.add(new EventLoggingData());
        //mDataLayerScreenData.add(new ImageAssetData(defaultBitmap));
        mDataLayerScreenData.add(new CapabilityDiscoveryData());

        // Specifies an adapter (see also next example).
        mCustomRecyclerAdapter = new CustomRecyclerAdapter(mDataLayerScreenData);

        mWearableRecyclerView.setAdapter(mCustomRecyclerAdapter);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        mAccel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mGyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        //Log.e("SENSOR TEST", "sensor setup complete");

        BaseTime = System.nanoTime();

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        //Settings.System.putInt(getContentResolver(),Settings.System.SCREEN_OFF_TIMEOUT, -1);

    }

    @Override
    protected void onResume() {
        super.onResume();

        // Instantiates clients without member variables, as clients are inexpensive to create and
        // won't lose their listeners. (They are cached and shared between GoogleApi instances.)
        Wearable.getDataClient(this).addListener(this);
        Wearable.getMessageClient(this).addListener(this);
        Wearable.getCapabilityClient(this)
                .addListener(this, Uri.parse("wear://"), CapabilityClient.FILTER_REACHABLE);
        //register on resumre
        sensorManager.registerListener(this, mAccel, SensorManager.SENSOR_DELAY_FASTEST);
        sensorManager.registerListener(this, mGyro, SensorManager.SENSOR_DELAY_FASTEST);
    }

    @Override
    protected void onPause() {
        super.onPause();

        Wearable.getDataClient(this).removeListener(this);
        Wearable.getMessageClient(this).removeListener(this);
        Wearable.getCapabilityClient(this).removeListener(this);

        //unregister on pause
        sensorManager.unregisterListener(this);
    }

    /*
     * Sends data to proper WearableRecyclerView logger row or if the item passed is an asset, sends
     * to row displaying Bitmaps.
     */
    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        Log.d(TAG, "onDataChanged(): " + dataEvents);
/*
        for (DataEvent event : dataEvents) {
            if (event.getType() == DataEvent.TYPE_CHANGED) {
                String path = event.getDataItem().getUri().getPath();
                if (DataLayerListenerService.IMAGE_PATH.equals(path)) {
                    DataMapItem dataMapItem = DataMapItem.fromDataItem(event.getDataItem());
                    Asset photoAsset =
                            dataMapItem.getDataMap().getAsset(DataLayerListenerService.IMAGE_KEY);
                    // Loads image on background thread.
                    new LoadBitmapAsyncTask().execute(photoAsset);

                } else if (DataLayerListenerService.COUNT_PATH.equals(path)) {
                    Log.d(TAG, "Data Changed for COUNT_PATH");
                    mCustomRecyclerAdapter.appendToDataEventLog(
                            "DataItem Changed", event.getDataItem().toString());
                } else {
                    Log.d(TAG, "Unrecognized path: " + path);
                }

            } else if (event.getType() == DataEvent.TYPE_DELETED) {
                mCustomRecyclerAdapter.appendToDataEventLog(
                        "DataItem Deleted", event.getDataItem().toString());

            } else {
                mCustomRecyclerAdapter.appendToDataEventLog(
                        "Unknown data event type", "Type = " + event.getType());
            }
        }
*/
        for (DataEvent event : dataEvents) {
            Uri uri = event.getDataItem().getUri();
            String path = uri.getPath();

            if ("/count".equals(path)) {
                // Get the node id of the node that created the data item from the host portion of
                // the uri.
                String nodeId = uri.getHost();
                hosturiID = uri.getHost();
                // Set the data of the message to be the bytes of the Uri.
                byte[] payload = uri.toString().getBytes();

                // Send the rpc
                // Instantiates clients without member variables, as clients are inexpensive to
                // create. (They are cached and shared between GoogleApi instances.)
                Task<Integer> sendMessageTask =
                        Wearable.getMessageClient(this)
                                //.sendMessage(nodeId, , payload);
                                .sendMessage(nodeId, "/data-item-received", payload);

                sendMessageTask.addOnCompleteListener(
                        new OnCompleteListener<Integer>() {
                            @Override
                            public void onComplete(Task<Integer> task) {
                                if (task.isSuccessful()) {
                                    Log.d(TAG, "Message sent successfully");
                                } else {
                                    Log.d(TAG, "Message failed.");
                                }
                            }
                        });
            }
        }
    }

    public void onStartRecordingClicked(View view) {
        if (hosturiID != null) {
            Task<Integer> sendMessageTask = Wearable.getMessageClient(this).sendMessage(hosturiID, "Open File", hostPayload);
        }
        isSendingData = true;
    }
    public void onStopRecordingClicked(View view) {
        if (hosturiID != null) {
            Task<Integer> sendMessageTask = Wearable.getMessageClient(this).sendMessage(hosturiID, "Close File", hostPayload);
        }
        isSendingData = false;
    }
/*    *//*
     * Triggered directly from buttons in recycler_row_capability_discovery.xml to check
     * capabilities of connected devices.
     *//*
    public void onCapabilityDiscoveryButtonClicked(View view) {
        switch (view.getId()) {
            case R.id.capability_2_btn:
                showNodes(CAPABILITY_2_NAME);
                break;
            case R.id.capabilities_1_and_2_btn:
                showNodes(CAPABILITY_1_NAME, CAPABILITY_2_NAME);
                break;
            default:
                Log.e(TAG, "Unknown click event registered");
        }
    }*/

    /*
     * Sends data to proper WearableRecyclerView logger row.
     */
    @Override
    public void onMessageReceived(MessageEvent event) {
        Log.d(TAG, "onMessageReceived: " + event);
        mCustomRecyclerAdapter.appendToDataEventLog("Message", event.toString());
    }

    /*
     * Sends data to proper WearableRecyclerView logger row.
     */
    @Override
    public void onCapabilityChanged(CapabilityInfo capabilityInfo) {
        Log.d(TAG, "onCapabilityChanged: " + capabilityInfo);
        mCustomRecyclerAdapter.appendToDataEventLog(
                "onCapabilityChanged", capabilityInfo.toString());
    }

    /** Find the connected nodes that provide at least one of the given capabilities. */
    private void showNodes(final String... capabilityNames) {

        Task<Map<String, CapabilityInfo>> capabilitiesTask =
                Wearable.getCapabilityClient(this)
                        .getAllCapabilities(CapabilityClient.FILTER_REACHABLE);

        capabilitiesTask.addOnSuccessListener(
                new OnSuccessListener<Map<String, CapabilityInfo>>() {
                    @Override
                    public void onSuccess(Map<String, CapabilityInfo> capabilityInfoMap) {
                        Set<Node> nodes = new HashSet<>();

                        if (capabilityInfoMap.isEmpty()) {
                            showDiscoveredNodes(nodes);
                            return;
                        }
                        for (String capabilityName : capabilityNames) {
                            CapabilityInfo capabilityInfo = capabilityInfoMap.get(capabilityName);
                            if (capabilityInfo != null) {
                                nodes.addAll(capabilityInfo.getNodes());
                            }
                        }
                        showDiscoveredNodes(nodes);
                    }
                });
    }

    private void showDiscoveredNodes(Set<Node> nodes) {
        List<String> nodesList = new ArrayList<>();
        for (Node node : nodes) {
            nodesList.add(node.getDisplayName());
        }
        Log.d(
                TAG,
                "Connected Nodes: "
                        + (nodesList.isEmpty()
                                ? "No connected device was found for the given capabilities"
                                : TextUtils.join(",", nodesList)));
        String msg;
        if (!nodesList.isEmpty()) {
            msg = getString(R.string.connected_nodes, TextUtils.join(", ", nodesList));
        } else {
            msg = getString(R.string.no_device);
        }
        Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
    }

/*    *//*
     * Extracts {@link android.graphics.Bitmap} data from the
     * {@link com.google.android.gms.wearable.Asset}
     *//*
    private class LoadBitmapAsyncTask extends AsyncTask<Asset, Void, Bitmap> {

        @Override
        protected Bitmap doInBackground(Asset... params) {
            if (params.length > 0) {

                Asset asset = params[0];

                Task<DataClient.GetFdForAssetResponse> getFdForAssetResponseTask =
                        Wearable.getDataClient(getApplicationContext()).getFdForAsset(asset);

                try {
                    // Block on a task and get the result synchronously. This is generally done
                    // when executing a task inside a separately managed background thread. Doing
                    // this on the main (UI) thread can cause your application to become
                    // unresponsive.
                    DataClient.GetFdForAssetResponse getFdForAssetResponse =
                            Tasks.await(getFdForAssetResponseTask);

                    InputStream assetInputStream = getFdForAssetResponse.getInputStream();

                    if (assetInputStream != null) {
                        return BitmapFactory.decodeStream(assetInputStream);

                    } else {
                        Log.w(TAG, "Requested an unknown Asset.");
                        return null;
                    }

                } catch (ExecutionException exception) {
                    Log.e(TAG, "Failed retrieving asset, Task failed: " + exception);
                    return null;

                } catch (InterruptedException exception) {
                    Log.e(TAG, "Failed retrieving asset, interrupt occurred: " + exception);
                    return null;
                }

            } else {
                Log.e(TAG, "Asset must be non-null");
                return null;
            }
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {

            if (bitmap != null) {
                Log.d(TAG, "Setting background image on second page..");
                int imageAssetItemIndex = mCustomRecyclerAdapter.setImageAsset(bitmap);

                // Moves RecyclerView to appropriate row to show new image sent over.
                if (imageAssetItemIndex > -1) {
                    mWearableRecyclerView.scrollToPosition(imageAssetItemIndex);
                }
            }
        }
    }*/

    @Override
    public AmbientModeSupport.AmbientCallback getAmbientCallback() {
        return new MyAmbientCallback();
    }

    /** Customizes appearance for Ambient mode. (We don't do anything minus default.) */
    private class MyAmbientCallback extends AmbientModeSupport.AmbientCallback {
        /** Prepares the UI for ambient mode. */
        @Override
        public void onEnterAmbient(Bundle ambientDetails) {
            super.onEnterAmbient(ambientDetails);
        }

        /**
         * Updates the display in ambient mode on the standard interval. Since we're using a custom
         * refresh cycle, this method does NOT update the data in the display. Rather, this method
         * simply updates the positioning of the data in the screen to avoid burn-in, if the display
         * requires it.
         */
        @Override
        public void onUpdateAmbient() {
            super.onUpdateAmbient();
        }

        /** Restores the UI to active (non-ambient) mode. */
        @Override
        public void onExitAmbient() {
            super.onExitAmbient();
        }
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        //Log.e("SENSOR TEST", "SENSECHANGE");
        if (isSendingData) {
            if (sensorEvent.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                //Log.e("SENSOR TEST", "ACCELCHANGE");

               /* mAccelValues[0] = sensorEvent.values[0];
                mAccelValues[1] = sensorEvent.values[1];
                mAccelValues[2] = sensorEvent.values[2];*/

                //Log.e("SENSOR TEST Accel", "x: "+ sensorEvent.values[0] + ", y: " + sensorEvent.values[1] + ", z: " + sensorEvent.values[2]);

                //Intent i = new Intent(MainActivity.this, DataLayerListenerService.class);
                //i.putExtra("Accel", mAccelValues);

                //Log.e("SENSOR TEST", Long.toString(System.nanoTime() - BaseTime));
                sendMessage("DataA", System.nanoTime() - BaseTime, sensorEvent.values[0], sensorEvent.values[1], sensorEvent.values[2]);
            }

            if (sensorEvent.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
                //Log.e("SENSOR TEST", "GYROCHANGE");

               /* mGyroValues[0] = sensorEvent.values[0];
                mGyroValues[1] = sensorEvent.values[1];
                mGyroValues[2] = sensorEvent.values[2];*/

                sendMessage("DataG", System.nanoTime() - BaseTime, sensorEvent.values[0], sensorEvent.values[1], sensorEvent.values[2]);

                //Log.e("SENSOR TEST Gyro", "x: "+ sensorEvent.values[0] + ", y: " + sensorEvent.values[1] + ", z: " + sensorEvent.values[2]);
            }
        }
    }

    public void sendMessage(String DataType,Long time, float xVal, float yVal, float zVal) {
        if (hosturiID != null) {
            Task<Integer> sendMessageTask = Wearable.getMessageClient(this).sendMessage(hosturiID, DataType+ "," + Long.toString(time) + "," + Float.toString(xVal) + "," + Float.toString(yVal) + "," + Float.toString(zVal), hostPayload);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {
    }
}
