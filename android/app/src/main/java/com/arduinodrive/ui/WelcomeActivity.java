package com.arduinodrive.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import com.arduinodrive.R;

public final class WelcomeActivity extends BaseActivity {
    private static final int INTRO_MS = 3500;
    private static final int TITLE_READY_MS = 2940;
    private static final int WARP_MS = 860;
    private static final int STATIC_REVEAL_MS = 240;
    private static final String INTRO_POSITION = "intro_position";
    private final PathInterpolator settle = new PathInterpolator(0.22f, 1f, 0.36f, 1f);
    private final PathInterpolator camera = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    private FrameLayout artwork;
    private View copy;
    private View start;
    private FrameLayout instrument;
    private NeedleGauge gauge;
    private StudioReveal light;
    private ValueAnimator intro;
    private ValueAnimator warp;
    private StarfieldWarpView starfield;
    private int introTime;
    private boolean opening;
    private final Runnable showStart = () -> renderIntro(INTRO_MS);
    private final Runnable showConnection = this::openConnection;
    private float dp;
    private float artworkOffset;

    @Override protected int activityTheme(String theme) { return R.style.Theme_ArduinoDrive_Welcome; }
    @Override protected boolean useLightSystemBars() { return false; }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_welcome);
        applyInsets(false);
        dp = getResources().getDisplayMetrics().density;
        artwork = findViewById(R.id.welcomeArtwork);
        light = new StudioReveal(getColor(R.color.welcome_background));
        artwork.getOverlay().add(light);
        artwork.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                light.setBounds(0, 0, right - left, bottom - top));
        copy = findViewById(R.id.welcomeCopy);
        start = findViewById(R.id.welcomeStartButton);
        instrument = findViewById(R.id.welcomeInstrument);
        View stage = findViewById(R.id.welcomeStage);
        gauge = new NeedleGauge(this);
        instrument.addView(gauge, new FrameLayout.LayoutParams(-1, -1));
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            LinearLayout content = findViewById(R.id.welcomeContent);
            View actions = findViewById(R.id.welcomeActions);
            content.setOrientation(LinearLayout.HORIZONTAL);
            content.setGravity(android.view.Gravity.CENTER_VERTICAL);
            stage.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1.35f));
            actions.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            actions.setPaddingRelative((int) (16 * dp), (int) (16 * dp),
                    (int) (32 * dp), (int) (16 * dp));
        } else {
            stage.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                artworkOffset = Math.max(0, view.getHeight() - artwork.getHeight()) * 0.27f;
                artwork.setTranslationY(artworkOffset);
                instrument.setTranslationY(Math.max(0, artwork.getTop() + artworkOffset
                        - instrument.getTop() - 16f * dp));
            });
        }
        int restoredTime = state == null ? 0 : state.getInt(INTRO_POSITION);
        if (restoredTime < INTRO_MS && !systemAnimationsEnabled()) restoredTime = TITLE_READY_MS;
        renderIntro(restoredTime);
        start.setOnClickListener(view -> beginTransition());
    }

    @Override protected void onResume() {
        super.onResume();
        if (introTime == INTRO_MS) return;
        if (!systemAnimationsEnabled()) {
            if (intro != null) {
                intro.cancel();
                intro = null;
            }
            renderIntro(TITLE_READY_MS);
            start.postDelayed(showStart, STATIC_REVEAL_MS);
            return;
        }
        if (intro != null) {
            intro.resume();
            return;
        }
        intro = ValueAnimator.ofInt(0, INTRO_MS);
        intro.setDuration(INTRO_MS);
        intro.setInterpolator(new LinearInterpolator());
        intro.addUpdateListener(animation -> renderIntro((int) animation.getAnimatedValue()));
        intro.setCurrentPlayTime(introTime);
        intro.start();
    }

    private static float progress(int time, int start, int end) {
        return Math.max(0f, Math.min(1f, (time - start) / (float) (end - start)));
    }

    private void renderIntro(int time) {
        introTime = time;
        float reveal = settle.getInterpolation(progress(time, 0, 1350));
        float move = camera.getInterpolation(progress(time, 350, 2850));
        artwork.setAlpha(0.09f + 0.91f * reveal);
        artwork.setScaleX(1f + 0.055f * (1f - move));
        artwork.setScaleY(artwork.getScaleX());
        artwork.setTranslationX(-14f * dp * (1f - move));
        artwork.setTranslationY(artworkOffset + 8f * dp * (1f - move));
        light.setReveal(camera.getInterpolation(progress(time, 0, 1350)));

        boolean gaugeVisible = time >= 900 && time < 2760;
        int visibility = gaugeVisible ? View.VISIBLE : View.INVISIBLE;
        if (instrument.getVisibility() != visibility) instrument.setVisibility(visibility);
        if (gaugeVisible) {
            float appear = progress(time, 900, 1200);
            float disappear = progress(time, 2370, 2760);
            instrument.setAlpha(appear * (1f - disappear));
            float sweep = time < 1720 ? progress(time, 1100, 1720) : 1f - progress(time, 1720, 2370);
            gauge.setSweep(camera.getInterpolation(sweep));
        }

        float title = settle.getInterpolation(progress(time, 2080, TITLE_READY_MS));
        copy.setAlpha(title);
        copy.setTranslationY(12f * dp * (1f - title));
        float button = settle.getInterpolation(progress(time, TITLE_READY_MS, INTRO_MS));
        int buttonVisibility = button > 0f ? View.VISIBLE : View.INVISIBLE;
        if (start.getVisibility() != buttonVisibility) start.setVisibility(buttonVisibility);
        start.setAlpha(button);
        start.setTranslationY(10f * dp * (1f - button));
        start.setEnabled(time >= INTRO_MS);
    }

    private void beginTransition() {
        if (opening || !start.isEnabled()) return;
        opening = true;
        start.setEnabled(false);
        if (intro != null) intro.cancel();
        instrument.setVisibility(View.INVISIBLE);
        light.setReveal(1f);
        starfield = new StarfieldWarpView(this);
        addContentView(starfield, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (!systemAnimationsEnabled()) {
            starfield.setProgress(0.65f);
            start.postDelayed(showConnection, STATIC_REVEAL_MS);
            return;
        }
        warp = ValueAnimator.ofFloat(0f, 1f);
        warp.setDuration(WARP_MS);
        warp.setInterpolator(new LinearInterpolator());
        warp.addUpdateListener(animation -> starfield.setProgress(animation.getAnimatedFraction()));
        warp.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (warp != animation) return;
                warp = null;
                openConnection();
            }
        });
        warp.start();
    }

    private static final class StudioReveal extends Drawable {
        private final Paint paint = new Paint();
        private final Matrix shift = new Matrix();
        private final int shade;
        private float reveal;

        StudioReveal(int shade) { this.shade = shade; }

        void setReveal(float progress) {
            if (reveal == progress) return;
            reveal = progress;
            invalidateSelf();
        }

        @Override protected void onBoundsChange(Rect bounds) {
            if (bounds.width() == 0) return;
            paint.setShader(new LinearGradient(0f, 0f, bounds.width() * 0.38f, 0f,
                    shade & 0x00FFFFFF, shade, Shader.TileMode.CLAMP));
        }

        @Override public void draw(Canvas canvas) {
            Shader gradient = paint.getShader();
            if (gradient == null || reveal == 1f) return;
            // Dịch vùng tối để ánh sáng studio hé lộ thân xe từ trái sang phải.
            shift.setTranslate(getBounds().width() * (1.38f * reveal - 0.38f), 0f);
            gradient.setLocalMatrix(shift);
            canvas.drawRect(getBounds(), paint);
        }

        @Override public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override public void setColorFilter(ColorFilter filter) {
            paint.setColorFilter(filter);
            invalidateSelf();
        }

        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private void openConnection() {
        startActivity(new Intent(this, ConnectionActivity.class), screenTransition());
        finish();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(INTRO_POSITION, introTime);
    }

    @Override protected void onPause() {
        start.removeCallbacks(showStart);
        start.removeCallbacks(showConnection);
        if (intro != null) {
            if (isFinishing() || introTime == INTRO_MS) {
                intro.cancel();
                intro = null;
            } else {
                intro.pause();
            }
        }
        if (warp != null) {
            ValueAnimator interrupted = warp;
            warp = null;
            interrupted.cancel();
        }
        if (!isFinishing()) {
            if (starfield != null) {
                ((ViewGroup) starfield.getParent()).removeView(starfield);
                starfield = null;
            }
            opening = false;
            start.setEnabled(introTime == INTRO_MS);
        }
        super.onPause();
    }

    private static final class NeedleGauge extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arc = new RectF();
        private float sweep;

        NeedleGauge(Context context) { super(context); }

        void setSweep(float value) {
            sweep = value;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float x = getWidth() * 0.5f, y = getHeight() * 0.8f;
            float radius = Math.min(x * 0.84f, y * 0.87f);
            float stroke = getResources().getDisplayMetrics().density;
            arc.set(x - radius, y - radius, x + radius, y + radius);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setColor(0xFF99999D);
            canvas.drawArc(arc, 195, 150, false, paint);
            double radians = Math.toRadians(195 + 128 * sweep);
            paint.setColor(0xFFF2EEE8);
            paint.setStrokeWidth(1.5f * stroke);
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(x, y, x + (float) Math.cos(radians) * radius * 0.81f,
                    y + (float) Math.sin(radians) * radius * 0.81f, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(x, y, 2f * stroke, paint);
        }
    }
}
