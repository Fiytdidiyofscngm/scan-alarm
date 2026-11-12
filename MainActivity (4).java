package com.wakeup.scanalarm;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Base64;
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
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.label.ImageLabel;
import com.google.mlkit.vision.label.ImageLabeler;
import com.google.mlkit.vision.label.ImageLabeling;
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    static volatile boolean visible = false;
    WebView web;
    SharedPreferences sp;
    ImageLabeler labeler;

    static PendingIntent watchPi(Context c) {
        Context a = c.getApplicationContext();
        return PendingIntent.getForegroundService(a, 4, new Intent(a, RingService.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sp = getSharedPreferences("p", 0);

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

            @Override
            public void onPageFinished(WebView v, String url) {
                pushRing();
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

        // Never ask for permissions while the alarm is ringing.
        if (!isRingActive()) askPermissions(false);
    }

    boolean isRingActive() {
        return RingService.ringing || sp.getBoolean("ring", false);
    }

    void pushRing() {
        if (isRingActive()) web.evaluateJavascript("window.nativeRing&&nativeRing()", null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        pushRing();
    }

    @Override
    protected void onPause() {
        visible = false;
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        pushRing();
    }

    // Asks only for what is still missing. After two refusals it opens the settings page instead.
    void askPermissions(boolean fromButton) {
        List<String> need = new ArrayList<String>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.CAMERA);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (need.isEmpty()) {
            if (fromButton) openSettingsPage();
            else askFsiOnce();
            return;
        }
        int asks = sp.getInt("permAsks", 0);
        if (fromButton && asks >= 2) {
            openSettingsPage();
            return;
        }
        if (!fromButton && asks >= 2) return;
        sp.edit().putInt("permAsks", asks + 1).apply();
        requestPermissions(need.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int c, String[] p, int[] r) {
        super.onRequestPermissionsResult(c, p, r);
        if (!isRingActive()) askFsiOnce();
    }

    void askFsiOnce() {
        if (Build.VERSION.SDK_INT >= 34 && !sp.getBoolean("fsiAsked", false)) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (!nm.canUseFullScreenIntent()) {
                sp.edit().putBoolean("fsiAsked", true).apply();
                openFsiSettings();
            }
        }
    }

    void openSettingsPage() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            // ignore
        }
    }

    void openFsiSettings() {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        Uri.parse("package:" + getPackageName())));
            } else {
                openSettingsPage();
            }
        } catch (Exception e) {
            openSettingsPage();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent d) {
        super.onActivityResult(req, res, d);
        if (req == 7 && res == RESULT_OK && d != null) {
            Uri u = d.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
            if (u != null) sp.edit().putString("snd", u.toString()).apply();
        }
    }

    // While ringing, swallow the volume keys and the back button.
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

    void deliver(final String id, final String json) {
        runOnUiThread(new Runnable() {
            public void run() {
                web.evaluateJavascript("window.onLabels&&onLabels('" + id + "'," + json + ")", null);
            }
        });
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
        public boolean shouldRing() {
            return isRingActive();
        }

        @JavascriptInterface
        public void startRing() {
            startForegroundService(new Intent(MainActivity.this, RingService.class));
        }

        @JavascriptInterface
        public void stopRing() {
            RingService.ringing = false;
            sp.edit().putBoolean("ring", false).apply();
            ((AlarmManager) getSystemService(Context.ALARM_SERVICE)).cancel(watchPi(MainActivity.this));
            stopService(new Intent(MainActivity.this, RingService.class));
        }

        @JavascriptInterface
        public int getVol() {
            return sp.getInt("vol", 100);
        }

        @JavascriptInterface
        public void setVol(int pct) {
            sp.edit().putInt("vol", Math.max(10, Math.min(100, pct))).apply();
        }

        @JavascriptInterface
        public void pickSound() {
            runOnUiThread(new Runnable() {
                public void run() {
                    Intent i = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
                    i.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM);
                    i.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
                    i.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false);
                    String cur = sp.getString("snd", null);
                    if (cur != null) i.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(cur));
                    startActivityForResult(i, 7);
                }
            });
        }

        @JavascriptInterface
        public String soundName() {
            try {
                String cur = sp.getString("snd", null);
                Uri u = cur != null ? Uri.parse(cur)
                        : RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                return RingtoneManager.getRingtone(MainActivity.this, u).getTitle(MainActivity.this);
            } catch (Exception e) {
                return "Default";
            }
        }

        @JavascriptInterface
        public String status() {
            boolean cam = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
            boolean notif = getSystemService(NotificationManager.class).areNotificationsEnabled();
            boolean overlay = Settings.canDrawOverlays(MainActivity.this);
            boolean fsi = Build.VERSION.SDK_INT < 34
                    || getSystemService(NotificationManager.class).canUseFullScreenIntent();
            return "{\"cam\":" + cam + ",\"notif\":" + notif + ",\"overlay\":" + overlay
                    + ",\"fsi\":" + fsi + "}";
        }

        @JavascriptInterface
        public void askPerm() {
            runOnUiThread(new Runnable() {
                public void run() {
                    askPermissions(true);
                }
            });
        }

        @JavascriptInterface
        public void openOverlaySettings() {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        openSettingsPage();
                    }
                }
            });
        }

        @JavascriptInterface
        public void openFsiSettings() {
            runOnUiThread(new Runnable() {
                public void run() {
                    MainActivity.this.openFsiSettings();
                }
            });
        }

        @JavascriptInterface
        public void openLockScreenSettings() {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        Intent i = new Intent("miui.intent.action.APP_PERM_EDITOR");
                        i.setClassName("com.miui.securitycenter",
                                "com.miui.permcenter.permissions.PermissionsEditorActivity");
                        i.putExtra("extra_pkgname", getPackageName());
                        startActivity(i);
                    } catch (Exception e) {
                        openSettingsPage();
                    }
                }
            });
        }

        @JavascriptInterface
        public void openAutostart() {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        Intent i = new Intent();
                        i.setComponent(new ComponentName("com.miui.securitycenter",
                                "com.miui.permcenter.autostart.AutoStartManagementActivity"));
                        startActivity(i);
                    } catch (Exception e) {
                        openSettingsPage();
                    }
                }
            });
        }

        @JavascriptInterface
        public void openAppSettings() {
            runOnUiThread(new Runnable() {
                public void run() {
                    openSettingsPage();
                }
            });
        }

        // While ringing, hide the app from the recent apps list so it can't be swiped away.
        @JavascriptInterface
        public void hideRecents(final boolean hide) {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        ActivityManager m = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                        for (ActivityManager.AppTask t : m.getAppTasks()) {
                            t.setExcludeFromRecents(hide);
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                }
            });
        }

        // On-device image labels (ML Kit). Result comes back through window.onLabels.
        @JavascriptInterface
        public void labels(final String id, String b64) {
            try {
                byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bmp == null) {
                    deliver(id, "null");
                    return;
                }
                if (labeler == null) {
                    labeler = ImageLabeling.getClient(
                            new ImageLabelerOptions.Builder().setConfidenceThreshold(0.05f).build());
                }
                labeler.process(InputImage.fromBitmap(bmp, 0))
                        .addOnSuccessListener(new OnSuccessListener<List<ImageLabel>>() {
                            public void onSuccess(List<ImageLabel> ls) {
                                StringBuilder sb = new StringBuilder("[");
                                for (int i = 0; i < ls.size(); i++) {
                                    if (i > 0) sb.append(',');
                                    sb.append('[').append(ls.get(i).getIndex()).append(',')
                                            .append(String.format(Locale.US, "%.3f", ls.get(i).getConfidence()))
                                            .append(']');
                                }
                                sb.append(']');
                                deliver(id, sb.toString());
                            }
                        })
                        .addOnFailureListener(new OnFailureListener() {
                            public void onFailure(Exception e) {
                                deliver(id, "null");
                            }
                        });
            } catch (Throwable t) {
                deliver(id, "null");
            }
        }
    }

    // Rings with the screen off, keeps the alarm volume where you set it, and keeps coming back
    // until the object is scanned.
    public static class RingService extends Service {
        static volatile boolean ringing = false;
        MediaPlayer mp;
        AudioManager am;
        PowerManager.WakeLock wl;
        int beat = 0;
        Handler h = new Handler(Looper.getMainLooper());
        Runnable loud = new Runnable() {
            public void run() {
                SharedPreferences sp = getSharedPreferences("p", 0);
                int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
                int pct = sp.getInt("vol", 100);
                am.setStreamVolume(AudioManager.STREAM_ALARM,
                        Math.max(1, Math.round(max * pct / 100f)), 0);
                beat++;
                if (beat % 20 == 0) scheduleWatch(10000);
                if (beat % 5 == 0 && !MainActivity.visible) bringToFront();
                h.postDelayed(this, 400);
            }
        };

        @Override
        public IBinder onBind(Intent i) {
            return null;
        }

        void scheduleWatch(long ms) {
            try {
                AlarmManager a = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                PendingIntent pi = watchPi(this);
                a.setAlarmClock(new AlarmManager.AlarmClockInfo(System.currentTimeMillis() + ms, pi), pi);
            } catch (Exception e) {
                // ignore
            }
        }

        void bringToFront() {
            try {
                Intent i = new Intent(this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(i);
            } catch (Exception e) {
                // ignore
            }
        }

        @Override
        public int onStartCommand(Intent in, int flags, int id) {
            SharedPreferences sp = getSharedPreferences("p", 0);
            if (in == null && !sp.getBoolean("ring", false)) {
                stopSelf();
                return START_NOT_STICKY;
            }
            if (ringing) return START_STICKY;
            ringing = true;
            sp.edit().putBoolean("ring", true).apply();

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
                    .setContentTitle("Wake up")
                    .setContentText("Scan your object to stop the alarm")
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setOngoing(true)
                    .setContentIntent(fs)
                    .setFullScreenIntent(fs, true)
                    .build();
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);

            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "scanalarm:ring");
                wl.acquire(3600000L);
            } catch (Exception e) {
                // ignore
            }

            am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            try {
                String cur = sp.getString("snd", null);
                Uri u = cur != null ? Uri.parse(cur)
                        : RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
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
                try {
                    if (mp != null) mp.release();
                    mp = new MediaPlayer();
                    mp.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM).build());
                    mp.setDataSource(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM));
                    mp.setLooping(true);
                    mp.prepare();
                    mp.start();
                } catch (Exception e2) {
                    // keep going: the volume loop and notification still run
                }
            }
            scheduleWatch(10000);
            bringToFront();
            h.post(loud);
            return START_STICKY;
        }

        // If the app is swiped away while ringing, start the alarm again right away.
        @Override
        public void onTaskRemoved(Intent rootIntent) {
            if (ringing) scheduleWatch(1000);
            super.onTaskRemoved(rootIntent);
        }

        @Override
        public void onDestroy() {
            try {
                ((AlarmManager) getSystemService(Context.ALARM_SERVICE)).cancel(watchPi(this));
            } catch (Exception e) {
                // ignore
            }
            h.removeCallbacks(loud);
            if (mp != null) {
                try { mp.stop(); } catch (Exception e) { }
                mp.release();
                mp = null;
            }
            try {
                if (wl != null && wl.isHeld()) wl.release();
            } catch (Exception e) {
                // ignore
            }
            ringing = false;
            getSharedPreferences("p", 0).edit().putBoolean("ring", false).apply();
            super.onDestroy();
        }
    }
}
