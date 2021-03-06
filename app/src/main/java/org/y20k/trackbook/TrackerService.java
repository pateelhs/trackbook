/**
 * TrackerService.java
 * Implements the app's movement tracker service
 * The TrackerService creates a Track object and displays a notification
 *
 * This file is part of
 * TRACKBOOK - Movement Recorder for Android
 *
 * Copyright (c) 2016-17 - Y20K.org
 * Licensed under the MIT-License
 * http://opensource.org/licenses/MIT
 *
 * Trackbook uses osmdroid - OpenStreetMap-Tools for Android
 * https://github.com/osmdroid/osmdroid
 */

package org.y20k.trackbook;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.widget.Toast;

import org.y20k.trackbook.core.Track;
import org.y20k.trackbook.core.WayPoint;
import org.y20k.trackbook.helpers.LocationHelper;
import org.y20k.trackbook.helpers.LogHelper;
import org.y20k.trackbook.helpers.NotificationHelper;
import org.y20k.trackbook.helpers.StorageHelper;
import org.y20k.trackbook.helpers.TrackbookKeys;

import java.util.List;


/**
 * TrackerService class
 */
public class TrackerService extends Service implements TrackbookKeys, SensorEventListener {

    /* Define log tag */
    private static final String LOG_TAG = TrackerService.class.getSimpleName();


    /* Main class variables */
    private Track mTrack;
    private CountDownTimer mTimer;
    private LocationManager mLocationManager;
    private SensorManager mSensorManager;
    private float mStepCountOffset;
    private LocationListener mGPSListener = null;
    private LocationListener mNetworkListener = null;
    private SettingsContentObserver mSettingsContentObserver;
    private Location mCurrentBestLocation;
    private Notification mNotification;
    private NotificationCompat.Builder mNotificationBuilder;
    private NotificationManager mNotificationManager;
    private boolean mTrackerServiceRunning;
    private boolean mLocationSystemSetting;


    @Override
    public void onCreate() {
        super.onCreate();

        // prepare notification channel and get NotificationManager
        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationHelper.createNotificationChannel(this);

        // acquire reference to Location Manager
        mLocationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);

        // acquire reference to Sensor Manager
        mSensorManager = (SensorManager) this.getSystemService(Context.SENSOR_SERVICE);

        // get state of location system setting
        mLocationSystemSetting = LocationHelper.checkLocationSystemSetting(getApplicationContext());

        // create content observer for changes in System Settings
        mSettingsContentObserver = new SettingsContentObserver(new Handler());
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        // check if user did turn off location in device settings
        if (!mLocationSystemSetting) {
            LogHelper.i(LOG_TAG, "Location Setting is turned off.");
            Toast.makeText(getApplicationContext(), R.string.toast_message_location_offline, Toast.LENGTH_LONG).show();
            stopTracking();
            return START_STICKY;
        }

        // checking for empty intent
        if (intent == null || intent.getAction() == null) {
            LogHelper.e(LOG_TAG, "Null-Intent received. Stopping self.");
            // stopSelf triggers onDestroy
            stopSelf();
        }

        // ACTION START
        else if (intent.getAction().equals(ACTION_START) && mLocationSystemSetting) {
            startTracking(intent);
        }

        // ACTION STOP
        else if (intent.getAction().equals(ACTION_STOP) || !mLocationSystemSetting) {
            mTrackerServiceRunning = false;
            if (mTrack != null && mTimer != null) {
                stopTracking();
            } else {
                // handle error - save state
                saveTrackerServiceState(mTrackerServiceRunning, FAB_STATE_DEFAULT);
            }
        }

        // ACTION DISMISS
        else if (intent.getAction().equals(ACTION_DISMISS)) {
            // save state
            saveTrackerServiceState(mTrackerServiceRunning, FAB_STATE_DEFAULT);
            // dismiss notification
            mNotificationManager.cancel(TRACKER_SERVICE_NOTIFICATION_ID); // todo check if necessary?
            stopForeground(true);
        }

        // ACTION TRACK REQUEST
        else if (intent.getAction().equals(ACTION_TRACK_REQUEST)) {
            // send track via broadcast
            sendTrackUpdate();
        }

        // START_STICKY is used for services that are explicitly started and stopped as needed
        return START_STICKY;
    }


    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }


    @Override
    public void onDestroy() {
        LogHelper.v(LOG_TAG, "onDestroy called.");

        if (mTrackerServiceRunning) {
            stopTracking();
        }

//        // remove listeners
//        stopFindingLocation();
//        mSensorManager.unregisterListener(this);

        // remove TrackerService from foreground state
        stopForeground(true);

        super.onDestroy();
    }


    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        // save the step count offset / previously recorded steps
        if (mStepCountOffset == 0) {
            mStepCountOffset = sensorEvent.values[0] - 1;
        }

        // calculate step count
        float stepCount = sensorEvent.values[0] - mStepCountOffset;

        // set step count in track
        mTrack.setStepCount(stepCount);
    }


    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }


    /* Start tracking location */
    private void startTracking(Intent intent) {
        LogHelper.v(LOG_TAG, "Service received command: START");

        // create a new track
        mTrack = new Track();

        // get last location
        if (intent.hasExtra(EXTRA_LAST_LOCATION)) {
            mCurrentBestLocation = intent.getParcelableExtra(EXTRA_LAST_LOCATION);
        }
        //  get last location - fallback
        if (mCurrentBestLocation == null) {
            mCurrentBestLocation = LocationHelper.determineLastKnownLocation(mLocationManager);
        }

        // add last location as WayPoint to track
        addWayPointToTrack();

        // put up notification
        mNotificationBuilder = new NotificationCompat.Builder(this, NOTIFICATION_CHANEL_ID_RECORDING_CHANNEL);
        mNotification = NotificationHelper.getNotification(this, mNotificationBuilder, mTrack, true);
        mNotificationManager.notify(TRACKER_SERVICE_NOTIFICATION_ID, mNotification); // todo check if necessary in pre Android O

        // set timer to retrieve new locations and to prevent endless tracking
        mTimer = new CountDownTimer(EIGHT_HOURS_IN_MILLISECONDS, FIFTEEN_SECONDS_IN_MILLISECONDS) {
            @Override
            public void onTick(long millisUntilFinished) {
                // update track duration
                long duration = EIGHT_HOURS_IN_MILLISECONDS - millisUntilFinished;
                mTrack.setDuration(duration);
                // try to add WayPoint to Track
                addWayPointToTrack();
                // update notification
                mNotification = NotificationHelper.getUpdatedNotification(TrackerService.this, mNotificationBuilder, mTrack);
                mNotificationManager.notify(TRACKER_SERVICE_NOTIFICATION_ID, mNotification);
            }

            @Override
            public void onFinish() {
                // stop tracking after eight hours
                stopTracking();
            }
        };
        mTimer.start();

        // initialize step counter
        mStepCountOffset = 0;
        Sensor stepCounter = mSensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if (stepCounter != null) {
            mSensorManager.registerListener(this, stepCounter, SensorManager.SENSOR_DELAY_UI);
        } else {
            LogHelper.i(LOG_TAG, "Pedometer Sensor not available");
            mTrack.setStepCount(-1);
        }

        // create gps and network location listeners
        startFindingLocation();

        // register content observer for changes in System Settings
        this.getContentResolver().registerContentObserver(android.provider.Settings.Secure.CONTENT_URI, true, mSettingsContentObserver);

        // start service in foreground
        startForeground(TRACKER_SERVICE_NOTIFICATION_ID, mNotification);
    }


    /* Stop tracking location */
    private void stopTracking() {
        LogHelper.v(LOG_TAG, "Service received command: STOP");

        // store current date and time
        mTrack.setRecordingEnd();

        // stop timer
        mTimer.cancel();

        // broadcast an updated track
        sendTrackUpdate();

        // save a temp file in case the activity has been killed
        SaveTempTrackAsyncHelper saveTempTrackAsyncHelper = new SaveTempTrackAsyncHelper();
        saveTempTrackAsyncHelper.execute();

        // change notification
        mNotificationBuilder = new NotificationCompat.Builder(this, NOTIFICATION_CHANEL_ID_RECORDING_CHANNEL);
        mNotification = NotificationHelper.getNotification(this, mNotificationBuilder, mTrack, false);
        mNotificationManager.notify(TRACKER_SERVICE_NOTIFICATION_ID, mNotification);

        // remove listeners
        stopFindingLocation();
        mSensorManager.unregisterListener(this);

        // disable content observer for changes in System Settings
        this.getContentResolver().unregisterContentObserver(mSettingsContentObserver);

        // remove TrackerService from foreground state
        stopForeground(false);
    }


    /* Adds a new WayPoint to current track */
    private void addWayPointToTrack() {

        // create new WayPoint
        WayPoint newWayPoint = null;

        // get number of previously tracked WayPoints
        int trackSize = mTrack.getWayPoints().size();

        if (trackSize == 0) {
            // add first location to track
            newWayPoint = mTrack.addWayPoint(mCurrentBestLocation);
        } else {
            // get last WayPoint and compare it to current location
            Location lastWayPoint = mTrack.getWayPointLocation(trackSize - 1);

            // default value for average speed
            float averageSpeed = 0f;

            // compute average speed if new location come from network provider
            if (trackSize > 1 && mCurrentBestLocation.getProvider().equals(LocationManager.NETWORK_PROVIDER)) {
                Location firstWayPoint = mTrack.getWayPointLocation(0);
                float distance = firstWayPoint.distanceTo(lastWayPoint);
                long timeDifference = lastWayPoint.getElapsedRealtimeNanos() - firstWayPoint.getElapsedRealtimeNanos();
                averageSpeed = distance / ((float) timeDifference / ONE_NANOSECOND);
            }

            if (LocationHelper.isNewWayPoint(lastWayPoint, mCurrentBestLocation, averageSpeed)) {
                // if new, add current best location to track
                newWayPoint = mTrack.addWayPoint(mCurrentBestLocation);
            }

        }

        // send local broadcast if new WayPoint added
        if (newWayPoint != null) {
            sendTrackUpdate();
        }

    }


    /* Broadcasts a track update */
    private void sendTrackUpdate() {
        if (mTrack != null) {
            Intent i = new Intent();
            i.setAction(ACTION_TRACK_UPDATED);
            i.putExtra(EXTRA_TRACK, mTrack);
            i.putExtra(EXTRA_LAST_LOCATION, mCurrentBestLocation);
            LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(i);
        }
    }


    /* Creates a location listener */
    private LocationListener createLocationListener() {
        return new LocationListener() {
            public void onLocationChanged(Location location) {
                // check if the new location is better
                if (LocationHelper.isBetterLocation(location, mCurrentBestLocation)) {
                    // save location
                    mCurrentBestLocation = location;
                }
            }

            public void onStatusChanged(String provider, int status, Bundle extras) {
                LogHelper.v(LOG_TAG, "Location provider status change: " + provider + " | " + status);
            }

            public void onProviderEnabled(String provider) {
                LogHelper.v(LOG_TAG, "Location provider enabled: " + provider);
            }

            public void onProviderDisabled(String provider) {
                LogHelper.v(LOG_TAG, "Location provider disabled: " + provider);
            }
        };
    }


    /* Creates gps and network location listeners */
    private void startFindingLocation() {

        // register location listeners and request updates
        List locationProviders = mLocationManager.getAllProviders();
        if (locationProviders.contains(LocationManager.GPS_PROVIDER)) {
            mGPSListener = createLocationListener();
            mTrackerServiceRunning = true;
        }
        if (locationProviders.contains(LocationManager.NETWORK_PROVIDER)) {
            mNetworkListener = createLocationListener();
            mTrackerServiceRunning = true;
        }
        LocationHelper.registerLocationListeners(mLocationManager, mGPSListener, mNetworkListener);
        saveTrackerServiceState(mTrackerServiceRunning, FAB_STATE_RECORDING);
    }


    /* Removes gps and network location listeners */
    private void stopFindingLocation() {
        // remove listeners
        LocationHelper.removeLocationListeners(mLocationManager, mGPSListener, mNetworkListener);
        mTrackerServiceRunning = false;
        saveTrackerServiceState(mTrackerServiceRunning, FAB_STATE_SAVE);

        // notify MainActivityMapFragment
        Intent i = new Intent();
        i.setAction(ACTION_TRACKING_STOPPED);
        i.putExtra(EXTRA_TRACK, mTrack);
        i.putExtra(EXTRA_LAST_LOCATION, mCurrentBestLocation);
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(i);
    }


    /* Saves state of Tracker Service and floating Action Button */
    private void saveTrackerServiceState(boolean trackerServiceRunning, int fabState) {
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = settings.edit();
        editor.putBoolean(PREFS_TRACKER_SERVICE_RUNNING, trackerServiceRunning);
        editor.putInt(PREFS_FAB_STATE, fabState);
        editor.apply();
    }


    /**
     * Inner class: SettingsContentObserver is a custom ContentObserver for changes in Android Settings
     */
    public class SettingsContentObserver extends ContentObserver {

        public SettingsContentObserver(Handler handler) {
            super(handler);
        }

        @Override
        public boolean deliverSelfNotifications() {
            return super.deliverSelfNotifications();
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            LogHelper.v(LOG_TAG, "System Setting change detected.");

            // check if location setting was changed
            boolean previousLocationSystemSetting = mLocationSystemSetting;
            mLocationSystemSetting = LocationHelper.checkLocationSystemSetting(getApplicationContext());
            if (previousLocationSystemSetting != mLocationSystemSetting && !mLocationSystemSetting && mTrackerServiceRunning) {
                LogHelper.v(LOG_TAG, "Location Setting turned off while tracking service running.");
                if (mTrack != null) {
                    stopTracking();
                }
                stopForeground(true);
            }
        }

    }
    /**
     * End of inner class
     */


    /**
     * Inner class: Saves track to external storage using AsyncTask
     */
    private class SaveTempTrackAsyncHelper extends AsyncTask<Void, Void, Void> {

        @Override
        protected Void doInBackground(Void... voids) {
            LogHelper.v(LOG_TAG, "Saving temporary track object in background.");
            // save track object
            StorageHelper storageHelper = new StorageHelper(TrackerService.this);
            storageHelper.saveTrack(mTrack, FILE_TEMP_TRACK);
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            super.onPostExecute(aVoid);
            LogHelper.v(LOG_TAG, "Saving finished.");
        }
    }
    /**
     * End of inner class
     */

}