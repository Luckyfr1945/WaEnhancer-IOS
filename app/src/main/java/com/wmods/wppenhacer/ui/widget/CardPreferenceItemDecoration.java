package com.wmods.wppenhacer.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroupAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.wmods.wppenhacer.R;

public class CardPreferenceItemDecoration extends RecyclerView.ItemDecoration {

    private final Paint cardBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float radius;
    private final float strokeWidth;
    private final int marginHorizontal;

    public CardPreferenceItemDecoration(Context context) {
        float density = context.getResources().getDisplayMetrics().density;

        cardBgPaint.setColor(ContextCompat.getColor(context, R.color.card_bg));
        cardBgPaint.setStyle(Paint.Style.FILL);

        strokeWidth = 1.2f * density;
        strokePaint.setColor(ContextCompat.getColor(context, R.color.card_border));
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(strokeWidth);

        dividerPaint.setColor(ContextCompat.getColor(context, R.color.card_divider));
        dividerPaint.setStyle(Paint.Style.FILL);

        radius = 18f * density;
        marginHorizontal = (int) (14 * density);
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
        if (pref == null) return false;
        return pref instanceof PreferenceCategory || pref.getClass().getSimpleName().contains("Category");
    }

    private boolean isFirstItem(RecyclerView.Adapter<?> adapter, int position) {
        if (position == 0) return true;
        Preference prev = getPreference(adapter, position - 1);
        return prev == null || isCategory(prev);
    }

    private boolean isLastItem(RecyclerView.Adapter<?> adapter, int position) {
        int count = adapter != null ? adapter.getItemCount() : 0;
        if (position == count - 1) return true;
        Preference next = getPreference(adapter, position + 1);
        return next == null || isCategory(next);
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        RecyclerView.Adapter<?> adapter = parent.getAdapter();
        if (adapter == null) return;

        int position = parent.getChildAdapterPosition(view);
        if (position == RecyclerView.NO_POSITION) return;

        float density = parent.getContext().getResources().getDisplayMetrics().density;
        Preference pref = getPreference(adapter, position);

        outRect.left = marginHorizontal;
        outRect.right = marginHorizontal;

        if (isCategory(pref)) {
            // Category Title: clean compact spacing, absolutely no lines
            outRect.top = (int) (14 * density);
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
        if (adapter == null) return;

        int childCount = parent.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = parent.getChildAt(i);
            int position = parent.getChildAdapterPosition(child);
            if (position == RecyclerView.NO_POSITION) continue;

            Preference pref = getPreference(adapter, position);
            if (isCategory(pref)) {
                // NEVER draw background, border, or divider for category titles!
                continue;
            }

            boolean isFirst = isFirstItem(adapter, position);
            boolean isLast = isLastItem(adapter, position);

            float left = child.getLeft();
            float right = child.getRight();
            float top = child.getTop();
            float bottom = child.getBottom();

            // Draw clean Card Background and Card Border
            Path path = createRoundedCardPath(left, top, right, bottom, isFirst, isLast, radius);
            canvas.drawPath(path, cardBgPaint);
            canvas.drawPath(path, strokePaint);

            // Draw inner divider line ONLY between items inside the same card
            // and NEVER on the last item!
            if (!isLast) {
                float density = parent.getContext().getResources().getDisplayMetrics().density;
                float dividerHeight = 1f * density;
                float indent = 18f * density;
                canvas.drawRect(left + indent, bottom - dividerHeight, right - indent, bottom, dividerPaint);
            }
        }
    }

    private Path createRoundedCardPath(float left, float top, float right, float bottom, boolean isFirst, boolean isLast, float r) {
        Path path = new Path();
        float[] radii;

        if (isFirst && isLast) {
            radii = new float[]{r, r, r, r, r, r, r, r};
        } else if (isFirst) {
            radii = new float[]{r, r, r, r, 0, 0, 0, 0};
        } else if (isLast) {
            radii = new float[]{0, 0, 0, 0, r, r, r, r};
        } else {
            radii = new float[]{0, 0, 0, 0, 0, 0, 0, 0};
        }

        RectF rect = new RectF(left, top, right, bottom);
        path.addRoundRect(rect, radii, Path.Direction.CW);
        return path;
    }
}
