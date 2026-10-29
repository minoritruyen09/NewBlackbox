package top.niunaijun.blackbox.proxy;

import android.app.AlertDialog;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.concurrent.atomic.AtomicInteger;

import top.niunaijun.blackbox.app.BActivityThread;

/**
 * Giu tien trinh cua app ao (p0..p49) o muc "dich vu foreground" de he thong khong dong bang /
 * kill khi ban chuyen app, keo thong bao hoac dua vao cua so nho.
 *
 * - startFor(...) duoc goi tu tien trinh chinh cua BlackBox moi khi mo mot activity app ao.
 * - Service chay trong chinh tien trinh :pN cua app ao (xem AndroidManifest).
 * - Tu dung lai sau IDLE_STOP_DELAY_MS khi khong con activity nao cua app ao.
 */
public class KeepAliveService extends Service {
    private static final String TAG = "KeepAliveService";
    public static final String CHANNEL_ID = "blackbox_keep_alive";
    private static final int NOTIFY_BASE_ID = 0x4B410000;
    private static final long IDLE_STOP_DELAY_MS = 60_000L;

    private static final AtomicInteger sLiveActivities = new AtomicInteger();
    private static volatile KeepAliveService sInstance;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mIdleCheck = new Runnable() {
        @Override
        public void run() {
            if (sLiveActivities.get() <= 0) {
                Log.d(TAG, "idle -> stopSelf");
                stopSelf();
            }
        }
    };

    // ------------------------------------------------------------------ tien trinh chinh

    public static void startFor(Context context, int bpid) {
        try {
            if (context == null || bpid < 0 || bpid >= ProxyManifest.FREE_COUNT) {
                return;
            }
            ensureChannel(context);
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(context.getPackageName(),
                    ProxyManifest.getKeepAliveService(bpid)));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable t) {
            Log.w(TAG, "startFor(" + bpid + ") failed: " + t);
        }
    }

    private static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "App đang chạy trong BlackBox", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Giữ app ảo luôn hoạt động khi bạn chuyển app hoặc dùng cửa sổ nhỏ");
        channel.setShowBadge(false);
        channel.setSound(null, null);
        channel.enableVibration(false);
        nm.createNotificationChannel(channel);
    }

    /** Goi tu MainActivity cua BlackBox: xin mien tru toi uu pin (chi hoi 1 lan). */
    public static void requestBatteryExemptionIfNeeded(final Activity activity) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return;
            }
            final String pkg = activity.getPackageName();
            PowerManager pm = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
            if (pm == null || pm.isIgnoringBatteryOptimizations(pkg)) {
                return;
            }
            final SharedPreferences sp = activity.getSharedPreferences("bb_keep_alive", Context.MODE_PRIVATE);
            if (sp.getBoolean("asked_battery", false)) {
                return;
            }
            sp.edit().putBoolean("asked_battery", true).apply();
            new AlertDialog.Builder(activity)
                    .setTitle("Cho phép chạy nền")
                    .setMessage("Để app bên trong BlackBox không bị hệ thống dừng khi bạn chuyển app, "
                            + "hãy cho phép BlackBox chạy nền không giới hạn pin.")
                    .setNegativeButton("Để sau", null)
                    .setPositiveButton("Cho phép", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            try {
                                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:" + pkg));
                                activity.startActivity(i);
                            } catch (Throwable t) {
                                Log.w(TAG, "open battery settings failed: " + t);
                            }
                        }
                    })
                    .show();
        } catch (Throwable t) {
            Log.w(TAG, "requestBatteryExemptionIfNeeded failed: " + t);
        }
    }

    // ------------------------------------------------------------------ tien trinh app ao

    /** Duoc AppInstrumentation goi khi mot activity cua app ao duoc tao. */
    public static void onGuestActivityCreated() {
        sLiveActivities.incrementAndGet();
        KeepAliveService s = sInstance;
        if (s != null) {
            s.mHandler.removeCallbacks(s.mIdleCheck);
        }
    }

    /** Duoc AppInstrumentation goi khi mot activity cua app ao bi huy. */
    public static void onGuestActivityDestroyed() {
        if (sLiveActivities.decrementAndGet() <= 0) {
            sLiveActivities.set(0);
            KeepAliveService s = sInstance;
            if (s != null) {
                s.mHandler.removeCallbacks(s.mIdleCheck);
                s.mHandler.postDelayed(s.mIdleCheck, IDLE_STOP_DELAY_MS);
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTIFY_BASE_ID + Math.max(0, BActivityThread.getAppPid()), buildNotification());
        } catch (Throwable t) {
            Log.e(TAG, "startForeground failed: " + t);
        }
        mHandler.removeCallbacks(mIdleCheck);
        if (sLiveActivities.get() <= 0) {
            mHandler.postDelayed(mIdleCheck, IDLE_STOP_DELAY_MS);
        }
        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        String pkg = BActivityThread.getAppPackageName();
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("BlackBox")
                .setContentText("Đang giữ " + (pkg != null ? pkg : "app") + " hoạt động")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setShowWhen(false)
                .setOngoing(true);
        return b.build();
    }

    @Override
    public void onDestroy() {
        mHandler.removeCallbacks(mIdleCheck);
        if (sInstance == this) {
            sInstance = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static class P0 extends KeepAliveService {
    }

    public static class P1 extends KeepAliveService {
    }

    public static class P2 extends KeepAliveService {
    }

    public static class P3 extends KeepAliveService {
    }

    public static class P4 extends KeepAliveService {
    }

    public static class P5 extends KeepAliveService {
    }

    public static class P6 extends KeepAliveService {
    }

    public static class P7 extends KeepAliveService {
    }

    public static class P8 extends KeepAliveService {
    }

    public static class P9 extends KeepAliveService {
    }

    public static class P10 extends KeepAliveService {
    }

    public static class P11 extends KeepAliveService {
    }

    public static class P12 extends KeepAliveService {
    }

    public static class P13 extends KeepAliveService {
    }

    public static class P14 extends KeepAliveService {
    }

    public static class P15 extends KeepAliveService {
    }

    public static class P16 extends KeepAliveService {
    }

    public static class P17 extends KeepAliveService {
    }

    public static class P18 extends KeepAliveService {
    }

    public static class P19 extends KeepAliveService {
    }

    public static class P20 extends KeepAliveService {
    }

    public static class P21 extends KeepAliveService {
    }

    public static class P22 extends KeepAliveService {
    }

    public static class P23 extends KeepAliveService {
    }

    public static class P24 extends KeepAliveService {
    }

    public static class P25 extends KeepAliveService {
    }

    public static class P26 extends KeepAliveService {
    }

    public static class P27 extends KeepAliveService {
    }

    public static class P28 extends KeepAliveService {
    }

    public static class P29 extends KeepAliveService {
    }

    public static class P30 extends KeepAliveService {
    }

    public static class P31 extends KeepAliveService {
    }

    public static class P32 extends KeepAliveService {
    }

    public static class P33 extends KeepAliveService {
    }

    public static class P34 extends KeepAliveService {
    }

    public static class P35 extends KeepAliveService {
    }

    public static class P36 extends KeepAliveService {
    }

    public static class P37 extends KeepAliveService {
    }

    public static class P38 extends KeepAliveService {
    }

    public static class P39 extends KeepAliveService {
    }

    public static class P40 extends KeepAliveService {
    }

    public static class P41 extends KeepAliveService {
    }

    public static class P42 extends KeepAliveService {
    }

    public static class P43 extends KeepAliveService {
    }

    public static class P44 extends KeepAliveService {
    }

    public static class P45 extends KeepAliveService {
    }

    public static class P46 extends KeepAliveService {
    }

    public static class P47 extends KeepAliveService {
    }

    public static class P48 extends KeepAliveService {
    }

    public static class P49 extends KeepAliveService {
    }
}
