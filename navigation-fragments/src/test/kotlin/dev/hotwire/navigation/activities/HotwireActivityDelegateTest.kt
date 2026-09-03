package dev.hotwire.navigation.activities

import androidx.navigation.NavController
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.never
import com.nhaarman.mockito_kotlin.times
import com.nhaarman.mockito_kotlin.verify
import com.nhaarman.mockito_kotlin.whenever
import dev.hotwire.navigation.navigator.Navigator
import dev.hotwire.navigation.navigator.NavigatorConfiguration
import dev.hotwire.navigation.navigator.NavigatorHost
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HotwireActivityDelegateTest {

    private lateinit var activity: TestActivity
    private lateinit var delegate: HotwireActivityDelegate

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(TestActivity::class.java).create().get()
        delegate = activity.delegate
    }

    @Test
    fun `does not boot the current host while start locations are held`() {
        val host = navigatorHost(mainConfig)

        delegate.holdStartLocations()
        delegate.registerNavigatorHost(host)

        assertThat(delegate.isHoldingStartLocations).isTrue()
        verify(host, never()).initControllerGraphIfNeeded()
    }

    @Test
    fun `releasing start locations boots the current host`() {
        val host = navigatorHost(mainConfig)

        delegate.holdStartLocations()
        delegate.registerNavigatorHost(host)
        delegate.releaseStartLocations()

        assertThat(delegate.isHoldingStartLocations).isFalse()
        verify(host).initControllerGraphIfNeeded()
    }

    @Test
    fun `releasing start locations leaves a lazy non-current host unbooted`() {
        val currentHost = navigatorHost(mainConfig)
        val lazyHost = navigatorHost(secondaryConfig)

        delegate.setLazyNavigatorHosts(listOf(mainConfig.navigatorHostId, secondaryConfig.navigatorHostId))
        delegate.holdStartLocations()
        delegate.registerNavigatorHost(currentHost)
        delegate.registerNavigatorHost(lazyHost)
        delegate.releaseStartLocations()

        verify(currentHost).initControllerGraphIfNeeded()
        verify(lazyHost, never()).initControllerGraphIfNeeded()
    }

    @Test
    fun `releasing start locations is idempotent`() {
        val host = navigatorHost(mainConfig)

        delegate.holdStartLocations()
        delegate.registerNavigatorHost(host)
        delegate.releaseStartLocations()
        delegate.releaseStartLocations()

        verify(host, times(1)).initControllerGraphIfNeeded()
    }

    @Test
    fun `releasing start locations is a no-op when nothing is held`() {
        val host = navigatorHost(mainConfig)

        delegate.registerNavigatorHost(host)
        delegate.releaseStartLocations()

        // Booted once on registration, not again on release.
        verify(host, times(1)).initControllerGraphIfNeeded()
    }

    @Test
    fun `the tab selected while held is remembered and booted on release`() {
        val mainHost = navigatorHost(mainConfig)
        val secondaryHost = navigatorHost(secondaryConfig)

        delegate.setLazyNavigatorHosts(listOf(mainConfig.navigatorHostId, secondaryConfig.navigatorHostId))
        delegate.holdStartLocations()
        delegate.registerNavigatorHost(mainHost)
        delegate.registerNavigatorHost(secondaryHost)
        delegate.setCurrentNavigator(secondaryConfig)

        verify(secondaryHost, never()).initControllerGraphIfNeeded()

        delegate.releaseStartLocations()

        verify(secondaryHost).initControllerGraphIfNeeded()
        verify(mainHost, never()).initControllerGraphIfNeeded()
    }

    // Production ordering: HotwireBottomNavigationController.load() runs in Activity.onCreate()
    // and selects the initial tab before any NavigatorHost view is created (hosts register in
    // onStart), so setCurrentNavigator() is called against an empty host map.
    @Test
    fun `current navigator selected before any host registers is booted on release`() {
        delegate.setLazyNavigatorHosts(listOf(mainConfig.navigatorHostId, secondaryConfig.navigatorHostId))
        delegate.holdStartLocations()
        delegate.setCurrentNavigator(secondaryConfig)

        val currentHost = navigatorHost(secondaryConfig)
        val lazyHost = navigatorHost(mainConfig)
        delegate.registerNavigatorHost(currentHost)
        delegate.registerNavigatorHost(lazyHost)

        verify(currentHost, never()).initControllerGraphIfNeeded()
        verify(lazyHost, never()).initControllerGraphIfNeeded()

        delegate.releaseStartLocations()

        verify(currentHost).initControllerGraphIfNeeded()
        verify(lazyHost, never()).initControllerGraphIfNeeded()
    }

    @Test
    fun `hosts registered after release boot normally`() {
        delegate.holdStartLocations()
        delegate.releaseStartLocations()

        val host = navigatorHost(mainConfig)
        delegate.registerNavigatorHost(host)

        verify(host).initControllerGraphIfNeeded()
    }

    private fun navigatorHost(configuration: NavigatorConfiguration): NavigatorHost {
        val navigator = mock<Navigator>()
        whenever(navigator.configuration).thenReturn(configuration)

        return mock<NavigatorHost>().also {
            whenever(it.id).thenReturn(configuration.navigatorHostId)
            whenever(it.navigator).thenReturn(navigator)
            whenever(it.navController).thenReturn(mock<NavController>())
        }
    }

    companion object {
        private val mainConfig = NavigatorConfiguration(
            name = "main",
            startLocation = "https://example.com/main",
            navigatorHostId = 1
        )

        private val secondaryConfig = NavigatorConfiguration(
            name = "secondary",
            startLocation = "https://example.com/secondary",
            navigatorHostId = 2
        )
    }

    private class TestActivity : HotwireActivity() {
        override fun navigatorConfigurations() = listOf(mainConfig, secondaryConfig)
    }
}
