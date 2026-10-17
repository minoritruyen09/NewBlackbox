package top.niunaijun.blackbox.utils;

import android.app.Activity;
import android.app.Application;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class ScreenshotButton implements Application.ActivityLifecycleCallbacks {

    private static final String TAG = "BBScreenshot";
    private static final String VIEW_TAG = "bb_screenshot_button";
    private static final String PILL_TAG = "bb_save_image_pill";
    private static final int MAX_SIDE = 4096;

    private final Handler mMain = new Handler(Looper.getMainLooper());

    private Runnable mPending;
    private float mDownX, mDownY;
    private View mPill;
    private ViewGroup mPillRoot;
    private Runnable mPillTimeout;

    public static void install(Application app) {
        try {
            app.registerActivityLifecycleCallbacks(new ScreenshotButton());
        } catch (Throwable t) {
            Log.e(TAG, "install failed", t);
        }
    }

    @Override
    public void onActivityResumed(Activity activity) {
        try {
            attach(activity);
            wrapTouch(activity);
        } catch (Throwable t) {
            Log.e(TAG, "attach failed", t);
        }
    }

    @Override
    public void onActivityPaused(Activity activity) {
        cancelPending();
        dismissPill();
    }

    // ---------------------------------------------------------------- nut chup man hinh

    private void attach(final Activity activity) {
        Window window = activity.getWindow();
        if (window == null) {
            return;
        }
        View decor = window.getDecorView();
        if (!(decor instanceof ViewGroup)) {
            return;
        }
        final ViewGroup root = (ViewGroup) decor;
        if (root.findViewWithTag(VIEW_TAG) != null) {
            return;
        }

        float density = activity.getResources().getDisplayMetrics().density;
        int size = (int) (44 * density);

        final TextView button = new TextView(activity);
        button.setTag(VIEW_TAG);
        button.setText("\uD83D\uDCF7");
        button.setTextSize(20);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x99000000);
        button.setBackground(bg);
        button.setElevation(8 * density);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size,
                Gravity.END | Gravity.CENTER_VERTICAL);
        lp.rightMargin = (int) (6 * density);
        root.addView(button, lp);

        final int slop = ViewConfiguration.get(activity).getScaledTouchSlop();
        button.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY, startTx, startTy;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startTx = v.getTranslationX();
                        startTy = v.getTranslationY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX;
                        float dy = e.getRawY() - downY;
                        if (!moved && Math.abs(dx) < slop && Math.abs(dy) < slop) {
                            return true;
                        }
                        moved = true;
                        v.setTranslationX(startTx + dx);
                        v.setTranslationY(startTy + dy);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) {
                            capture(activity, root, button);
                        }
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    private void capture(final Activity activity, final ViewGroup root, final View button) {
        final int w = root.getWidth();
        final int h = root.getHeight();
        if (w <= 0 || h <= 0) {
            toast(activity, "Khong chup duoc: man hinh chua san sang");
            return;
        }
        button.setVisibility(View.INVISIBLE);
        mMain.postDelayed(new Runnable() {
            @Override
            public void run() {
                grab(activity, root, button, w, h);
            }
        }, 120);
    }

    private void grab(final Activity activity, final ViewGroup root, final View button,
                      final int w, final int h) {
        final Bitmap bitmap;
        try {
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        } catch (Throwable t) {
            button.setVisibility(View.VISIBLE);
            toast(activity, "Khong du bo nho de chup");
            return;
        }

        if (Build.VERSION.SDK_INT >= 26) {
            try {
                PixelCopy.request(activity.getWindow(), bitmap, new PixelCopy.OnPixelCopyFinishedListener() {
                    @Override
                    public void onPixelCopyFinished(int result) {
                        button.setVisibility(View.VISIBLE);
                        if (result != PixelCopy.SUCCESS) {
                            drawFallback(root, bitmap);
                        }
                        saveAsync(activity, bitmap, "BlackBox_");
                    }
                }, mMain);
                return;
            } catch (Throwable t) {
                Log.w(TAG, "PixelCopy failed, using draw()", t);
            }
        }
        drawFallback(root, bitmap);
        button.setVisibility(View.VISIBLE);
        saveAsync(activity, bitmap, "BlackBox_");
    }

    private static void drawFallback(View root, Bitmap bitmap) {
        try {
            root.draw(new Canvas(bitmap));
        } catch (Throwable t) {
            Log.e(TAG, "draw fallback failed", t);
        }
    }

    // ---------------------------------------------------------------- giu len anh de luu

    private void wrapTouch(Activity activity) {
        Window window = activity.getWindow();
        if (window == null) {
            return;
        }
        Window.Callback cb = window.getCallback();
        if (cb == null) {
            return;
        }
        if (Proxy.isProxyClass(cb.getClass())
                && Proxy.getInvocationHandler(cb) instanceof TouchHandler) {
            return;
        }
        Window.Callback wrapped = (Window.Callback) Proxy.newProxyInstance(
                ScreenshotButton.class.getClassLoader(),
                new Class[]{Window.Callback.class},
                new TouchHandler(cb, activity, this));
        window.setCallback(wrapped);
    }

    private static final class TouchHandler implements InvocationHandler {
        private final Window.Callback mOrig;
        private final Activity mActivity;
        private final ScreenshotButton mOwner;

        TouchHandler(Window.Callback orig, Activity activity, ScreenshotButton owner) {
            mOrig = orig;
            mActivity = activity;
            mOwner = owner;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("dispatchTouchEvent".equals(method.getName())
                    && args != null && args.length == 1 && args[0] instanceof MotionEvent) {
                try {
                    mOwner.onTouch(mActivity, (MotionEvent) args[0]);
                } catch (Throwable t) {
                    Log.e(TAG, "onTouch failed", t);
                }
            }
            try {
                return method.invoke(mOrig, args);
            } catch (InvocationTargetException e) {
                throw e.getCause() != null ? e.getCause() : e;
            }
        }
    }

    private void onTouch(final Activity activity, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                cancelPending();
                View decor = activity.getWindow().getDecorView();
                if (isOnOurViews(decor, e)) {
                    return;
                }
                dismissPill();
                mDownX = e.getX();
                mDownY = e.getY();
                final float x = mDownX;
                final float y = mDownY;
                mPending = new Runnable() {
                    @Override
                    public void run() {
                        mPending = null;
                        onLongPress(activity, x, y);
                    }
                };
                mMain.postDelayed(mPending, ViewConfiguration.getLongPressTimeout());
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mPending != null) {
                    int slop = ViewConfiguration.get(activity).getScaledTouchSlop();
                    if (Math.abs(e.getX() - mDownX) > slop || Math.abs(e.getY() - mDownY) > slop) {
                        cancelPending();
                    }
                }
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                cancelPending();
                break;
            default:
                break;
        }
    }

    private boolean isOnOurViews(View decor, MotionEvent e) {
        Rect r = new Rect();
        int x = (int) e.getX();
        int y = (int) e.getY();
        if (mPill != null && mPill.getGlobalVisibleRect(r) && r.contains(x, y)) {
            return true;
        }
        if (decor instanceof ViewGroup) {
            View button = decor.findViewWithTag(VIEW_TAG);
            if (button != null && button.getVisibility() == View.VISIBLE
                    && button.getGlobalVisibleRect(r) && r.contains(x, y)) {
                return true;
            }
        }
        return false;
    }

    private void cancelPending() {
        if (mPending != null) {
            mMain.removeCallbacks(mPending);
            mPending = null;
        }
    }

    private void onLongPress(Activity activity, float x, float y) {
        View decor = activity.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) {
            return;
        }
        ViewGroup root = (ViewGroup) decor;
        List<ImageView> candidates = new ArrayList<ImageView>();
        collectImages(root, (int) x, (int) y, new Rect(), candidates);
        if (candidates.isEmpty()) {
            return;
        }
        ImageView target = null;
        for (ImageView iv : candidates) {
            if (bitmapOf(iv.getDrawable()) != null) {
                target = iv;
                break;
            }
        }
        if (target == null) {
            target = candidates.get(0);
        }
        root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        showPill(activity, root, target, x, y);
    }

    private static void collectImages(View v, int x, int y, Rect tmp, List<ImageView> out) {
        if (v == null || v.getVisibility() != View.VISIBLE || v.getAlpha() <= 0f) {
            return;
        }
        Object tag = v.getTag();
        if (VIEW_TAG.equals(tag) || PILL_TAG.equals(tag)) {
            return;
        }
        if (!v.getGlobalVisibleRect(tmp) || !tmp.contains(x, y)) {
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                collectImages(g.getChildAt(i), x, y, tmp, out);
            }
        }
        if (v instanceof ImageView && ((ImageView) v).getDrawable() != null) {
            out.add((ImageView) v);
        }
    }

    private void showPill(final Activity activity, ViewGroup root, ImageView target, float x, float y) {
        dismissPill();
        float density = activity.getResources().getDisplayMetrics().density;

        final WeakReference<ImageView> ref = new WeakReference<ImageView>(target);
        TextView pill = new TextView(activity);
        pill.setTag(PILL_TAG);
        pill.setText("\uD83D\uDCBE  Luu anh (chat luong goc)");
        pill.setTextSize(14);
        pill.setTextColor(Color.WHITE);
        pill.setGravity(Gravity.CENTER);
        int ph = (int) (14 * density);
        int pv = (int) (10 * density);
        pill.setPadding(ph, pv, ph, pv);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(24 * density);
        bg.setColor(0xEE222222);
        pill.setBackground(bg);
        pill.setElevation(12 * density);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        int maxLeft = Math.max(0, root.getWidth() - (int) (230 * density));
        lp.leftMargin = Math.min(Math.max(0, (int) x - (int) (100 * density)), maxLeft);
        lp.topMargin = Math.max((int) (8 * density), (int) y - (int) (70 * density));
        root.addView(pill, lp);

        pill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismissPill();
                saveImage(activity, ref.get());
            }
        });

        mPill = pill;
        mPillRoot = root;
        mPillTimeout = new Runnable() {
            @Override
            public void run() {
                dismissPill();
            }
        };
        mMain.postDelayed(mPillTimeout, 10000);
    }

    private void dismissPill() {
        if (mPillTimeout != null) {
            mMain.removeCallbacks(mPillTimeout);
            mPillTimeout = null;
        }
        if (mPill != null && mPillRoot != null) {
            try {
                mPillRoot.removeView(mPill);
            } catch (Throwable ignored) {
            }
        }
        mPill = null;
        mPillRoot = null;
    }

    private void saveImage(Activity activity, ImageView iv) {
        if (iv == null || iv.getDrawable() == null) {
            toast(activity, "Anh khong con hien thi");
            return;
        }
        Bitmap bitmap;
        try {
            bitmap = extractBitmap(iv);
        } catch (Throwable t) {
            Log.e(TAG, "extract failed", t);
            toast(activity, "Khong lay duoc anh: " + t.getMessage());
            return;
        }
        if (bitmap == null) {
            toast(activity, "Khong lay duoc anh nay (co the la anh dang vector/video)");
            return;
        }
        saveAsync(activity, bitmap, "BlackBox_Image_");
    }

    private static Bitmap extractBitmap(ImageView iv) {
        Drawable d = iv.getDrawable();
        if (d == null) {
            return null;
        }
        Bitmap b = bitmapOf(d);
        if (b != null) {
            if (Build.VERSION.SDK_INT >= 26 && b.getConfig() == Bitmap.Config.HARDWARE) {
                b = b.copy(Bitmap.Config.ARGB_8888, false);
            }
            return b;
        }
        int w = d.getIntrinsicWidth();
        int h = d.getIntrinsicHeight();
        if (w <= 0 || h <= 0) {
            w = iv.getWidth();
            h = iv.getHeight();
        }
        if (w <= 0 || h <= 0) {
            return null;
        }
        float scale = Math.min(1f, MAX_SIDE / (float) Math.max(w, h));
        int bw = Math.max(1, Math.round(w * scale));
        int bh = Math.max(1, Math.round(h * scale));
        Bitmap out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Rect old = d.copyBounds();
        d.setBounds(0, 0, bw, bh);
        d.draw(new Canvas(out));
        d.setBounds(old);
        return out;
    }

    private static Bitmap bitmapOf(Drawable d) {
        if (d == null) {
            return null;
        }
        if (d instanceof BitmapDrawable) {
            Bitmap b = ((BitmapDrawable) d).getBitmap();
            return (b != null && !b.isRecycled()) ? b : null;
        }
        if (d instanceof LayerDrawable) {
            LayerDrawable ld = (LayerDrawable) d;
            for (int i = ld.getNumberOfLayers() - 1; i >= 0; i--) {
                Bitmap b = bitmapOf(ld.getDrawable(i));
                if (b != null) {
                    return b;
                }
            }
            return null;
        }
        Drawable cur = d.getCurrent();
        if (cur != null && cur != d) {
            return bitmapOf(cur);
        }
        return null;
    }

    // ---------------------------------------------------------------- luu file

    private void saveAsync(final Activity activity, final Bitmap bitmap, final String prefix) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String error = null;
                try {
                    save(activity, bitmap, prefix);
                } catch (Throwable t) {
                    Log.e(TAG, "save failed", t);
                    error = String.valueOf(t.getMessage());
                }
                final String msg = error == null
                        ? "Da luu anh " + bitmap.getWidth() + "x" + bitmap.getHeight()
                                + " vao Thu vien (Pictures/BlackBox)"
                        : "Luu anh that bai: " + error;
                toast(activity, msg);
            }
        }, "bb-screenshot-save").start();
    }

    private static void save(Context context, Bitmap bitmap, String prefix) throws Exception {
        String name = prefix + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".png";

        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/BlackBox");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new Exception("MediaStore khong tao duoc file");
            }
            OutputStream os = resolver.openOutputStream(uri);
            if (os == null) {
                throw new Exception("Khong mo duoc file de ghi");
            }
            try {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
            } finally {
                os.close();
            }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES), "BlackBox");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new Exception("Khong tao duoc thu muc " + dir);
            }
            File file = new File(dir, name);
            FileOutputStream os = new FileOutputStream(file);
            try {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
            } finally {
                os.close();
            }
            MediaScannerConnection.scanFile(context, new String[]{file.getAbsolutePath()}, null, null);
        }
    }

    private void toast(final Activity activity, final String msg) {
        mMain.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) { }
    @Override public void onActivityDestroyed(Activity activity) { }
}
