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

package com.phaseshiftlab.sunshinewatch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.res.ResourcesCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.util.TypedValue;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;
import com.phaseshiftlab.sunshineutilitylib.Utility;

import java.lang.ref.WeakReference;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import static android.R.attr.centerY;
import static android.support.wearable.R.id.width;
import static com.phaseshiftlab.sunshineutilitylib.data.WeatherConstantsDefinitions.DEGREE;
import static com.phaseshiftlab.sunshineutilitylib.data.WeatherConstantsDefinitions.MAX_TEMP;
import static com.phaseshiftlab.sunshineutilitylib.data.WeatherConstantsDefinitions.MIN_TEMP;
import static com.phaseshiftlab.sunshineutilitylib.data.WeatherConstantsDefinitions.WEATHER_CONDITION_ID;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class MyWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;
    private static final String TAG = "PhaseshiftWatchFace";

    @Override
    public Engine onCreateEngine() {
        Log.d("DEBUG", "onCreateEngine");
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> engineWeakReference;

        public EngineHandler(MyWatchFace.Engine reference) {
            engineWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = engineWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
        final Handler handler = new EngineHandler(this);
        boolean registeredTimeZoneReceiver = false;
        Paint backgroundPaint;
        Paint textMaxTempPaint;
        Paint textMinTempPaint;
        Paint dateTextPaint;
        boolean isAmbient;
        Time localTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                localTime.clear(intent.getStringExtra("time-zone"));
                localTime.setToNow();
            }
        };
        private Integer minTemp;
        private Integer maxTemp;
        private Integer weatherId;
        private Float maxTempHeight;
        private Integer tapCount;
        private Float xOffset;
        private Float yOffset;

        GoogleApiClient googleApiClient = new GoogleApiClient.Builder(MyWatchFace.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean lowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = MyWatchFace.this.getResources();
            yOffset = resources.getDimension(R.dimen.digital_y_offset);

            textMaxTempPaint = new Paint();
            textMaxTempPaint = createTextPaint(resources.getColor(R.color.digital_text));

            textMinTempPaint = new Paint();
            textMinTempPaint = createTextPaint(resources.getColor(R.color.primary_light));

            localTime = new Time();
        }

        @Override
        public void onDestroy() {
            handler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                googleApiClient.connect();
                // Update time zone in case it changed while we weren't visible.
                localTime.clear(TimeZone.getDefault().getID());
                localTime.setToNow();
            } else {
                unregisterReceiver();
                if (googleApiClient != null && googleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(googleApiClient, this);
                    googleApiClient.disconnect();
                    Log.d(TAG, "disconnecting GoogleApiClient");
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (registeredTimeZoneReceiver) {
                return;
            }
            registeredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!registeredTimeZoneReceiver) {
                return;
            }
            registeredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = MyWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            xOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            textMinTempPaint.setTextSize(resources.getDimension(R.dimen.temperature_font_size));
            textMaxTempPaint.setTextSize(resources.getDimension(R.dimen.temperature_font_size));
            maxTempHeight = textMaxTempPaint.getTextSize();
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            lowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (isAmbient != inAmbientMode) {
                isAmbient = inAmbientMode;
                if (lowBitAmbient) {
                    textMaxTempPaint.setAntiAlias(!inAmbientMode);
                    textMinTempPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = MyWatchFace.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    tapCount++;
                    backgroundPaint.setColor(resources.getColor(tapCount % 2 == 0 ?
                            R.color.background : R.color.background2));
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), backgroundPaint);
            }

            localTime.setToNow();

            String dateText = localTime.format("EEE, MMM dd yyyy").toUpperCase();
            Rect tempRect = new Rect();
            dateTextPaint.getTextBounds(dateText, 0, dateText.length(), tempRect);
            Float dateStartX = (float) (bounds.centerX() - (tempRect.width() / 2));
            Float dateStartY = (float) (bounds.centerY() - tempRect.height() + 10);
            canvas.drawText(dateText, // Text to display
                    dateStartX,
                    dateStartY,
                    dateTextPaint
            );

            if (!isInAmbientMode()) {
                Resources r = getResources();
                Integer artMargin = Math.round(getResources().getDimension(R.dimen.icon_margin_top));
                Float artMarginPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, artMargin, r.getDisplayMetrics());
                Integer art = Math.round(getResources().getDimension(R.dimen.weather_icon_size));
                Float artPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, art, r.getDisplayMetrics());

                Float offsetTextPx = (artPx - maxTempHeight) / 2f;
                Float textBaselinePx = centerY + artMarginPx + artPx - offsetTextPx;
                Integer weatherInfoSpacing = Math.round((width - artPx) / 4);
                Float totalPx = weatherInfoSpacing + artPx;
                if (weatherId != null) {
                    drawWeatherArt(weatherInfoSpacing, totalPx, artMarginPx, artPx, canvas);
                }
                if (maxTemp != null) {
                    canvas.drawText(toDegree(maxTemp), 2 * totalPx, textBaselinePx, textMaxTempPaint);
                }
                if (minTemp != null) {
                    canvas.drawText(toDegree(minTemp), 3 * totalPx, textBaselinePx, textMinTempPaint);
                }
            }
        }

        private void drawWeatherArt(Integer weatherInfoSpacing, Float totalPx, Float artMarginPx, Float artPx, Canvas canvas) {
            int weatherImage = Utility.getArtResourceForWeatherCondition(weatherId);
            Drawable weatherArt = ResourcesCompat.getDrawable(getResources(), weatherImage, null);
            if (weatherArt != null) {
                weatherArt.setBounds(weatherInfoSpacing, Math.round(centerY + artMarginPx),
                         Math.round(totalPx), Math.round(centerY + artPx + artMarginPx));
                weatherArt.draw(canvas);
            }
        }

        private String toDegree(Integer temp) {
            return Integer.toString(temp) + DEGREE;
        }

        /**
         * Starts the {@link #handler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            handler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                handler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #handler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                handler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Wearable.DataApi.addListener(googleApiClient, Engine.this);
        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            for (DataEvent event : dataEventBuffer) {

                DataItem dataItem = event.getDataItem();
                DataMapItem dataMapItem = DataMapItem.fromDataItem(dataItem);
                DataMap config = dataMapItem.getDataMap();
                for (String configKey : config.keySet()) {
                    if (configKey.equals(MIN_TEMP)) {
                        minTemp = config.getInt(MIN_TEMP);
                    }
                    if (configKey.equals(MAX_TEMP)) {
                        maxTemp = config.getInt(MAX_TEMP);
                    }
                    if (configKey.equals(WEATHER_CONDITION_ID)) {
                        weatherId = config.getInt(WEATHER_CONDITION_ID);
                    }
                }
            }
        }
    }
}
