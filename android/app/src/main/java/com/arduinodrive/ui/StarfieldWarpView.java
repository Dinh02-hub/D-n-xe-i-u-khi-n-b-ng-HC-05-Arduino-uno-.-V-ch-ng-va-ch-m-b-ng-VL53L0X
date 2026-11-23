package com.arduinodrive.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import java.util.Random;

final class StarfieldWarpView extends View {
    private static final int SPACE = 0xff030409;
    private static final int[] COLORS = {0xffeaf5ff, 0xffffe1a0, 0xff8bbdff};
    private static final int STAR_COUNT = 340;
    private static final int TRAIL_COUNT = 144;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Spark[] stars = new Spark[STAR_COUNT];
    private final Spark[] trails = new Spark[TRAIL_COUNT];
    private final float density;
    private Shader blueCloud;
    private Shader goldCloud;
    private float progress;

    StarfieldWarpView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        density = getResources().getDisplayMetrics().density;
        paint.setStrokeCap(Paint.Cap.BUTT);
        Random random = new Random(7206);
        for (int i = 0; i < stars.length; i++) stars[i] = new Spark(random);
        for (int i = 0; i < trails.length; i++) trails[i] = new Spark(random);
    }

    void setProgress(float value) {
        progress = value;
        invalidate();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width == 0 || height == 0) return;
        float size = Math.max(width, height);
        blueCloud = new RadialGradient(width * 0.68f, height * 0.53f, size * 0.51f,
                new int[]{0x40254692, 0x19162762, 0x00162762},
                new float[]{0f, 0.48f, 1f}, Shader.TileMode.CLAMP);
        goldCloud = new RadialGradient(width * 0.29f, height * 0.37f, size * 0.37f,
                0x33a66a36, 0x00a66a36, Shader.TileMode.CLAMP);
        for (Spark trail : trails) {
            float horizontal = width * 0.5f / Math.abs(trail.x);
            float vertical = height * 0.5f / Math.abs(trail.y);
            trail.edge = Math.min(horizontal, vertical);
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        float width = getWidth(), height = getHeight();
        if (width == 0 || height == 0) return;
        int overlay = (int) (255 * smooth(0f, 0.16f, progress));
        canvas.drawColor((overlay << 24) | (SPACE & 0x00ffffff));
        float glow = 1f - smooth(0.77f, 1f, progress);
        if (overlay == 0 || glow == 0f) return;
        paint.setAlpha(Math.round(overlay * glow));
        paint.setShader(blueCloud);
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(goldCloud);
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);

        float intensity = (overlay / 255f) * glow;
        float travel = 0.20f * progress + 1.35f * progress * progress;
        float centerX = width * 0.5f, centerY = height * 0.5f;
        for (Spark star : stars) {
            paint.setColor(COLORS[star.tint]);
            paint.setAlpha((int) (intensity * (25 + 118 * star.light)));
            float drift = 1f + travel * 0.22f * star.light;
            float x = centerX + (star.seed - 0.5f) * width * drift;
            float y = centerY + (star.height - 0.5f) * height * drift;
            float radius = density * (0.26f + 0.82f * star.light * star.light);
            canvas.drawCircle(x, y, radius, paint);
            if (star.light > 0.98f) {
                paint.setAlpha((int) (95 * intensity));
                paint.setStrokeWidth(0.48f * density);
                canvas.drawLine(x - 3f * radius, y, x + 3f * radius, y, paint);
                canvas.drawLine(x, y - 3f * radius, x, y + 3f * radius, paint);
            }
        }

        float acceleration = smooth(0.08f, 0.67f, progress);
        for (Spark trail : trails) {
            float radial = trail.seed + travel * (0.42f + trail.height);
            radial -= (int) radial;
            float reach = radial * 1.42f;
            float length = acceleration * reach * (0.06f + 0.57f * trail.light * trail.light);
            if (reach - length > 1.03f) continue;
            float head = reach * trail.edge;
            float tail = (reach - length) * trail.edge;
            float alpha = intensity * smooth(0.015f, 0.23f, radial)
                    * (1f - smooth(0.78f, 1f, radial)) * (0.16f + 0.84f * trail.light);
            if (alpha < 0.01f) continue;
            float stroke = density * (0.35f + 2.2f * radial * trail.light * trail.light);

            float fromX = centerX + tail * trail.x, fromY = centerY + tail * trail.y;
            float toX = centerX + head * trail.x, toY = centerY + head * trail.y;
            float dx = toX - fromX, dy = toY - fromY;
            paint.setColor(COLORS[trail.tint]);
            paint.setAlpha((int) (alpha * 17));
            paint.setStrokeWidth(stroke * 4.3f);
            canvas.drawLine(fromX + dx * 0.31f, fromY + dy * 0.31f, toX, toY, paint);
            // Tăng độ sáng và bề rộng từ đuôi đến đầu tia.
            for (int part = 0; part < 5; part++) {
                float start = part / 5f;
                float end = (part + 1) / 5f;
                paint.setAlpha(Math.min(255, (int) (alpha * 290 * end * end)));
                paint.setStrokeWidth(stroke * (0.3f + 0.7f * end));
                canvas.drawLine(fromX + dx * start, fromY + dy * start,
                        fromX + dx * end, fromY + dy * end, paint);
            }
        }
    }

    private static float smooth(float start, float end, float value) {
        float t = Math.max(0f, Math.min(1f, (value - start) / (end - start)));
        return t * t * (3f - 2f * t);
    }

    private static final class Spark {
        final float x, y, seed, height, light;
        final int tint;
        float edge;

        Spark(Random random) {
            double radians = random.nextDouble() * (Math.PI * 2);
            x = (float) Math.cos(radians);
            y = (float) Math.sin(radians);
            seed = random.nextFloat();
            height = random.nextFloat();
            light = random.nextFloat();
            float shade = random.nextFloat();
            tint = shade < 0.58f ? 0 : shade < 0.76f ? 1 : 2;
        }
    }
}
