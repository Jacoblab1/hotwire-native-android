package dev.hotwire.navigation.activities

import android.os.Bundle
import android.os.Looper.getMainLooper
import android.widget.FrameLayout
import dev.hotwire.navigation.navigator.NavigatorConfiguration
import dev.hotwire.navigation.navigator.NavigatorHost
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Exercises the hold/release behavior with a real [NavigatorHost] fragment rather than
 * a mock, so the start destination is actually built (or not) by the navigation library.
 */
@RunWith(RobolectricTestRunner::class)
class HotwireActivityDelegateRealHostTest {

    @Test
    fun `held host is not ready until start locations are released`() {
        val activity = Robolectric.buildActivity(TestActivity::class.java).setup().get()
        shadowOf(getMainLooper()).idle()

        val host = requireNotNull(activity.delegate.findNavigatorHost(HOST_ID))
        assertThat(host.isReady()).isFalse()
        assertThat(activity.delegate.currentNavigator).isNull()

        activity.delegate.releaseStartLocations()
        shadowOf(getMainLooper()).idle()

        assertThat(host.isReady()).isTrue()
        assertThat(activity.delegate.currentNavigator).isNotNull()
    }

    companion object {
        private const val HOST_ID = 1234

        private val config = NavigatorConfiguration(
            name = "main",
            startLocation = "https://example.com/main",
            navigatorHostId = HOST_ID
        )
    }

    internal class TestActivity : HotwireActivity() {
        override fun navigatorConfigurations() = listOf(config)

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            delegate.holdStartLocations()

            setTheme(androidx.appcompat.R.style.Theme_AppCompat)
            setContentView(FrameLayout(this).apply { id = HOST_ID })

            if (savedInstanceState == null) {
                supportFragmentManager.beginTransaction()
                    .add(HOST_ID, NavigatorHost())
                    .commitNow()
            }
        }
    }
}
