package com.wakeup.scanalarm;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.widget.Toast;
import java.io.FileInputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.opencv.android.OpenCVLoader;
import org.opencv.calib3d.Calib3d;
import org.opencv.core.Core;
import org.opencv.core.DMatch;
import org.opencv.core.KeyPoint;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfDMatch;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Size;
import org.opencv.features2d.BFMatcher;
import org.opencv.features2d.SIFT;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
    static volatile int cvState = 0; // 0 = starting, 1 = ready, 2 = failed
    static final CvMatcher CV = new CvMatcher();
    final ExecutorService cvExec = Executors.newSingleThreadExecutor();

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
        cvExec.execute(new Runnable() {
            public void run() {
                try {
                    if (OpenCVLoader.initLocal()) {
                        CV.load(getFilesDir());
                        cvState = 1;
                    } else {
                        cvState = 2;
                    }
                } catch (Throwable t) {
                    cvState = 2;
                }
            }
        });

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

    // Xiaomi keeps two extra permissions that decide whether the scanner may open by itself.
    String miuiState(int op) {
        try {
            if (!Build.MANUFACTURER.equalsIgnoreCase("xiaomi")) return "null";
            AppOpsManager ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            java.lang.reflect.Method m = AppOpsManager.class.getMethod("checkOpNoThrow",
                    int.class, int.class, String.class);
            int r = (Integer) m.invoke(ops, op, android.os.Process.myUid(), getPackageName());
            if (r == 0) return "true";
            if (r == 1 || r == 2 || r == 5) return "false";
        } catch (Throwable t) {
            // unknown
        }
        return "null";
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
        if (res != RESULT_OK || d == null) return;
        Uri u = null;
        if (req == 7) u = d.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
        else if (req == 8) u = d.getData();
        if (u == null) return;
        String name = null;
        if (req == 8) {
            try {
                getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception e) {
                // not needed, the file is copied below
            }
            name = displayName(u);
        } else {
            try {
                name = RingtoneManager.getRingtone(this, u).getTitle(this);
            } catch (Exception e) {
                // ignore
            }
        }
        // Copy the sound into the app, so it still plays at alarm time even after a restart.
        if (copySound(u)) {
            sp.edit().putString("sndName", name != null ? name : "Custom sound")
                    .putBoolean("sndFile", true).remove("snd").apply();
            Toast.makeText(this, "Alarm sound saved", Toast.LENGTH_SHORT).show();
        } else if (req == 7) {
            sp.edit().putString("snd", u.toString())
                    .putString("sndName", name != null ? name : "Selected ringtone")
                    .putBoolean("sndFile", false).apply();
            Toast.makeText(this, "Alarm sound saved", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "That sound file could not be used. Try another file.",
                    Toast.LENGTH_LONG).show();
        }
    }

    String displayName(Uri u) {
        Cursor c = null;
        try {
            c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception e) {
            // ignore
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    boolean copySound(Uri u) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = getContentResolver().openInputStream(u);
            if (in == null) return false;
            File tmp = new File(getFilesDir(), "alarm_sound.tmp");
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[16384];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 30L * 1024 * 1024) {
                    out.close();
                    out = null;
                    tmp.delete();
                    return false;
                }
                out.write(buf, 0, n);
            }
            out.close();
            out = null;
            if (total == 0) {
                tmp.delete();
                return false;
            }
            File dst = new File(getFilesDir(), "alarm_sound");
            if (dst.exists()) dst.delete();
            return tmp.renameTo(dst);
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception e) {
                // ignore
            }
            try {
                if (out != null) out.close();
            } catch (Exception e) {
                // ignore
            }
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
        if (RingService.ringing) return;
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    void deliverCv(final String id, final String json) {
        runOnUiThread(new Runnable() {
            public void run() {
                web.evaluateJavascript("window.onCv&&onCv('" + id + "'," + json + ")", null);
            }
        });
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
            RingService.stopAt = System.currentTimeMillis();
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
        public int cvState() {
            return cvState;
        }

        @JavascriptInterface
        public int cvCount() {
            return cvState == 1 ? CV.count() : 0;
        }

        // Adds one registration photo. The answer comes back through window.onCv.
        @JavascriptInterface
        public void cvAdd(final String id, final String b64, final boolean reset) {
            cvExec.execute(new Runnable() {
                public void run() {
                    String r;
                    try {
                        r = CV.add(Base64.decode(b64, Base64.DEFAULT), getFilesDir(), reset);
                    } catch (Throwable t) {
                        r = "{\"ok\":false,\"msg\":\"The recognition engine reported an error. Try again.\"}";
                    }
                    deliverCv(id, r);
                }
            });
        }

        // Compares one camera frame with the registered object.
        @JavascriptInterface
        public void cvMatch(final String id, final String b64) {
            cvExec.execute(new Runnable() {
                public void run() {
                    String r;
                    try {
                        r = CV.detect(Base64.decode(b64, Base64.DEFAULT));
                    } catch (Throwable t) {
                        r = "{\"ok\":false,\"msg\":\"error\"}";
                    }
                    deliverCv(id, r);
                }
            });
        }

        @JavascriptInterface
        public void pickFile() {
            runOnUiThread(new Runnable() {
                public void run() {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("audio/*");
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    try {
                        startActivityForResult(i, 8);
                    } catch (Exception e) {
                        // ignore
                    }
                }
            });
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
                String named = sp.getString("sndName", null);
                if (named != null) return named;
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
                    + ",\"fsi\":" + fsi + ",\"bgstart\":" + miuiState(10021)
                    + ",\"lockscr\":" + miuiState(10020)
                    + ",\"battery\":" + ((PowerManager) getSystemService(Context.POWER_SERVICE))
                    .isIgnoringBatteryOptimizations(getPackageName()) + "}";
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
        public void askBattery() {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        try {
                            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                        } catch (Exception e2) {
                            openSettingsPage();
                        }
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
                            new ImageLabelerOptions.Builder().setConfidenceThreshold(0.1f).build());
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

    static class Feat {
        Mat desc;
        org.opencv.core.Point[] pts;
        int w, h;
    }

    // Recognizes the registered object with SIFT feature matching and a geometric check (RANSAC).
    // Light, distance, rotation and the background around the object do not matter.
    static class CvMatcher {
        SIFT sift;
        SIFT siftFast;
        BFMatcher bf;
        CLAHE clahe;
        final List<Feat> raw = new ArrayList<Feat>();
        final List<Feat> use = new ArrayList<Feat>();
        final List<Feat> subs = new ArrayList<Feat>();
        int th = 18;

        void ensure() {
            if (sift != null) return;
            sift = SIFT.create(1500, 3, 0.03, 10, 1.6);
            siftFast = SIFT.create(700, 3, 0.03, 10, 1.6);
            bf = BFMatcher.create(Core.NORM_L2, false);
            clahe = Imgproc.createCLAHE(2.0, new Size(8, 8));
        }

        Mat gray(byte[] jpg, int maxSide) {
            MatOfByte mb = new MatOfByte(jpg);
            Mat g = Imgcodecs.imdecode(mb, Imgcodecs.IMREAD_GRAYSCALE);
            mb.release();
            if (g == null || g.empty()) return null;
            int m = Math.max(g.cols(), g.rows());
            if (m > maxSide) {
                double s = (double) maxSide / m;
                Mat r = new Mat();
                Imgproc.resize(g, r, new Size(), s, s, Imgproc.INTER_AREA);
                g.release();
                g = r;
            }
            Mat e = new Mat();
            clahe.apply(g, e);
            g.release();
            return e;
        }

        Feat features(SIFT d, Mat g) {
            MatOfKeyPoint kp = new MatOfKeyPoint();
            Mat desc = new Mat();
            d.detectAndCompute(g, new Mat(), kp, desc);
            KeyPoint[] a = kp.toArray();
            kp.release();
            Feat f = new Feat();
            f.desc = desc;
            f.w = g.cols();
            f.h = g.rows();
            f.pts = new org.opencv.core.Point[a.length];
            for (int i = 0; i < a.length; i++) f.pts[i] = a[i].pt;
            return f;
        }

        boolean sane(Mat hm, int w, int h) {
            double[] a = new double[9];
            hm.get(0, 0, a);
            double det = a[0] * a[4] - a[1] * a[3];
            double sc = Math.sqrt(Math.abs(det));
            if (!(sc > 0.2 && sc < 6.0)) return false;
            if (Math.abs(a[6]) > 0.01 || Math.abs(a[7]) > 0.01) return false;
            MatOfPoint2f c = new MatOfPoint2f(new org.opencv.core.Point(0, 0),
                    new org.opencv.core.Point(w, 0), new org.opencv.core.Point(w, h),
                    new org.opencv.core.Point(0, h));
            MatOfPoint2f o = new MatOfPoint2f();
            Core.perspectiveTransform(c, o, hm);
            org.opencv.core.Point[] p = o.toArray();
            c.release();
            o.release();
            if (p.length != 4) return false;
            double sign = 0;
            for (int i = 0; i < 4; i++) {
                org.opencv.core.Point p0 = p[i];
                org.opencv.core.Point p1 = p[(i + 1) % 4];
                org.opencv.core.Point p2 = p[(i + 2) % 4];
                double cr = (p1.x - p0.x) * (p2.y - p1.y) - (p1.y - p0.y) * (p2.x - p1.x);
                if (cr == 0) return false;
                if (sign == 0) sign = cr;
                else if (sign * cr < 0) return false;
            }
            return true;
        }

        // Number of points that agree on one consistent position of the object.
        int match(Feat q, Feat t, boolean[] markT) {
            if (q.pts.length < 10 || t.pts.length < 10 || q.desc.empty() || t.desc.empty()) return 0;
            List<MatOfDMatch> knn = new ArrayList<MatOfDMatch>();
            bf.knnMatch(q.desc, t.desc, knn, 2);
            List<org.opencv.core.Point> src = new ArrayList<org.opencv.core.Point>();
            List<org.opencv.core.Point> dst = new ArrayList<org.opencv.core.Point>();
            List<Integer> idx = new ArrayList<Integer>();
            for (MatOfDMatch m : knn) {
                DMatch[] d = m.toArray();
                if (d.length >= 2 && d[0].distance < 0.8f * d[1].distance) {
                    src.add(t.pts[d[0].trainIdx]);
                    dst.add(q.pts[d[0].queryIdx]);
                    idx.add(d[0].trainIdx);
                }
                m.release();
            }
            if (src.size() < 10) return 0;
            MatOfPoint2f s = new MatOfPoint2f();
            s.fromList(src);
            MatOfPoint2f dd = new MatOfPoint2f();
            dd.fromList(dst);
            Mat mask = new Mat();
            Mat hm = Calib3d.findHomography(s, dd, Calib3d.RANSAC, 3.0, mask);
            int cnt = 0;
            try {
                if (hm != null && !hm.empty() && sane(hm, t.w, t.h)) {
                    byte[] mk = new byte[(int) mask.total()];
                    mask.get(0, 0, mk);
                    for (int i = 0; i < mk.length; i++) {
                        if (mk[i] != 0) {
                            cnt++;
                            if (markT != null) markT[idx.get(i)] = true;
                        }
                    }
                }
            } finally {
                s.release();
                dd.release();
                mask.release();
                if (hm != null) hm.release();
            }
            return cnt;
        }

        Feat subset(Feat t, boolean[] mark, int cnt) {
            Feat s = new Feat();
            s.w = t.w;
            s.h = t.h;
            s.pts = new org.opencv.core.Point[cnt];
            s.desc = new Mat(cnt, t.desc.cols(), t.desc.type());
            int k = 0;
            for (int i = 0; i < mark.length; i++) {
                if (mark[i]) {
                    t.desc.row(i).copyTo(s.desc.row(k));
                    s.pts[k] = t.pts[i];
                    k++;
                }
            }
            return s;
        }

        // Keeps only the points that appear in several registration photos (the object itself),
        // and sets the acceptance level from how well the photos match each other.
        void rebuild() {
            for (Feat s : subs) s.desc.release();
            subs.clear();
            use.clear();
            int n = raw.size();
            int minPair = Integer.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                Feat t = raw.get(i);
                boolean[] mark = new boolean[t.pts.length];
                boolean ok = false;
                for (int j = 0; j < n; j++) {
                    if (i == j) continue;
                    int c = match(raw.get(j), t, mark);
                    if (c >= 12) ok = true;
                    if (c < minPair) minPair = c;
                }
                int cnt = 0;
                for (boolean b : mark) if (b) cnt++;
                if (ok && cnt >= 40) {
                    Feat s = subset(t, mark, cnt);
                    subs.add(s);
                    use.add(s);
                } else {
                    use.add(t);
                }
            }
            th = minPair == Integer.MAX_VALUE ? 18
                    : Math.max(14, Math.min(22, Math.round(minPair * 0.25f)));
        }

        void clear() {
            for (Feat f : raw) f.desc.release();
            for (Feat s : subs) s.desc.release();
            raw.clear();
            use.clear();
            subs.clear();
        }

        static void writeFile(File f, byte[] b) {
            try {
                FileOutputStream o = new FileOutputStream(f);
                try {
                    o.write(b);
                } finally {
                    o.close();
                }
            } catch (Exception e) {
                // ignore
            }
        }

        static byte[] readFile(File f) {
            try {
                FileInputStream in = new FileInputStream(f);
                try {
                    byte[] b = new byte[(int) f.length()];
                    int off = 0;
                    while (off < b.length) {
                        int n = in.read(b, off, b.length - off);
                        if (n < 0) break;
                        off += n;
                    }
                    return b;
                } finally {
                    in.close();
                }
            } catch (Exception e) {
                return null;
            }
        }

        synchronized void load(File dir) {
            ensure();
            clear();
            for (int k = 0; k < 3; k++) {
                File f = new File(dir, "cvref_" + k + ".jpg");
                if (!f.exists()) break;
                byte[] b = readFile(f);
                if (b == null) break;
                Mat g = gray(b, 640);
                if (g == null) break;
                raw.add(features(sift, g));
                g.release();
            }
            rebuild();
        }

        synchronized int count() {
            return raw.size();
        }

        synchronized String add(byte[] jpg, File dir, boolean reset) {
            ensure();
            Mat g = gray(jpg, 640);
            if (g == null) return "{\"ok\":false,\"msg\":\"The picture could not be read.\"}";
            Feat f = features(sift, g);
            g.release();
            int kp = f.pts.length;
            if (kp < 150) {
                f.desc.release();
                return "{\"ok\":false,\"kp\":" + kp + ",\"msg\":\"Not enough detail to recognize this object. "
                        + "Move closer, add light, or choose an object with patterns, text or a logo.\"}";
            }
            if (reset) {
                clear();
                for (int k = 0; k < 3; k++) new File(dir, "cvref_" + k + ".jpg").delete();
            } else {
                if (raw.size() >= 3) {
                    f.desc.release();
                    return "{\"ok\":false,\"msg\":\"You can register at most 3 photos.\"}";
                }
                if (!raw.isEmpty()) {
                    int best = 0;
                    for (Feat o : raw) best = Math.max(best, match(f, o, null));
                    if (best < 14) {
                        f.desc.release();
                        return "{\"ok\":false,\"kp\":" + kp + ",\"msg\":\"This photo does not match your earlier photos. "
                                + "Make sure it is the same object and keep it inside the frame.\"}";
                    }
                }
            }
            writeFile(new File(dir, "cvref_" + raw.size() + ".jpg"), jpg);
            raw.add(f);
            rebuild();
            return "{\"ok\":true,\"photos\":" + raw.size() + ",\"kp\":" + kp + ",\"th\":" + th + "}";
        }

        // Fast pass first (small picture); a thorough pass only when the fast one is not sure.
        synchronized String detect(byte[] jpg) {
            ensure();
            if (use.isEmpty()) return "{\"ok\":false,\"msg\":\"No object registered.\"}";
            long t0 = System.currentTimeMillis();
            int best = 0;
            int kpq = 0;
            for (int pass = 0; pass < 2 && best < th; pass++) {
                Mat g = gray(jpg, pass == 0 ? 480 : 720);
                if (g == null) break;
                Feat q = features(pass == 0 ? siftFast : sift, g);
                g.release();
                kpq = Math.max(kpq, q.pts.length);
                for (Feat t : use) best = Math.max(best, match(q, t, null));
                q.desc.release();
            }
            return "{\"ok\":true,\"inl\":" + best + ",\"th\":" + th + ",\"kp\":" + kpq
                    + ",\"ms\":" + (System.currentTimeMillis() - t0) + "}";
        }
    }

    // Rings with the screen off, keeps the alarm volume where you set it, and keeps coming back
    // until the object is scanned. Stopping is made safe against late restarts.
    public static class RingService extends Service {
        static volatile boolean ringing = false;
        static volatile long stopAt = 0;
        BroadcastReceiver unlock;
        long lastFront = 0;
        MediaPlayer mp;
        AudioManager am;
        PowerManager.WakeLock wl;
        int beat = 0;
        Handler h = new Handler(Looper.getMainLooper());
        Runnable loud = new Runnable() {
            public void run() {
                if (!ringing) {
                    shutdown();
                    return;
                }
                SharedPreferences sp = getSharedPreferences("p", 0);
                int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
                int pct = sp.getInt("vol", 100);
                am.setStreamVolume(AudioManager.STREAM_ALARM,
                        Math.max(1, Math.round(max * pct / 100f)), 0);
                beat++;
                if (beat % 20 == 0) scheduleWatch(10000);
                if (!MainActivity.visible && System.currentTimeMillis() - lastFront > 800) bringToFront();
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
                PendingIntent show = PendingIntent.getActivity(this, 2,
                        new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
                a.setAlarmClock(new AlarmManager.AlarmClockInfo(System.currentTimeMillis() + ms, show), pi);
            } catch (Exception e) {
                // ignore
            }
        }

        void registerUnlock() {
            if (unlock != null) return;
            unlock = new BroadcastReceiver() {
                public void onReceive(Context c, Intent i) {
                    if (!ringing) return;
                    bringToFront();
                    h.postDelayed(new Runnable() {
                        public void run() {
                            if (ringing && !MainActivity.visible) bringToFront();
                        }
                    }, 400);
                    h.postDelayed(new Runnable() {
                        public void run() {
                            if (ringing && !MainActivity.visible) bringToFront();
                        }
                    }, 1200);
                }
            };
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_USER_PRESENT);
            f.addAction(Intent.ACTION_SCREEN_ON);
            try {
                registerReceiver(unlock, f);
            } catch (Exception e) {
                unlock = null;
            }
        }

        void bringToFront() {
            lastFront = System.currentTimeMillis();
            try {
                Intent i = new Intent(this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(i);
            } catch (Exception e) {
                // ignore
            }
        }

        Notification buildNote() {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel("alarm", "Alarm",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
            PendingIntent fs = PendingIntent.getActivity(this, 3,
                    new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE);
            return new Notification.Builder(this, "alarm")
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("Wake up")
                    .setContentText("Scan your object to stop the alarm")
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setOngoing(true)
                    .setContentIntent(fs)
                    .setFullScreenIntent(fs, true)
                    .build();
        }

        void startSound(SharedPreferences sp) {
            try {
                File f = new File(getFilesDir(), "alarm_sound");
                String cur = sp.getString("snd", null);
                Uri u = cur != null ? Uri.parse(cur)
                        : RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
                mp = new MediaPlayer();
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
                if (sp.getBoolean("sndFile", false) && f.exists()) mp.setDataSource(f.getAbsolutePath());
                else mp.setDataSource(this, u);
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
        }

        @Override
        public int onStartCommand(Intent in, int flags, int id) {
            SharedPreferences sp = getSharedPreferences("p", 0);
            if (in == null && !sp.getBoolean("ring", false)) {
                stopSelf();
                return START_NOT_STICKY;
            }
            try {
                startForeground(1, buildNote(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } catch (Exception e) {
                // continue without the foreground state
            }
            // A start that arrives right after the object was scanned is a leftover: ignore it.
            if (System.currentTimeMillis() - stopAt < 3000) {
                shutdown();
                return START_NOT_STICKY;
            }
            if (ringing) return START_STICKY;
            ringing = true;
            sp.edit().putBoolean("ring", true).apply();

            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "scanalarm:ring");
                wl.acquire(3600000L);
            } catch (Exception e) {
                // ignore
            }
            am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            startSound(sp);
            scheduleWatch(10000);
            bringToFront();
            registerUnlock();
            h.post(loud);
            return START_STICKY;
        }

        // If the app is swiped away while ringing, start the alarm again right away.
        @Override
        public void onTaskRemoved(Intent rootIntent) {
            if (ringing) scheduleWatch(1000);
            super.onTaskRemoved(rootIntent);
        }

        void cleanup() {
            if (unlock != null) {
                try {
                    unregisterReceiver(unlock);
                } catch (Exception e) {
                    // ignore
                }
                unlock = null;
            }
            try {
                ((AlarmManager) getSystemService(Context.ALARM_SERVICE)).cancel(watchPi(this));
            } catch (Exception e) {
                // ignore
            }
            h.removeCallbacks(loud);
            if (mp != null) {
                try { mp.stop(); } catch (Exception e) { }
                try { mp.release(); } catch (Exception e) { }
                mp = null;
            }
            try {
                if (wl != null && wl.isHeld()) wl.release();
            } catch (Exception e) {
                // ignore
            }
            ringing = false;
            getSharedPreferences("p", 0).edit().putBoolean("ring", false).apply();
        }

        void shutdown() {
            cleanup();
            try {
                stopForeground(true);
            } catch (Exception e) {
                // ignore
            }
            stopSelf();
        }

        @Override
        public void onDestroy() {
            cleanup();
            super.onDestroy();
        }
    }
}
