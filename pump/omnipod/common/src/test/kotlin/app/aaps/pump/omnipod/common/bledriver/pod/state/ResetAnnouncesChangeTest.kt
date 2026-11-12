package app.aaps.pump.omnipod.common.bledriver.pod.state

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.omnipod.common.EventOmnipodDashPumpValuesChanged
import app.aaps.pump.omnipod.common.bledriver.pod.definition.ActivationProgress
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.Mock

/**
 * [OmnipodDashPodStateManagerImpl.reset] runs when a pod is deactivated or discarded. Listeners must hear
 * about it: the pump plugin refreshes its reservoir level only on this event once no pod is active.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResetAnnouncesChangeTest : TestBase() {

    @Mock lateinit var preferences: Preferences

    @Test
    fun `reset clears the pod state and announces the change`() = runTest {
        val sut = OmnipodDashPodStateManagerImpl(aapsLogger, rxBus, preferences)
        sut.activationProgress = ActivationProgress.COMPLETED
        sut.podState.pulsesRemaining = 80.toShort()

        var announced = false
        val listener = launch(UnconfinedTestDispatcher(testScheduler)) {
            rxBus.toFlow(EventOmnipodDashPumpValuesChanged::class).first()
            announced = true
        }

        sut.reset()

        assertThat(announced).isTrue()
        assertThat(sut.activationProgress).isEqualTo(ActivationProgress.NOT_STARTED)
        assertThat(sut.pulsesRemaining).isNull()
        listener.cancel()
    }
}
