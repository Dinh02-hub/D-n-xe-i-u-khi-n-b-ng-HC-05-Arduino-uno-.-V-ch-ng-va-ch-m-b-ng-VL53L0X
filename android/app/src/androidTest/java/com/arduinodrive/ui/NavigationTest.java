package com.arduinodrive.ui;

import androidx.test.rule.ActivityTestRule;
import com.arduinodrive.R;
import org.junit.Rule;
import org.junit.Test;
import org.junit.Before;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.assertEquals;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;

public class NavigationTest {
    @Rule public ActivityTestRule<ConnectionActivity> rule = new ActivityTestRule<>(ConnectionActivity.class, false, false);
    @Before public void launch() {
        InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getSharedPreferences("drive_settings", 0).edit().clear().putBoolean("simulation", true).commit();
        rule.launchActivity(null);
    }
    @Test public void allScreensAndThemeChoicesAreReachable() {
        onView(withId(R.id.settingsButton)).perform(click());
        onView(withId(R.id.themeDark)).perform(click());
        onView(withId(R.id.themeDark)).check(matches(isChecked()));
        onView(withId(R.id.themeLight)).perform(click());
        onView(withId(R.id.themeLight)).check(matches(isChecked()));
        onView(withId(R.id.themeSystem)).perform(click());
        onView(withId(R.id.themeSystem)).check(matches(isChecked()));
        // A real swipe also covers the ScrollView's edge-to-edge inset handling.
        onView(withId(R.id.root)).perform(swipeUp());
        onView(withId(R.id.aboutButton)).perform(scrollTo(), click());
        onView(withText(R.string.close)).perform(click());
        pressBack();
        onView(withId(R.id.openControlButton)).perform(scrollTo(), click());
        onView(withId(R.id.forwardButton)).check(matches(isDisplayed()));
        onView(withId(R.id.stopButton)).check(matches(isDisplayed()));
        pressBack();
        onView(withId(R.id.openControlButton)).check(matches(isDisplayed()));
    }
    @Test public void rapidRepeatedOpenCreatesOnlyOneControlActivity() throws Throwable {
        AtomicInteger created = new AtomicInteger();
        ActivityLifecycleCallback observer = (activity, stage) -> {
            if (stage == Stage.CREATED && activity instanceof ControlActivity) created.incrementAndGet();
        };
        rule.runOnUiThread(() -> ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(observer));
        try {
            rule.runOnUiThread(() -> {
                rule.getActivity().findViewById(R.id.openControlButton).performClick();
                rule.getActivity().findViewById(R.id.openControlButton).performClick();
            });
            onView(withId(R.id.forwardButton)).check(matches(isDisplayed()));
            assertEquals("Repeated taps must not create competing Control sessions", 1, created.get());
        } finally {
            rule.runOnUiThread(() -> ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(observer));
        }
    }
}
