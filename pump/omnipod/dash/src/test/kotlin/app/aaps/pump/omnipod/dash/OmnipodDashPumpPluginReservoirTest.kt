package app.aaps.pump.omnipod.dash

import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.pump.BlePreCheck
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.omnipod.common.EventOmnipodDashPumpValuesChanged
import app.aaps.pump.omnipod.common.bledriver.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.common.bledriver.pod.state.OmnipodDashPodStateManager
import app.aaps.pump.omnipod.dash.driver.OmnipodDashManager
import app.aaps.pump.omnipod.dash.history.DashHistory
import app.aaps.pump.omnipod.dash.history.database.DashHistoryDatabase
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.stubbing.Answer

/**
 * The reservoir on the overview after a pod is deactivated or discarded.
 *
 * With no active pod, getPumpStatus is skipped, so the plugin's reservoir is only refreshed when the
 * pod state announces a change. Before, the old pod's last level (for example 4 U) stayed on screen.
 */
class OmnipodDashPumpPluginReservoirTest : TestBase() {

    private val podStateManager = mock<OmnipodDashPodStateManager>()

    // Every observed preference is a flow that never changes, so onStart can wire its collectors.
    private val preferences = mock<Preferences>(defaultAnswer = Answer { invocation ->
        val type = invocation.method.returnType
        when {
            StateFlow::class.java.isAssignableFrom(type) -> MutableStateFlow<Any>(Unit)
            Flow::class.java.isAssignableFrom(type)      -> emptyFlow<Any>()
            else                                         -> Mockito.RETURNS_DEFAULTS.answer(invocation)
        }
    })

    private lateinit var sut: OmnipodDashPumpPlugin

    @BeforeEach
    fun setUp() {
        sut = OmnipodDashPumpPlugin(
            aapsLogger = aapsLogger,
            rh = mock<ResourceHelper>(),
            preferences = preferences,
            commandQueue = mock<CommandQueue>(),
            omnipodManager = mock<OmnipodDashManager>(),
            podStateManager = podStateManager,
            history = mock<DashHistory>(),
            pumpSync = mock<PumpSync>(),
            rxBus = rxBus,
            aapsSchedulers = mock<AapsSchedulers>(),
            uiInteraction = mock<UiInteraction>(),
            notificationManager = mock<NotificationManager>(),
            pumpEnactResultProvider = { mock<PumpEnactResult>() },
            bolusProgressData = mock<BolusProgressData>(),
            dashHistoryDatabase = mock<DashHistoryDatabase>(),
            protectionCheck = mock<ProtectionCheck>(),
            blePreCheck = mock<BlePreCheck>()
        )
        runBlocking { sut.onStart() }
    }

    @AfterEach
    fun tearDown() {
        runBlocking { sut.onStop() }
    }

    @Test
    fun `a deactivated pod no longer shows its last reservoir level`() {
        whenever(podStateManager.activationProgress).thenReturn(ActivationProgress.COMPLETED)
        whenever(podStateManager.pulsesRemaining).thenReturn(80.toShort())
        announceUntilReservoir(4.0)

        // The pod state was reset (deactivate or discard): no active pod any more.
        whenever(podStateManager.activationProgress).thenReturn(ActivationProgress.NOT_STARTED)
        whenever(podStateManager.pulsesRemaining).thenReturn(null)
        announceUntilReservoir(0.0)
    }

    /**
     * The bus keeps no events, and the plugin subscribes on another thread, so the first event can be sent
     * before anyone listens. Announcing again is harmless: the event only says "the pod state changed".
     */
    private fun announceUntilReservoir(expected: Double) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && sut.reservoirLevel.value.cU != expected) {
            rxBus.send(EventOmnipodDashPumpValuesChanged())
            Thread.sleep(10)
        }
        assertThat(sut.reservoirLevel.value.cU).isWithin(1e-9).of(expected)
    }
}
