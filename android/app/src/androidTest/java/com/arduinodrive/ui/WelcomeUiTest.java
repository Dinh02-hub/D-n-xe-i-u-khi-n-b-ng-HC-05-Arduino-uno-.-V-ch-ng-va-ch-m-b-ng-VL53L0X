package com.arduinodrive.ui;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import com.arduinodrive.R;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class WelcomeUiTest {
    @Rule public ActivityTestRule<WelcomeActivity> rule =
            new ActivityTestRule<>(WelcomeActivity.class, false, false);

    @Test public void startIsAvailableAndBackFromSettingsDoesNotReplayWelcome() throws Throwable {
        InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getSharedPreferences("drive_settings", 0).edit().putBoolean("simulation", true).commit();
        rule.launchActivity(null);
        Activity welcome = awaitScreen(WelcomeActivity.class, 0);
        awaitStartReady(welcome);
        rule.runOnUiThread(() -> assertTrue(welcome.findViewById(R.id.welcomeStartButton).performClick()));
        Activity connection = awaitScreen(ConnectionActivity.class, 0);
        rule.runOnUiThread(() -> assertTrue(connection.findViewById(R.id.settingsButton).performClick()));
        Activity settings = awaitScreen(SettingsActivity.class, 0);
        rule.runOnUiThread(() -> {
            assertTrue(settings.findViewById(R.id.themeToggle).isShown());
            assertTrue(settings.findViewById(R.id.backButton).performClick());
        });
        Activity returned = awaitScreen(ConnectionActivity.class, 0);
        rule.runOnUiThread(() -> {
            assertTrue(returned.findViewById(R.id.openControlButton).isShown());
            assertNull(returned.findViewById(R.id.welcomeStartButton));
        });
    }

    @Test public void duplicateStartCreatesOneConnectionScreen() throws Throwable {
        AtomicInteger created = new AtomicInteger();
        ActivityLifecycleCallback observer = (activity, stage) -> {
            if (stage == Stage.CREATED && activity instanceof ConnectionActivity) created.incrementAndGet();
        };
        rule.launchActivity(null);
        awaitStartReady();
        rule.runOnUiThread(() -> ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(observer));
        try {
            rule.runOnUiThread(() -> {
                View start = rule.getActivity().findViewById(R.id.welcomeStartButton);
                start.performClick();
                start.performClick();
                ViewGroup content = rule.getActivity().findViewById(android.R.id.content);
                assertTrue(content.getChildAt(content.getChildCount() - 1) instanceof StarfieldWarpView);
            });
            Activity connection = awaitScreen(ConnectionActivity.class, 0);
            rule.runOnUiThread(() -> assertTrue(connection.findViewById(R.id.openControlButton).isShown()));
            assertEquals(1, created.get());
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(observer));
        }
    }

    @Test public void titleFinishesBeforeTheStartButtonFadesIn() throws Throwable {
        rule.launchActivity(null);
        rule.runOnUiThread(() -> {
            WelcomeActivity activity = rule.getActivity();
            View copy = activity.findViewById(R.id.welcomeCopy);
            View start = activity.findViewById(R.id.welcomeStartButton);
            renderIntroAt(activity, 2850);
            assertTrue(copy.getAlpha() > 0.9f);
            assertEquals(View.INVISIBLE, start.getVisibility());
            assertFalse(start.isEnabled());
            renderIntroAt(activity, 3200);
            assertEquals(1f, copy.getAlpha(), 0f);
            assertEquals(View.VISIBLE, start.getVisibility());
            assertTrue(start.getAlpha() > 0f && start.getAlpha() < 1f);
            assertFalse(start.isEnabled());
            renderIntroAt(activity, 3500);
            assertEquals(1f, start.getAlpha(), 0f);
            assertTrue(start.isEnabled());
        });
    }

    @Test public void rotationStillAllowsTheIntroToFinishAndStart() throws Throwable {
        rule.launchActivity(null);
        rule.runOnUiThread(() -> rule.getActivity().setRequestedOrientation(
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
        Activity rotated = awaitScreen(WelcomeActivity.class, Configuration.ORIENTATION_LANDSCAPE);
        awaitStartReady(rotated);
        rule.runOnUiThread(() -> assertEquals(View.INVISIBLE,
                rotated.findViewById(R.id.welcomeInstrument).getVisibility()));
    }

    private Activity awaitScreen(Class<? extends Activity> type, int orientation) {
        long deadline = SystemClock.uptimeMillis() + 10000;
        AtomicReference<Activity> visible = new AtomicReference<>();
        while (SystemClock.uptimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED)) {
                    if (type.isInstance(activity) && activity.hasWindowFocus() && (orientation == 0
                            || activity.getResources().getConfiguration().orientation == orientation)) {
                        visible.set(activity);
                    }
                }
            });
            if (visible.get() != null) return visible.get();
            SystemClock.sleep(30);
        }
        throw new AssertionError("Không mở được màn hình " + type.getSimpleName());
    }

    private void awaitStartReady() throws Throwable {
        awaitStartReady(rule.getActivity());
    }

    private void awaitStartReady(Activity activity) throws Throwable {
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < deadline) {
            AtomicReference<Boolean> ready = new AtomicReference<>();
            rule.runOnUiThread(() -> {
                View start = activity.findViewById(R.id.welcomeStartButton);
                ready.set(start.isEnabled() && start.isShown());
            });
            if (ready.get()) return;
            SystemClock.sleep(30);
        }
        fail("Nút Bắt đầu chưa xuất hiện");
    }

    private static void renderIntroAt(WelcomeActivity activity, int time) {
        try {
            java.lang.reflect.Method render = WelcomeActivity.class.getDeclaredMethod("renderIntro", int.class);
            render.setAccessible(true);
            render.invoke(activity, time);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }
}
