package com.wmods.wppenhacer.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroupAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.wmods.wppenhacer.activities.MainActivity;

/**
 * Liquid Glass ItemDecoration untuk seluruh layar Preferensi Android.
 * Mengaplikasikan sudut halus 22dp, border specular gradien putih yang hanya
 * membungkus keliling luar kartu,
 * specular top sheen, dan latar belakang liquid glass transparan berkilau yang
 * menyatu dengan wallpaper.
 */
public class CardPreferenceItemDecoration extends RecyclerView.ItemDecoration {

    private final Paint cardBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float radius;
    private final float strokeWidth;
    private final int marginHorizontal;

    // Reusable geometry buffers to eliminate GC allocations per frame in onDraw
    private final Path bgPath = new Path();
    private final Path sheenPath = new Path();
    private final Path strokePath = new Path();
    private final RectF tempRect = new RectF();
    private final float[] radiiAll = new float[8];
    private final float[] radiiTop = new float[8];
    private final float[] radiiBottom = new float[8];
    private final float[] radiiNone = new float[8];

    public CardPreferenceItemDecoration(Context context) {
        float density = context.getResources().getDisplayMetrics().density;

        strokeWidth = 1.2f * density;
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(strokeWidth);

        cardBgPaint.setStyle(Paint.Style.FILL);
        sheenPaint.setStyle(Paint.Style.FILL);
        dividerPaint.setStyle(Paint.Style.FILL);

        radius = 22f * density;
        marginHorizontal = (int) (16 * density);

        // Pre-fill radii arrays once
        radiiAll[0] = radius; radiiAll[1] = radius;
        radiiAll[2] = radius; radiiAll[3] = radius;
        radiiAll[4] = radius; radiiAll[5] = radius;
        radiiAll[6] = radius; radiiAll[7] = radius;

        radiiTop[0] = radius; radiiTop[1] = radius;
        radiiTop[2] = radius; radiiTop[3] = radius;

        radiiBottom[4] = radius; radiiBottom[5] = radius;
        radiiBottom[6] = radius; radiiBottom[7] = radius;
    }

    private Preference getPreference(RecyclerView.Adapter<?> adapter, int position) {
        if (adapter instanceof PreferenceGroupAdapter groupAdapter) {
            if (position >= 0 && position < groupAdapter.getItemCount()) {
                return groupAdapter.getItem(position);
            }
        }
        return null;
    }

    private boolean isCategory(Preference pref) {
        if (pref == null)
            return false;
        return pref instanceof PreferenceCategory || pref.getClass().getSimpleName().contains("Category");
    }

    private boolean isFirstItem(RecyclerView.Adapter<?> adapter, int position) {
        if (position == 0)
            return true;
        Preference prev = getPreference(adapter, position - 1);
        return prev == null || isCategory(prev);
    }

    private boolean isLastItem(RecyclerView.Adapter<?> adapter, int position) {
        int count = adapter != null ? adapter.getItemCount() : 0;
        if (position == count - 1)
            return true;
        Preference next = getPreference(adapter, position + 1);
        return next == null || isCategory(next);
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
            @NonNull RecyclerView.State state) {
        RecyclerView.Adapter<?> adapter = parent.getAdapter();
        if (adapter == null)
            return;

        int position = parent.getChildAdapterPosition(view);
        if (position == RecyclerView.NO_POSITION)
            return;

        float density = parent.getContext().getResources().getDisplayMetrics().density;
        Preference pref = getPreference(adapter, position);

        outRect.left = marginHorizontal;
        outRect.right = marginHorizontal;

        if (isCategory(pref)) {
            // Category Title: jarak bersih dan rapi tanpa ada garis bawah
            outRect.top = (position == 0) ? (int) (10 * density) : (int) (20 * density);
            outRect.bottom = (int) (6 * density);
        } else {
            if (isLastItem(adapter, position)) {
                outRect.bottom = (int) (14 * density);
            } else {
                outRect.bottom = 0;
            }
            outRect.top = 0;
        }
    }

    @Override
    public void onDraw(@NonNull Canvas canvas, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        RecyclerView.Adapter<?> adapter = parent.getAdapter();
        if (adapter == null)
            return;

        boolean hasWallpaper = MainActivity.customWallpaperBitmap != null;
        int childCount = parent.getChildCount();
        float density = parent.getContext().getResources().getDisplayMetrics().density;

        for (int i = 0; i < childCount; i++) {
            View child = parent.getChildAt(i);
            int position = parent.getChildAdapterPosition(child);
            if (position == RecyclerView.NO_POSITION)
                continue;

            Preference pref = getPreference(adapter, position);
            if (isCategory(pref)) {
                // Kategori tidak digambar card atau garis apapun
                continue;
            }

            boolean isFirst = isFirstItem(adapter, position);
            boolean isLast = isLastItem(adapter, position);

            float left = child.getLeft();
            float right = child.getRight();
            float top = child.getTop();
            float bottom = child.getBottom();

            // 1. Liquid Glass Card Background Fill
            if (hasWallpaper) {
                // Frosted translucent dark glass tint with rich contrast
                cardBgPaint.setShader(new LinearGradient(
                        left, top, left, bottom,
                        Color.argb(135, 26, 30, 38),
                        Color.argb(105, 18, 22, 28),
                        Shader.TileMode.CLAMP));
            } else {
                // Deep OLED Glass tone
                cardBgPaint.setShader(new LinearGradient(
                        left, top, left, bottom,
                        Color.argb(230, 24, 28, 34),
                        Color.argb(230, 14, 16, 20),
                        Shader.TileMode.CLAMP));
            }

            updateBackgroundPath(left, top, right, bottom, isFirst, isLast);
            canvas.drawPath(bgPath, cardBgPaint);

            // 2. Specular Top Sheen (efek pantulan kaca di bagian atas kartu seperti di Dashboard)
            if (isFirst) {
                float sheenHeight = Math.min(bottom - top, 26f * density);
                sheenPaint.setShader(new LinearGradient(
                        left, top, left, top + sheenHeight,
                        Color.argb(24, 255, 255, 255),
                        Color.TRANSPARENT,
                        Shader.TileMode.CLAMP));
                updateTopSheenPath(left, top, right, top + sheenHeight);
                canvas.drawPath(sheenPath, sheenPaint);
            }

            // 3. Specular Outer Perimeter Border (White 35% -> White 8%)
            // Hanya gambar perimeter luar kartu, tidak memotong garis di tengah kartu!
            strokePaint.setShader(new LinearGradient(
                    left, top, right, bottom,
                    Color.argb(90, 255, 255, 255),
                    Color.argb(20, 255, 255, 255),
                    Shader.TileMode.CLAMP));

            updateOuterStrokePath(left, top, right, bottom, isFirst, isLast, radius);
            canvas.drawPath(strokePath, strokePaint);

            // 4. Hairline Crystal Divider di dalam kartu antar item
            if (!isLast) {
                float dividerHeight = 0.8f * density;
                float indent = 18f * density;
                dividerPaint.setColor(Color.argb(20, 255, 255, 255));
                canvas.drawRect(left + indent, bottom - dividerHeight, right - indent, bottom, dividerPaint);
            }
        }
    }

    private void updateBackgroundPath(float left, float top, float right, float bottom, boolean isFirst, boolean isLast) {
        bgPath.reset();
        float[] radii;
        if (isFirst && isLast) {
            radii = radiiAll;
        } else if (isFirst) {
            radii = radiiTop;
        } else if (isLast) {
            radii = radiiBottom;
        } else {
            radii = radiiNone;
        }
        tempRect.set(left, top, right, bottom);
        bgPath.addRoundRect(tempRect, radii, Path.Direction.CW);
    }

    private void updateTopSheenPath(float left, float top, float right, float sheenBottom) {
        sheenPath.reset();
        tempRect.set(left, top, right, sheenBottom);
        sheenPath.addRoundRect(tempRect, radiiTop, Path.Direction.CW);
    }

    private void updateOuterStrokePath(float left, float top, float right, float bottom, boolean isFirst,
            boolean isLast, float r) {
        strokePath.reset();
        if (isFirst && isLast) {
            tempRect.set(left, top, right, bottom);
            strokePath.addRoundRect(tempRect, r, r, Path.Direction.CW);
            return;
        }

        if (isFirst) {
            strokePath.moveTo(left, bottom);
            strokePath.lineTo(left, top + r);
            strokePath.quadTo(left, top, left + r, top);
            strokePath.lineTo(right - r, top);
            strokePath.quadTo(right, top, right, top + r);
            strokePath.lineTo(right, bottom);
            return;
        }

        if (!isLast) {
            strokePath.moveTo(left, top);
            strokePath.lineTo(left, bottom);
            strokePath.moveTo(right, top);
            strokePath.lineTo(right, bottom);
            return;
        }

        // isLast
        strokePath.moveTo(left, top);
        strokePath.lineTo(left, bottom - r);
        strokePath.quadTo(left, bottom, left + r, bottom);
        strokePath.lineTo(right - r, bottom);
        strokePath.quadTo(right, bottom, right, bottom - r);
        strokePath.lineTo(right, top);
    }
}
