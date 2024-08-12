package models

import dev.inmo.plagubot.plugins.bans.models.WorkMode
import dev.inmo.plagubot.plugins.bans.utils.banPluginSerialFormat
import org.junit.Test
import kotlin.test.assertEquals

class WorkModesTests {
    private fun workModeSerializedType(classname: String) = "{\"type\":\"${classname}\"}"
    val workModesToNominalSerializations = mapOf(
        WorkMode.EnabledForAdmins.Default to workModeSerializedType(WorkMode.EnabledForAdmins.Default.javaClass.canonicalName!!),
        WorkMode.EnabledForUsers.Default to workModeSerializedType(WorkMode.EnabledForUsers.Default.javaClass.canonicalName!!),
        WorkMode.Enabled to workModeSerializedType(WorkMode.Enabled.javaClass.canonicalName!!),
        WorkMode.Disabled to workModeSerializedType(WorkMode.Disabled.javaClass.canonicalName!!)
    )
    @Test
    fun workModesSerializingCorrectly() {
        workModesToNominalSerializations.forEach { (mode, serialized) ->
            assertEquals(serialized, banPluginSerialFormat.encodeToString(WorkMode.serializer(), mode))
        }
    }
}
