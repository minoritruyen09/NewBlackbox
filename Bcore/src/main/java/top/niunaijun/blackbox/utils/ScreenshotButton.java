package top.niunaijun.blackbox.utils;

import android.app.Activity;
import android.app.Application;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
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
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class ScreenshotButton implements Application.ActivityLifecycleCallbacks {

    private static final String TAG = "BBScreenshot";
    private static final String VIEW_TAG = "bb_screenshot_button";

    private final Handler mMain = new Handler(Looper.getMainLooper());

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
        } catch (Throwable t) {
            Log.e(TAG, "attach failed", t);
        }
    }

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
                        saveAsync(activity, bitmap);
                    }
                }, mMain);
                return;
            } catch (Throwable t) {
                Log.w(TAG, "PixelCopy failed, using draw()", t);
            }
        }
        drawFallback(root, bitmap);
        button.setVisibility(View.VISIBLE);
        saveAsync(activity, bitmap);
    }

    private static void drawFallback(View root, Bitmap bitmap) {
        try {
            root.draw(new Canvas(bitmap));
        } catch (Throwable t) {
            Log.e(TAG, "draw fallback failed", t);
        }
    }

    private void saveAsync(final Activity activity, final Bitmap bitmap) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String error = null;
                try {
                    save(activity, bitmap);
                } catch (Throwable t) {
                    Log.e(TAG, "save failed", t);
                    error = String.valueOf(t.getMessage());
                }
                final String msg = error == null
                        ? "Da luu anh vao Thu vien (Pictures/BlackBox)"
                        : "Luu anh that bai: " + error;
                toast(activity, msg);
            }
        }, "bb-screenshot-save").start();
    }

    private static void save(Context context, Bitmap bitmap) throws Exception {
        String name = "BlackBox_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".png";

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
    @Override public void onActivityPaused(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) { }
    @Override public void onActivityDestroyed(Activity activity) { }
}
