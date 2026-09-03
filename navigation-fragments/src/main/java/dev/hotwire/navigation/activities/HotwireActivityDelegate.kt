package dev.hotwire.navigation.activities

import androidx.activity.OnBackPressedCallback
import androidx.annotation.IdRes
import dev.hotwire.navigation.logging.logDebug
import dev.hotwire.navigation.navigator.Navigator
import dev.hotwire.navigation.navigator.NavigatorConfiguration
import dev.hotwire.navigation.navigator.NavigatorHost
import dev.hotwire.navigation.observers.HotwireActivityObserver

/**
 * Initializes the Activity for Hotwire navigation and provides all the hooks for an
 * Activity to communicate with Hotwire Native (and vice versa).
 *
 * @property activity The Activity to bind this delegate to.
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
class HotwireActivityDelegate(val activity: HotwireActivity) {
    private val navigatorHosts = mutableMapOf<Int, NavigatorHost>()
    private val lazyNavigatorHostIds = mutableSetOf<Int>()
    private var holdingStartLocations = false

    private val onBackPressedCallback = object : OnBackPressedCallback(enabled = true) {
        override fun handleOnBackPressed() {
            currentNavigator?.pop()
        }
    }

    private var currentNavigatorHostId = activity.navigatorConfigurations().first().navigatorHostId

    /**
     * Initializes the Activity with a BackPressedDispatcher that properly
     * handles Fragment navigation with the back button.
     */
    init {
        activity.lifecycle.addObserver(HotwireActivityObserver())
        activity.onBackPressedDispatcher.addCallback(
            owner = activity,
            onBackPressedCallback = onBackPressedCallback
        )
    }

    /**
     * Get the Activity's currently active [Navigator].
     *
     * Returns null if the navigator is not ready for navigation.
     */
    val currentNavigator: Navigator?
        get() {
            val host = navigatorHosts[currentNavigatorHostId]

            return if (host?.isReady() == true) {
                host.navigator
            } else {
                null
            }
        }


    /**
     * True while start locations are being held back. See [holdStartLocations].
     */
    val isHoldingStartLocations: Boolean
        get() = holdingStartLocations

    /**
     * Sets the currently active navigator in your Activity. If you use multiple
     *  [NavigatorHost] instances in your app (such as for bottom tabs),
     *  you must update this whenever the current navigator changes.
     */
    fun setCurrentNavigator(configuration: NavigatorConfiguration) {
        logDebug("navigatorSetAsCurrent", listOf("navigator" to configuration.name))
        currentNavigatorHostId = configuration.navigatorHostId

        val navigatorHost = navigatorHosts[currentNavigatorHostId]
        if (navigatorHost != null) {
            if (!holdingStartLocations) {
                navigatorHost.initControllerGraphIfNeeded()
            }
            updateOnBackPressedCallback(navigatorHost)
        }
    }

    /**
     * Prevents every registered and future [NavigatorHost] from building its navigation
     * graph and visiting its start location until [releaseStartLocations] is called. Use
     * this when the app isn't ready to make its first visit yet (e.g. while an async
     * authentication bootstrap is in flight).
     *
     * Must be called before the hosts' views are created and they register themselves
     * with this delegate. In practice that means in `Activity.onCreate()`, before
     * `setContentView()` and before `HotwireBottomNavigationController.load()`, and at
     * the latest before the Activity is started. A host that has already registered has
     * booted its start location, and this won't undo that.
     */
    fun holdStartLocations() {
        logDebug("startLocationsHeld", listOf("navigator" to "all"))
        holdingStartLocations = true
    }

    /**
     * Lifts the hold started by [holdStartLocations]. Boots the start location of every
     * host that would have been booted on registration had the hold not been in place:
     * hosts that aren't lazy, plus the current host. Hosts that register afterwards
     * behave normally.
     *
     * Call this while the Activity is resumed (or at least started with its fragment
     * state not yet saved). `FragmentNavigator` silently drops the navigate to the start
     * destination when `FragmentManager.isStateSaved` is true, and the host is marked as
     * initialized either way, so releasing while stopped leaves the host with a
     * navigation graph but no start fragment.
     *
     * This is idempotent and is a no-op when nothing is being held.
     */
    fun releaseStartLocations() {
        if (!holdingStartLocations) return

        logDebug("startLocationsReleased", listOf("navigator" to "all"))
        holdingStartLocations = false

        navigatorHosts.values
            .filter { it.id !in lazyNavigatorHostIds || it.id == currentNavigatorHostId }
            .forEach { it.initControllerGraphIfNeeded() }
    }

    /**
     * Sets the given navigator hosts as lazy, meaning their start destination
     * won't be loaded until the host first becomes the current navigator (e.g.
     * when its bottom tab is first selected). Any host not marked as lazy is
     * loaded eagerly as soon as its view is created.
     */
    internal fun setLazyNavigatorHosts(navigatorHostIds: Collection<Int>) {
        lazyNavigatorHostIds.clear()
        lazyNavigatorHostIds.addAll(navigatorHostIds)
    }

    internal fun registerNavigatorHost(host: NavigatorHost) {
        logDebug("navigatorRegistered", listOf("navigator" to host.navigator.configuration.name))

        if (navigatorHosts[host.id] == null) {
            navigatorHosts[host.id] = host
            listenToDestinationChanges(host)

            if (currentNavigatorHostId == host.id) {
                updateOnBackPressedCallback(host)
            }

            // Load the host's start destination unless start locations are being
            // held back, or it's a lazy host that isn't currently selected. Lazy
            // hosts are loaded when they first become the current navigator.
            if (!holdingStartLocations &&
                (host.id !in lazyNavigatorHostIds || currentNavigatorHostId == host.id)
            ) {
                host.initControllerGraphIfNeeded()
            }
        }
    }

    internal fun unregisterNavigatorHost(host: NavigatorHost) {
        logDebug("navigatorUnregistered", listOf("navigator" to host.navigator.configuration.name))
        navigatorHosts.remove(host.id)
    }

    internal fun onNavigatorHostReady(host: NavigatorHost) {
        logDebug("navigatorReady", listOf("navigator" to host.navigator.configuration.name))
        activity.onNavigatorReady(host.navigator)
    }

    /**
     * Finds the registered navigator host associated with the provided resource ID.
     *
     * @param navigatorHostId
     * @return The [NavigatorHost] instance if it's view has been created and it has
     *  been registered with the Activity, otherwise `null`.
     */
    fun findNavigatorHost(@IdRes navigatorHostId: Int): NavigatorHost? {
        return navigatorHosts[navigatorHostId]
    }

    /**
     * Resets the sessions associated with all registered navigator hosts.
     * Hosts that haven't loaded their start destination yet are skipped.
     */
    fun resetSessions() {
        navigatorHosts.values
            .filter { it.isGraphInitialized }
            .forEach {
                it.navigator.session.reset()
                it.navigator.modalSession.reset()
            }
    }

    /**
     * Resets all registered navigators via [Navigator.reset].
     * Hosts that haven't loaded their start destination yet are skipped.
     */
    fun resetNavigators() {
        navigatorHosts.values
            .filter { it.isGraphInitialized }
            .forEach { it.navigator.reset() }
    }

    private fun listenToDestinationChanges(host: NavigatorHost) {
        host.navController.addOnDestinationChangedListener { controller, _, _ ->
            updateOnBackPressedCallback(host)
        }
    }

    private fun updateOnBackPressedCallback(host: NavigatorHost) {
        if (host.id == currentNavigatorHostId) {
            onBackPressedCallback.isEnabled = host.navController.previousBackStackEntry != null
        }
    }
}
