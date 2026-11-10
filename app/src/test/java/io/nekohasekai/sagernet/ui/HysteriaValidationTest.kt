package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.QuietSagerNet
import io.nekohasekai.sagernet.fmt.FixtureProcess
import io.nekohasekai.sagernet.ui.profile.HysteriaSettingsActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = SagerNet::class, sdk = [29], shadows = [QuietSagerNet::class, FixtureProcess::class])
class HysteriaValidationTest {
    @Test fun switchingToV1IgnoresHiddenV2FieldsButStillChecksPorts() {
        val activity = Robolectric.buildActivity(HysteriaSettingsActivity::class.java).get()
        DataStore.protocolVersion = 1
        DataStore.serverRealmServerUrl = "https://realm.example"
        DataStore.serverPorts = "443,8443"
        DataStore.serverHopInterval = 30
        DataStore.serverHopIntervalMax = 20
        assertNull(activity.validateHysteriaSettings())
        DataStore.serverPorts = "70000"
        assertEquals("serverPorts", activity.validateHysteriaSettings()!!.preferenceKey)
        DataStore.protocolVersion = 2
        assertNull(activity.validateHysteriaSettings())
        DataStore.serverRealmServerUrl = ""
        DataStore.serverPorts = "443,8443"
        assertEquals(Key.SERVER_HOP_INTERVAL_MAX, activity.validateHysteriaSettings()!!.preferenceKey)
    }
}
