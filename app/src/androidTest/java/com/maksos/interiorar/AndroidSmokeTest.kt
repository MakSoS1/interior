package com.maksos.interiorar

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator test: the 3D project library must launch without granting camera access. */
@RunWith(AndroidJUnit4::class)
class AndroidSmokeTest {
    @Test fun mainScreenShowsProjectAndSampleActions() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("Загрузить GLB")).check(matches(isDisplayed()))
            onView(withText("Тестовая кухня")).check(matches(isDisplayed()))
        }
    }
}
