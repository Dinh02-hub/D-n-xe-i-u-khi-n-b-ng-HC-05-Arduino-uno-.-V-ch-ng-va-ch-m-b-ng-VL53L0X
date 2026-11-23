package com.arduinodrive.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import com.arduinodrive.R;

/** One horizontal axis. Owner-pointer tracking lets throttle and steering coexist. */
public final class SteeringJoystickView extends View {
    public interface Listener { void onSteering(int percent); }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Listener listener;
    private int value;
    private int pointer = -1;
    private final float density;

    public SteeringJoystickView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        setClickable(true);
    }
    public void setListener(Listener listener) { this.listener = listener; }
    public void reset() { pointer = -1; update(0); }
    public int getValue() { return value; }
    private void update(int next) {
        next = Math.max(-100, Math.min(100, next));
        if (Math.abs(next) < 5) next = 0;
        if (next == value) return;
        value = next;
        invalidate();
        if (listener != null) listener.onSteering(value);
    }
    private void track(float x) {
        float half = Math.max(1, getWidth() / 2f - 30 * density);
        update(Math.round((x - getWidth() / 2f) / half * 100));
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pointer = event.getPointerId(0);
                getParent().requestDisallowInterceptTouchEvent(true);
                track(event.getX());
                return true;
            case MotionEvent.ACTION_MOVE:
                int index = event.findPointerIndex(pointer);
                if (index >= 0) track(event.getX(index));
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerId(event.getActionIndex()) == pointer) reset();
                return true;
            case MotionEvent.ACTION_UP:
                reset();
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                reset();
                return true;
            default: return true;
        }
    }
    @Override public boolean performClick() { super.performClick(); reset(); return true; }
    @Override public boolean onKeyDown(int code, KeyEvent event) {
        if (!isEnabled()) return super.onKeyDown(code, event);
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            update(value + (code == KeyEvent.KEYCODE_DPAD_LEFT ? -10 : 10)); return true;
        }
        return super.onKeyDown(code, event);
    }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.SeekBar");
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, -100, 100, value));
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, getContext().getString(R.string.center_steer)));
    }
    @Override public boolean performAccessibilityAction(int action, Bundle args) {
        if (isEnabled() && action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) { update(value + 10); return true; }
        if (isEnabled() && action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) { update(value - 10); return true; }
        return super.performAccessibilityAction(action, args);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight(), cy = h / 2f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(BaseActivity.themeColor(getContext(), R.attr.driveSurface));
        canvas.drawRoundRect(0, 0, w, h, 10 * density, 10 * density, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(density);
        paint.setColor(BaseActivity.themeColor(getContext(), R.attr.driveLine));
        canvas.drawRoundRect(density / 2, density / 2, w - density / 2, h - density / 2,
                10 * density, 10 * density, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(BaseActivity.themeColor(getContext(), R.attr.driveLine));
        paint.setStrokeWidth(2 * density);
        canvas.drawLine(30 * density, cy, w - 30 * density, cy, paint);
        paint.setColor(BaseActivity.themeColor(getContext(), R.attr.driveMuted));
        paint.setStrokeWidth(density);
        canvas.drawLine(w / 2, cy - 12 * density, w / 2, cy + 12 * density, paint);
        paint.setTextSize(16 * density); paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("‹", 14 * density, cy + 5 * density, paint);
        canvas.drawText("›", w - 14 * density, cy + 5 * density, paint);
        float x = w / 2f + value / 100f * (w / 2f - 30 * density);
        paint.setColor(BaseActivity.themeColor(getContext(), isEnabled() ? R.attr.driveAccent : R.attr.driveMuted));
        float halfHeight = Math.min(26 * density, h / 2f - 4 * density);
        canvas.drawRoundRect(x - 18 * density, cy - halfHeight, x + 18 * density,
                cy + halfHeight, 7 * density, 7 * density, paint);
        paint.setColor(BaseActivity.themeColor(getContext(), R.attr.driveOnAccent));
        paint.setStrokeWidth(2 * density);
        canvas.drawLine(x - 3 * density, cy - 6 * density, x - 3 * density, cy + 6 * density, paint);
        canvas.drawLine(x + 3 * density, cy - 6 * density, x + 3 * density, cy + 6 * density, paint);
    }
}
