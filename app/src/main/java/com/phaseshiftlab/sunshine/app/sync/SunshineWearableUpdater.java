package com.phaseshiftlab.sunshine.app.sync;

import android.app.Activity;
import android.database.Cursor;
import android.os.Bundle;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.phaseshiftlab.sunshineutilitylib.data.WeatherConstantsDefinitions;

import static com.phaseshiftlab.sunshineutilitylib.data.WeatherConstantsDefinitions.DATA_PATH;
import static com.phaseshiftlab.sunshineutilitylib.data.WeatherConstantsDefinitions.MAX_TEMP;
import static com.phaseshiftlab.sunshineutilitylib.data.WeatherConstantsDefinitions.MIN_TEMP;
import static com.phaseshiftlab.sunshineutilitylib.data.WeatherConstantsDefinitions.WEATHER_CONDITION_ID;


public class SunshineWearableUpdater {
    private static GoogleApiClient mGoogleApiClient;

    public static void update(final Cursor data, Activity activity) {
        final Double currentMax;
        final Double currentMin;

        if (data != null && data.getCount()>0) {
            data.moveToFirst();
            currentMax = data.getDouble(WeatherConstantsDefinitions.COL_WEATHER_MAX_TEMP);
            currentMin = data.getDouble(WeatherConstantsDefinitions.COL_WEATHER_MIN_TEMP);
        }
        else {
            currentMax = null;
            currentMin= null;
        }

        mGoogleApiClient = new GoogleApiClient.Builder(activity)
                .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                    @Override
                    public void onConnected(Bundle connectionHint) {
                        // Now you can use the Data Layer API
                        PutDataMapRequest putDataMapReq = PutDataMapRequest.create(DATA_PATH);

                        int minTemp = (int) Math.round(currentMin);
                        int maxTemp = (int) Math.round(currentMax);

                        putDataMapReq.getDataMap().putInt(MIN_TEMP, minTemp);
                        putDataMapReq.getDataMap().putInt(MAX_TEMP, maxTemp);

                        int weatherId = data.getInt(WeatherConstantsDefinitions.COL_WEATHER_CONDITION_ID);
                        putDataMapReq.getDataMap().putInt(WEATHER_CONDITION_ID, weatherId);

                        PutDataRequest putDataReq = putDataMapReq.asPutDataRequest();
                        PendingResult<DataApi.DataItemResult> pendingResult =
                                Wearable.DataApi.putDataItem(mGoogleApiClient, putDataReq);
                    }
                    @Override
                    public void onConnectionSuspended(int cause) {
                    }
                })
                .addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
                    @Override
                    public void onConnectionFailed(ConnectionResult result) {
                    }
                })
                // Request access only to the Wearable API
                .addApi(Wearable.API)
                .build();
        mGoogleApiClient.connect();
    }
}
