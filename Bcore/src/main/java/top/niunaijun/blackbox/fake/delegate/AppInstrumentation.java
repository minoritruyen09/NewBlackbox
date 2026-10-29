package top.niunaijun.blackbox.fake.delegate;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Window;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import black.android.app.BRActivity;
import black.android.app.BRActivityThread;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.hook.HookManager;
import top.niunaijun.blackbox.fake.hook.IInjectHook;
import top.niunaijun.blackbox.fake.service.HCallbackProxy;
import top.niunaijun.blackbox.fake.service.IActivityClientProxy;
import top.niunaijun.blackbox.proxy.KeepAliveService;
import top.niunaijun.blackbox.utils.HackAppUtils;
import top.niunaijun.blackbox.utils.compat.ActivityCompat;
import top.niunaijun.blackbox.utils.compat.ActivityManagerCompat;
import top.niunaijun.blackbox.utils.compat.ContextCompat;

public final class AppInstrumentation extends BaseInstrumentationDelegate implements IInjectHook {

    private static final String TAG = AppInstrumentation.class.getSimpleName();

    private static AppInstrumentation sAppInstrumentation;

    /**
     * true: khi nguoi dung roi app (Home / Recents / doi app) thi app ao van "dang chay":
     * khong bao onPause / onStop cho no, va moi lan cua so mat focus van bao co focus.
     * Khi nguoi dung quay lai thi khong bao onStart / onResume lap lai.
     * Neu chinh app ao tu mo mot activity khac, hoac activity dang finish thi van chay vong doi binh thuong.
     */
    private static final boolean KEEP_RUNNING_WHEN_LEAVING = true;

    /** App ao vua tu goi startActivity trong khoang nay => la dieu huong ben trong, khong phai roi app. */
    private static final long INTERNAL_LAUNCH_WINDOW_MS = 2000L;

    private static volatile long sInternalLaunchAt = 0L;

    /** Activity vua nhan onUserLeaveHint that su (nguoi dung bam Home / Recents). */
    private static final Set<Activity> sLeaving =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<Activity, Boolean>()));

    /** Activity da bi he thong pause/stop nhung app ao khong duoc bao (van dang "chay"). */
    private static final Set<Activity> sGhost =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<Activity, Boolean>()));

    private static Field sCalledField;
    private static boolean sCalledFieldResolved;

    /** Goi tu hook startActivity cua tien trinh app ao. */
    public static void markInternalLaunch() {
        sInternalLaunchAt = SystemClock.uptimeMillis();
    }

    private static boolean isInternalLaunchRecent() {
        return SystemClock.uptimeMillis() - sInternalLaunchAt < INTERNAL_LAUNCH_WINDOW_MS;
    }

    /**
     * Activity.performStop/performResume... bat buoc mCalled = true sau khi goi onStop/onResume,
     * neu khong se nem SuperNotCalledException. Khi bo qua callback cua app ao ta tu dat mCalled.
     */
    private static synchronized boolean canMarkCalled() {
        if (!sCalledFieldResolved) {
            sCalledFieldResolved = true;
            try {
                Field f = Activity.class.getDeclaredField("mCalled");
                f.setAccessible(true);
                sCalledField = f;
            } catch (Throwable t) {
                Log.w(TAG, "cannot access Activity.mCalled: " + t);
                sCalledField = null;
            }
        }
        return sCalledField != null;
    }

    private static boolean markSuperCalled(Activity activity) {
        if (!canMarkCalled()) {
            return false;
        }
        try {
            sCalledField.setBoolean(activity, true);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "markSuperCalled failed: " + t);
            return false;
        }
    }

    public static AppInstrumentation get() {
        if (sAppInstrumentation == null) {
            synchronized (AppInstrumentation.class) {
                if (sAppInstrumentation == null) {
                    sAppInstrumentation = new AppInstrumentation();
                }
            }
        }
        return sAppInstrumentation;
    }

    public AppInstrumentation() {
    }

    @Override
    public void injectHook() {
        try {
            Instrumentation mInstrumentation = getCurrInstrumentation();
            if (mInstrumentation == this || checkInstrumentation(mInstrumentation))
                return;
            mBaseInstrumentation = (Instrumentation) mInstrumentation;
            BRActivityThread.get(BlackBoxCore.mainThread())._set_mInstrumentation(this);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Instrumentation getCurrInstrumentation() {
        Object currentActivityThread = BlackBoxCore.mainThread();
        return BRActivityThread.get(currentActivityThread).mInstrumentation();
    }

    @Override
    public boolean isBadEnv() {
        return !checkInstrumentation(getCurrInstrumentation());
    }

    private boolean checkInstrumentation(Instrumentation instrumentation) {
        if (instrumentation instanceof AppInstrumentation) {
            return true;
        }
        Class<?> clazz = instrumentation.getClass();
        if (Instrumentation.class.equals(clazz)) {
            return false;
        }
        do {
            assert clazz != null;
            Field[] fields = clazz.getDeclaredFields();
            for (Field field : fields) {
                if (Instrumentation.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    try {
                        Object obj = field.get(instrumentation);
                        if ((obj instanceof AppInstrumentation)) {
                            return true;
                        }
                    } catch (Exception e) {
                        return false;
                    }
                }
            }
            clazz = clazz.getSuperclass();
        } while (!Instrumentation.class.equals(clazz));
        return false;
    }

    private void checkHCallback() {
        HookManager.get().checkEnv(HCallbackProxy.class);
    }

    private void checkActivity(Activity activity) {
        Log.d(TAG, "callActivityOnCreate: " + activity.getClass().getName());
        HackAppUtils.enableQQLogOutput(activity.getPackageName(), activity.getClassLoader());
        checkHCallback();
        HookManager.get().checkEnv(IActivityClientProxy.class);
        ActivityInfo info = BRActivity.get(activity).mActivityInfo();
        ContextCompat.fix(activity);
        ActivityCompat.fix(activity);
        if (info.theme != 0) {
            activity.getTheme().applyStyle(info.theme, true);
        }
        ActivityManagerCompat.setActivityOrientation(activity, info.screenOrientation);
    }

    @Override
    public Application newApplication(ClassLoader cl, String className, Context context) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        ContextCompat.fix(context);

        return super.newApplication(cl, className, context);
    }

    @Override
    public void callActivityOnCreate(Activity activity, Bundle icicle, PersistableBundle persistentState) {
        checkActivity(activity);
        KeepAliveService.onGuestActivityCreated();
        super.callActivityOnCreate(activity, icicle, persistentState);
        installFocusGuard(activity);
    }

    @Override
    public void callActivityOnCreate(Activity activity, Bundle icicle) {
        checkActivity(activity);
        KeepAliveService.onGuestActivityCreated();
        super.callActivityOnCreate(activity, icicle);
        installFocusGuard(activity);
    }

    @Override
    public void callActivityOnUserLeaving(Activity activity) {
        // Van bao onUserLeaveHint cho app ao (nhieu app dung no de tu vao Picture-in-Picture).
        if (KEEP_RUNNING_WHEN_LEAVING && !activity.isFinishing() && !isInternalLaunchRecent()) {
            sLeaving.add(activity);
        }
        super.callActivityOnUserLeaving(activity);
    }

    @Override
    public void callActivityOnPause(Activity activity) {
        if (KEEP_RUNNING_WHEN_LEAVING && sLeaving.remove(activity)
                && !activity.isFinishing() && canMarkCalled()) {
            sGhost.add(activity);
            Log.d(TAG, "keep running, skip onPause: " + activity.getClass().getName());
            return;
        }
        super.callActivityOnPause(activity);
    }

    @Override
    public void callActivityOnStop(Activity activity) {
        if (sGhost.contains(activity)) {
            if (markSuperCalled(activity)) {
                Log.d(TAG, "keep running, skip onStop: " + activity.getClass().getName());
                return;
            }
            // Khong bo qua duoc: bao bu onPause roi onStop de vong doi van nhat quan
            sGhost.remove(activity);
            super.callActivityOnPause(activity);
        }
        super.callActivityOnStop(activity);
    }

    @Override
    public void callActivityOnRestart(Activity activity) {
        if (sGhost.contains(activity) && markSuperCalled(activity)) {
            return;
        }
        super.callActivityOnRestart(activity);
    }

    @Override
    public void callActivityOnStart(Activity activity) {
        if (sGhost.contains(activity) && markSuperCalled(activity)) {
            return;
        }
        super.callActivityOnStart(activity);
    }

    @Override
    public void callActivityOnResume(Activity activity) {
        sLeaving.remove(activity);
        if (sGhost.remove(activity) && markSuperCalled(activity)) {
            Log.d(TAG, "keep running, skip duplicate onResume: " + activity.getClass().getName());
            return;
        }
        super.callActivityOnResume(activity);
    }

    @Override
    public void callActivityOnDestroy(Activity activity) {
        sLeaving.remove(activity);
        if (sGhost.remove(activity)) {
            // Activity bi huy khi dang "chay ngam": bao bu onPause/onStop truoc onDestroy
            try {
                super.callActivityOnPause(activity);
            } catch (Throwable t) {
                Log.w(TAG, "late onPause failed: " + t);
            }
            try {
                super.callActivityOnStop(activity);
            } catch (Throwable t) {
                Log.w(TAG, "late onStop failed: " + t);
            }
        }
        KeepAliveService.onGuestActivityDestroyed();
        super.callActivityOnDestroy(activity);
    }

    /**
     * Boc Window.Callback cua activity de moi lan cua so mat focus thi app ao van nhan
     * onWindowFocusChanged(true). Nhieu game dung su kien nay de tam dung / khoa man hinh.
     */
    private void installFocusGuard(Activity activity) {
        if (!KEEP_RUNNING_WHEN_LEAVING) {
            return;
        }
        try {
            Window window = activity.getWindow();
            if (window == null) {
                return;
            }
            final Window.Callback original = window.getCallback();
            if (original == null || Proxy.isProxyClass(original.getClass())) {
                return;
            }
            Window.Callback guarded = (Window.Callback) Proxy.newProxyInstance(
                    Window.Callback.class.getClassLoader(),
                    new Class<?>[]{Window.Callback.class},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            try {
                                if ("onWindowFocusChanged".equals(method.getName())
                                        && args != null && args.length == 1) {
                                    args = new Object[]{Boolean.TRUE};
                                }
                                return method.invoke(original, args);
                            } catch (InvocationTargetException e) {
                                throw e.getCause() != null ? e.getCause() : e;
                            }
                        }
                    });
            window.setCallback(guarded);
        } catch (Throwable t) {
            Log.w(TAG, "installFocusGuard failed: " + t);
        }
    }

    @Override
    public void callApplicationOnCreate(Application app) {
        checkHCallback();
        top.niunaijun.blackbox.utils.ScreenshotButton.install(app);
        super.callApplicationOnCreate(app);
    }

    public Activity newActivity(ClassLoader cl, String className, Intent intent) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        try {
            return super.newActivity(cl, className, intent);
        } catch (ClassNotFoundException e) {
            return mBaseInstrumentation.newActivity(cl, className, intent);
        }
    }
}
