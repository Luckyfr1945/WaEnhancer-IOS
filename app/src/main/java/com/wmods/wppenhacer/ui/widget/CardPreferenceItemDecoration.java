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

            Path bgPath = createBackgroundPath(left, top, right, bottom, isFirst, isLast, radius);
            canvas.drawPath(bgPath, cardBgPaint);

            // 2. Specular Top Sheen (efek pantulan kaca di bagian atas kartu seperti di
            // Dashboard)
            if (isFirst) {
                float sheenHeight = Math.min(bottom - top, 26f * density);
                sheenPaint.setShader(new LinearGradient(
                        left, top, left, top + sheenHeight,
                        Color.argb(24, 255, 255, 255),
                        Color.TRANSPARENT,
                        Shader.TileMode.CLAMP));
                Path sheenPath = createTopSheenPath(left, top, right, top + sheenHeight, radius);
                canvas.drawPath(sheenPath, sheenPaint);
            }

            // 3. Specular Outer Perimeter Border (White 35% -> White 8%)
            // Hanya gambar perimeter luar kartu, tidak memotong garis di tengah kartu!
            strokePaint.setShader(new LinearGradient(
                    left, top, right, bottom,
                    Color.argb(90, 255, 255, 255),
                    Color.argb(20, 255, 255, 255),
                    Shader.TileMode.CLAMP));

            Path strokePath = createOuterStrokePath(left, top, right, bottom, isFirst, isLast, radius);
            if (strokePath != null) {
                canvas.drawPath(strokePath, strokePaint);
            }

            // 4. Hairline Crystal Divider di dalam kartu antar item
            if (!isLast) {
                float dividerHeight = 0.8f * density;
                float indent = 18f * density;
                dividerPaint.setColor(Color.argb(20, 255, 255, 255));
                canvas.drawRect(left + indent, bottom - dividerHeight, right - indent, bottom, dividerPaint);
            }
        }
    }

    private Path createBackgroundPath(float left, float top, float right, float bottom, boolean isFirst, boolean isLast,
            float r) {
        Path path = new Path();
        float[] radii;
        if (isFirst && isLast) {
            radii = new float[] { r, r, r, r, r, r, r, r };
        } else if (isFirst) {
            radii = new float[] { r, r, r, r, 0, 0, 0, 0 };
        } else if (isLast) {
            radii = new float[] { 0, 0, 0, 0, r, r, r, r };
        } else {
            radii = new float[] { 0, 0, 0, 0, 0, 0, 0, 0 };
        }
        RectF rect = new RectF(left, top, right, bottom);
        path.addRoundRect(rect, radii, Path.Direction.CW);
        return path;
    }

    private Path createTopSheenPath(float left, float top, float right, float sheenBottom, float r) {
        Path path = new Path();
        float[] radii = new float[] { r, r, r, r, 0, 0, 0, 0 };
        RectF rect = new RectF(left, top, right, sheenBottom);
        path.addRoundRect(rect, radii, Path.Direction.CW);
        return path;
    }

    private Path createOuterStrokePath(float left, float top, float right, float bottom, boolean isFirst,
            boolean isLast, float r) {
        Path p = new Path();
        if (isFirst && isLast) {
            RectF rect = new RectF(left, top, right, bottom);
            p.addRoundRect(rect, r, r, Path.Direction.CW);
            return p;
        }

        if (isFirst) {
            p.moveTo(left, bottom);
            p.lineTo(left, top + r);
            p.quadTo(left, top, left + r, top);
            p.lineTo(right - r, top);
            p.quadTo(right, top, right, top + r);
            p.lineTo(right, bottom);
            return p;
        }

        if (!isLast) {
            p.moveTo(left, top);
            p.lineTo(left, bottom);
            p.moveTo(right, top);
            p.lineTo(right, bottom);
            return p;
        }

        // isLast
        p.moveTo(left, top);
        p.lineTo(left, bottom - r);
        p.quadTo(left, bottom, left + r, bottom);
        p.lineTo(right - r, bottom);
        p.quadTo(right, bottom, right, bottom - r);
        p.lineTo(right, top);
        return p;
    }
}
