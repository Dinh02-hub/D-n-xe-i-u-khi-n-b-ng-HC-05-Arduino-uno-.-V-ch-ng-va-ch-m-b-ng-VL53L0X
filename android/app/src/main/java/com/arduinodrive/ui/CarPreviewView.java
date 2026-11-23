package com.arduinodrive.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Matrix;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import java.util.ArrayList;
import java.util.List;
import com.arduinodrive.R;
import com.arduinodrive.control.ControlCommand;
import com.arduinodrive.control.MotorState;

/** Hiển thị mô hình UNO trên màn kết nối và sơ đồ hướng xe khi cần. */
public final class CarPreviewView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private boolean hero;
    private ControlCommand command = ControlCommand.stop();
    private MotorState motors = new MotorState(0, 0);

    private static final float BASE_PITCH = 0.50f;
    private static final float INITIAL_YAW = -0.54f;
    private final float density;
    private final int touchSlop;
    private ChipModel chip;
    private boolean ambientMotion, frameScheduled, dragged;
    private int pointerId = -1;
    private long lastFrame;
    private float yaw = INITIAL_YAW, pitch = BASE_PITCH, phase;
    private float offsetX, offsetY, downX, downY, lastX, lastY;
    private final Runnable animateChip = this::updateChip;

    public CarPreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public void setHero(boolean hero) {
        this.hero = hero;
        if (hero && chip == null) chip = new ChipModel();
        setClickable(hero);
        updateAnimation();
        invalidate();
    }

    public void setAmbientMotion(boolean enabled) {
        ambientMotion = enabled;
        if (!enabled) finishDrag();
        updateAnimation();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateAnimation();
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(animateChip);
        frameScheduled = false;
        lastFrame = 0;
        finishDrag();
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateAnimation();
    }

    private boolean shouldAnimate() {
        return hero && ambientMotion && pointerId < 0 && isAttachedToWindow()
                && getWindowVisibility() == VISIBLE;
    }

    private void updateAnimation() {
        if (shouldAnimate()) {
            if (!frameScheduled) {
                frameScheduled = true;
                postOnAnimation(animateChip);
            }
        } else {
            removeCallbacks(animateChip);
            frameScheduled = false;
            lastFrame = 0;
        }
    }

    private void updateChip() {
        frameScheduled = false;
        if (!shouldAnimate()) return;
        long now = System.nanoTime();
        if (lastFrame != 0) {
            float seconds = Math.min((now - lastFrame) * 1e-9f, 0.05f);
            yaw = (yaw + 0.46f * seconds) % ((float) Math.PI * 2f);
            phase = (phase + 1.65f * seconds) % ((float) Math.PI * 2f);
            float settle = (float) Math.exp(-7f * seconds);
            offsetX *= settle;
            offsetY *= settle;
            pitch = BASE_PITCH + (pitch - BASE_PITCH) * settle;
            invalidate();
        }
        lastFrame = now;
        updateAnimation();
    }

    private float chipScale() { return Math.min(getWidth() / 230f, getHeight() / 190f); }
    private float chipY() { return getHeight() / 2f + offsetY + (float) Math.sin(phase) * 6f * chipScale(); }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!hero) return super.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                float x = event.getX(), y = event.getY();
                if (Math.abs(x - (getWidth() / 2f + offsetX)) > 83f * chipScale()
                        || Math.abs(y - chipY()) > 56f * chipScale()) return false;
                // Chỉ giữ thao tác bắt đầu trên chip để vùng ngoài vẫn cuộn được.
                pointerId = event.getPointerId(0);
                downX = lastX = x;
                downY = lastY = y;
                dragged = false;
                getParent().requestDisallowInterceptTouchEvent(true);
                updateAnimation();
                return true;
            case MotionEvent.ACTION_MOVE:
                int index = event.findPointerIndex(pointerId);
                if (index < 0) return pointerId >= 0;
                moveChip(event.getX(index), event.getY(index));
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerId(event.getActionIndex()) == pointerId) {
                    finishDrag();
                    updateAnimation();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (pointerId < 0) return true;
                moveChip(event.getX(), event.getY());
                boolean clicked = !dragged;
                finishDrag();
                if (clicked) performClick();
                updateAnimation();
                return true;
            case MotionEvent.ACTION_CANCEL:
                finishDrag();
                updateAnimation();
                return true;
            default:
                return pointerId >= 0;
        }
    }

    private void moveChip(float x, float y) {
        if (!dragged && Math.hypot(x - downX, y - downY) < touchSlop) return;
        dragged = true;
        float dx = x - lastX, dy = y - lastY;
        lastX = x;
        lastY = y;
        float limitX = Math.max(0f, Math.min(50f * density, getWidth() * 0.5f - 88f * chipScale()));
        float limitY = Math.max(0f, Math.min(28f * density, getHeight() * 0.5f - 76f * chipScale()));
        offsetX = clamp(offsetX + dx, -limitX, limitX);
        offsetY = clamp(offsetY + dy, -limitY, limitY);
        yaw += dx * 0.013f / density;
        pitch = clamp(pitch + dy * 0.009f / density, -0.52f, 0.95f);
        invalidate();
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void finishDrag() {
        if (pointerId < 0) return;
        pointerId = -1;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
    }

    @Override public boolean performClick() {
        super.performClick();
        if (!hero) return false;
        offsetX = offsetY = 0f;
        yaw = INITIAL_YAW;
        pitch = BASE_PITCH;
        invalidate();
        return true;
    }
    public void setState(ControlCommand command, MotorState motors) {
        this.command = command;
        this.motors = motors;
        invalidate();
    }
    private int color(int resource) { return BaseActivity.themeColor(getContext(), resource); }
    private void rect(Canvas canvas, float l, float t, float r, float b, float radius, int color) {
        paint.setColor(color); paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(l, t, r, b, radius, radius, paint);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getHeight() <= 0) return;
        if (hero) { drawHero(canvas); return; }
        canvas.save();
        canvas.translate(getWidth() / 2f, getHeight() / 2f);
        float scale = Math.min(getWidth() / 170f, getHeight() / 145f);
        canvas.scale(scale, scale);
        int accent = color(hero ? R.attr.driveHeroText : R.attr.driveAccent);
        int muted = color(hero ? R.attr.driveHeroMuted : R.attr.driveMuted);
        int soft = color(hero ? R.attr.driveHeroMuted : R.attr.driveAccentSoft);
        // Hai thanh thể hiện công suất riêng của từng bánh.
        rect(canvas, -70, -45, -66, 45, 2, soft);
        rect(canvas, 66, -45, 70, 45, 2, soft);
        float leftBar = Math.abs(motors.left) * 90;
        float rightBar = Math.abs(motors.right) * 90;
        rect(canvas, -70, 45 - leftBar, -66, 45, 2, accent);
        rect(canvas, 66, 45 - rightBar, 70, 45, 2, accent);
        canvas.save();
        canvas.rotate(command.value == 'L' ? -12 : command.value == 'R' ? 12 : 0);
        rect(canvas, -45, -37, -31, -9, 4, muted);
        rect(canvas, 31, -37, 45, -9, 4, muted);
        rect(canvas, -45, 19, -31, 47, 4, muted);
        rect(canvas, 31, 19, 45, 47, 4, muted);
        rect(canvas, -28, -49, 28, 51, 12, accent);
        rect(canvas, -20, -35, 20, -12, 5, color(hero ? R.attr.driveHero : R.attr.driveSurface));
        paint.setColor(color(hero ? R.attr.driveHero : R.attr.driveOnAccent));
        paint.setTextSize(12); paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        canvas.drawText("UNO", 0, 22, paint);
        canvas.restore();
        if (command.value == 'F' || command.value == 'B') {
            float y = command.value == 'F' ? -61 : 65;
            float direction = command.value == 'F' ? -1 : 1;
            arrow.reset(); arrow.moveTo(-7, y - direction * 5);
            arrow.lineTo(0, y + direction * 2); arrow.lineTo(7, y - direction * 5);
            paint.setColor(accent); paint.setStrokeWidth(2.5f); paint.setStyle(Paint.Style.STROKE);
            canvas.drawPath(arrow, paint); paint.setStyle(Paint.Style.FILL);
        }
        canvas.restore();
    }

    private void drawHero(Canvas canvas) {
        float scale = chipScale();
        canvas.save();
        canvas.translate(getWidth() / 2f + offsetX, getHeight() / 2f + 39f * scale);
        canvas.scale(scale, scale);
        chip.drawShadow(canvas, Math.max(0f, Math.min(1f, (chipY() - getHeight() / 2f) / (36f * density) + 0.5f)));
        canvas.restore();

        canvas.save();
        canvas.translate(getWidth() / 2f + offsetX, chipY());
        canvas.scale(scale, scale);
        chip.draw(canvas, yaw, pitch);
        canvas.restore();
    }

    private static final class ChipModel {
        private static final float[] LABEL_PLANE = {0, 0, 0, 74, 112, 74, 112, 0};
        private static final int[][] QUADS = {
                {0, 3, 2, 1}, {4, 5, 6, 7}, {0, 4, 7, 3},
                {1, 2, 6, 5}, {0, 1, 5, 4}, {3, 7, 6, 2}
        };
        private static final float[][] NORMALS = {
                {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}
        };
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path contour = new Path(), label = new Path();
        private final Matrix labelTransform = new Matrix();
        private final Face[] faces;

        ChipModel() {
            List<Face> mesh = new ArrayList<>();
            addBox(mesh, -61, -11, -42, 61, 9, 42, 0xFF484950, false);
            addBox(mesh, -57, 9, -38, 57, 14, 38, 0xFF565861, true);
            addBox(mesh, -59, -13, -40, 59, -10, 40, 0xFF28292F, false);
            for (int pin = 0; pin < 10; pin++) {
                float z = -34f + pin * 7.55f;
                addBox(mesh, -77, -8, z - 1.45f, -60, -3, z + 1.45f, 0xFFB7B8BE, false);
                addBox(mesh, 60, -8, z - 1.45f, 77, -3, z + 1.45f, 0xFFB7B8BE, false);
                addBox(mesh, -83, -13, z - 1.45f, -73, -8, z + 1.45f, 0xFF92949C, false);
                addBox(mesh, 73, -13, z - 1.45f, 83, -8, z + 1.45f, 0xFF92949C, false);
            }
            faces = mesh.toArray(new Face[0]);
            fill.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            fill.setTextSize(21f);
            fill.getTextPath("UNO", 0, 3, 56f - fill.measureText("UNO") / 2f, 44f, label);
            shadow.setShader(new RadialGradient(0, 0, 94f, 0x75000000, 0x00000000, Shader.TileMode.CLAMP));
        }

        private static void addBox(List<Face> faces, float x0, float y0, float z0,
                                   float x1, float y1, float z1, int color, boolean labeledTop) {
            float[][] corners = {
                    {x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0},
                    {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}
            };
            for (int side = 0; side < QUADS.length; side++) {
                float[][] points = new float[4][];
                for (int i = 0; i < 4; i++) points[i] = corners[QUADS[side][i]];
                faces.add(new Face(points, NORMALS[side], color, labeledTop && side == 5));
            }
        }

        void drawShadow(Canvas canvas, float height) {
            canvas.scale(1f + 0.15f * height, 0.26f + 0.04f * height);
            shadow.setAlpha(180 + Math.round(60f * height));
            canvas.drawCircle(0, 0, 94f, shadow);
        }

        void draw(Canvas canvas, float yaw, float pitch) {
            float sy = (float) Math.sin(yaw), cy = (float) Math.cos(yaw);
            float sp = (float) Math.sin(pitch), cp = (float) Math.cos(pitch);
            for (Face face : faces) face.project(sy, cy, sp, cp);

            // Vẽ mặt xa trước. Giữ mảng đã sắp xếp giữa các khung để giảm việc so sánh.
            for (int i = 1; i < faces.length; i++) {
                Face face = faces[i];
                int j = i;
                while (j > 0 && faces[j - 1].depth > face.depth) {
                    faces[j] = faces[j - 1];
                    j--;
                }
                faces[j] = face;
            }
            for (Face face : faces) {
                if (!face.visible) continue;
                float[] p = face.projected;
                contour.rewind();
                contour.moveTo(p[0], p[1]);
                for (int i = 2; i < p.length; i += 2) contour.lineTo(p[i], p[i + 1]);
                contour.close();
                fill.setColor(shade(face.color, face.light));
                canvas.drawPath(contour, fill);
                if (face.labeled && labelTransform.setPolyToPoly(LABEL_PLANE, 0, p, 0, 4)) {
                    canvas.save();
                    canvas.concat(labelTransform);
                    fill.setColor(0xFFDBD9D6);
                    canvas.drawPath(label, fill);
                    canvas.drawCircle(12f, 13f, 2.7f, fill);
                    canvas.restore();
                }
            }
        }

        private static int shade(int color, float light) {
            int r = Math.min(255, Math.round((color >> 16 & 255) * light));
            int g = Math.min(255, Math.round((color >> 8 & 255) * light));
            int b = Math.min(255, Math.round((color & 255) * light));
            return 0xFF000000 | r << 16 | g << 8 | b;
        }

        private static final class Face {
            private static final float CAMERA_DISTANCE = 360f;
            final float[][] points;
            final float[] normal;
            final float[] projected = new float[8];
            final int color;
            final boolean labeled;
            boolean visible;
            float depth, light;

            Face(float[][] points, float[] normal, int color, boolean labeled) {
                this.points = points;
                this.normal = normal;
                this.color = color;
                this.labeled = labeled;
            }

            void project(float sy, float cy, float sp, float cp) {
                depth = 0f;
                for (int i = 0; i < points.length; i++) {
                    float[] point = points[i];
                    float x = cy * point[0] + sy * point[2];
                    float z = -sy * point[0] + cy * point[2];
                    float cameraZ = sp * point[1] + cp * z;
                    float perspective = CAMERA_DISTANCE / (CAMERA_DISTANCE - cameraZ);
                    projected[2 * i] = x * perspective;
                    projected[2 * i + 1] = (sp * z - cp * point[1]) * perspective;
                    depth += cameraZ;
                }
                float ax = projected[2] - projected[0], ay = projected[3] - projected[1];
                float bx = projected[4] - projected[0], by = projected[5] - projected[1];
                visible = ax * by - ay * bx < -0.01f;
                if (!visible) return;
                float nx = cy * normal[0] + sy * normal[2];
                float nz = -sy * normal[0] + cy * normal[2];
                float ny = cp * normal[1] - sp * nz;
                float facing = sp * normal[1] + cp * nz;
                light = 0.58f + 0.51f * Math.max(0f, -0.36f * nx + 0.50f * ny + 0.78f * facing);
            }
        }
    }
}
