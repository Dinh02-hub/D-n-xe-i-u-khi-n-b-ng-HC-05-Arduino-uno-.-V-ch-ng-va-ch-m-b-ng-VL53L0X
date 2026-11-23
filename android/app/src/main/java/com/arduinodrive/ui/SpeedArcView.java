package com.arduinodrive.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;
import com.arduinodrive.R;

/** Upper 180-degree gauge. The label and arc share the same animated value. */
public final class SpeedArcView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final float density;
    private final int accent, track;
    private float shownPercent;
    private int targetPercent = -1;
    private boolean available;
    private ValueAnimator animator;
    private TextView valueLabel;

    public SpeedArcView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        accent = BaseActivity.themeColor(context, R.attr.driveAccent);
        track = BaseActivity.themeColor(context, R.attr.driveLine);
        paint.setStrokeCap(Paint.Cap.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); // Label is accessible.
    }

    public void bindValueLabel(TextView label) { valueLabel = label; updateLabel(); }

    private void updateLabel() {
        if (valueLabel == null) return;
        String text = available ? getContext().getString(R.string.speed_value, Math.round(shownPercent))
                : getContext().getString(R.string.speed_unknown);
        if (!text.contentEquals(valueLabel.getText())) valueLabel.setText(text);
    }

    public void setSpeed(int percent, boolean known) {
        percent = Math.max(0, Math.min(100, percent));
        if (targetPercent == percent && available == known) return;
        boolean wasAvailable = available;
        available = known;
        targetPercent = percent;
        if (animator != null) animator.cancel();
        if (known && wasAvailable && isAttachedToWindow()) {
            animator = ValueAnimator.ofFloat(shownPercent, percent);
            animator.setDuration(180);
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(value -> {
                shownPercent = (float) value.getAnimatedValue();
                updateLabel();
                invalidate();
            });
            animator.start();
        } else {
            shownPercent = percent;
            updateLabel();
            invalidate();
        }
        if (!known) updateLabel();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        float inset = 8 * density, centerX = width / 2f, baseline = height - inset;
        float radius = Math.max(0, Math.min(centerX - inset, height - 2 * inset));
        bounds.set(centerX - radius, baseline - radius, centerX + radius, baseline + radius);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3 * density);
        paint.setColor(track);
        canvas.drawArc(bounds, 180, 180, false, paint);
        if (!available) return;
        paint.setColor(accent);
        canvas.drawArc(bounds, 180, shownPercent * 1.8f, false, paint);
        double angle = Math.PI + Math.PI * shownPercent / 100;
        float x = bounds.centerX() + bounds.width() / 2 * (float) Math.cos(angle);
        float y = bounds.centerY() + bounds.height() / 2 * (float) Math.sin(angle);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(track);
        canvas.drawCircle(x, y, 7 * density, paint);
        paint.setColor(accent);
        canvas.drawCircle(x, y, 4 * density, paint);
    }

    @Override protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }
}
