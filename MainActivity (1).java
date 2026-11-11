package com.wakeup.scanalarm;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.WebViewAssetLoader;

public class MainActivity extends Activity {
    WebView web;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Bridge(), "Android");

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest r) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        r.grant(r.getResources());
                    }
                });
            }
        });
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html");

        requestPermissions(new String[]{
                Manifest.permission.CAMERA,
                Manifest.permission.POST_NOTIFICATIONS}, 1);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (Build.VERSION.SDK_INT >= 34) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (!nm.canUseFullScreenIntent()) {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        Uri.parse("package:" + getPackageName())));
            }
        }
    }

    // While the alarm is ringing, swallow the volume keys and the back button.
    @Override
    public boolean onKeyDown(int k, KeyEvent e) {
        if (RingService.ringing && (k == KeyEvent.KEYCODE_VOLUME_UP
                || k == KeyEvent.KEYCODE_VOLUME_DOWN
                || k == KeyEvent.KEYCODE_VOLUME_MUTE)) {
            return true;
        }
        return super.onKeyDown(k, e);
    }

    @Override
    public void onBackPressed() {
        if (!RingService.ringing) super.onBackPressed();
    }

    class Bridge {
        PendingIntent op() {
            return PendingIntent.getForegroundService(MainActivity.this, 1,
                    new Intent(MainActivity.this, RingService.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }

        @JavascriptInterface
        public void schedule(long ms) {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            PendingIntent show = PendingIntent.getActivity(MainActivity.this, 2,
                    new Intent(MainActivity.this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(ms, show), op());
        }

        @JavascriptInterface
        public void cancel() {
            ((AlarmManager) getSystemService(Context.ALARM_SERVICE)).cancel(op());
        }

        @JavascriptInterface
        public boolean isRinging() {
            return RingService.ringing;
        }

        @JavascriptInterface
        public void startRing() {
            startForegroundService(new Intent(MainActivity.this, RingService.class));
        }

        @JavascriptInterface
        public void stopRing() {
            stopService(new Intent(MainActivity.this, RingService.class));
        }
    }

    // Rings with the screen off and keeps the alarm volume at maximum.
    public static class RingService extends Service {
        static volatile boolean ringing = false;
        MediaPlayer mp;
        AudioManager am;
        Handler h = new Handler(Looper.getMainLooper());
        Runnable loud = new Runnable() {
            public void run() {
                am.setStreamVolume(AudioManager.STREAM_ALARM,
                        am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
                h.postDelayed(this, 400);
            }
        };

        @Override
        public IBinder onBind(Intent i) {
            return null;
        }

        @Override
        public int onStartCommand(Intent in, int flags, int id) {
            if (ringing) return START_STICKY;
            ringing = true;

            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel("alarm", "Alarm",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);

            PendingIntent fs = PendingIntent.getActivity(this, 3,
                    new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(this, "alarm")
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("Wake up!")
                    .setContentText("Scan your object to stop the alarm")
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setOngoing(true)
                    .setContentIntent(fs)
                    .setFullScreenIntent(fs, true)
                    .build();
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);

            am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            try {
                Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
                mp = new MediaPlayer();
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
                mp.setDataSource(this, u);
                mp.setLooping(true);
                mp.prepare();
                mp.start();
            } catch (Exception e) {
                // keep going: the volume loop and notification still run
            }
            h.post(loud);
            return START_STICKY;
        }

        PendingIntent restartPi() {
            return PendingIntent.getForegroundService(getApplicationContext(), 4,
                    new Intent(getApplicationContext(), RingService.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }

        // If the app is swiped away while ringing, start the alarm again right away.
        @Override
        public void onTaskRemoved(Intent rootIntent) {
            if (ringing) {
                AlarmManager alarms = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                PendingIntent pi = restartPi();
                alarms.setAlarmClock(
                        new AlarmManager.AlarmClockInfo(System.currentTimeMillis() + 1000, pi), pi);
            }
            super.onTaskRemoved(rootIntent);
        }

        @Override
        public void onDestroy() {
            ((AlarmManager) getSystemService(Context.ALARM_SERVICE)).cancel(restartPi());
            h.removeCallbacks(loud);
            if (mp != null) {
                try { mp.stop(); } catch (Exception e) { }
                mp.release();
                mp = null;
            }
            ringing = false;
            super.onDestroy();
        }
    }
}
