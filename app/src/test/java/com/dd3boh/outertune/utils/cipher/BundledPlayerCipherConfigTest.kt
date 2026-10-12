package com.dd3boh.outertune.utils.cipher

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class BundledPlayerCipherConfigTest {
    @Test
    fun currentPlayerCanUseTheBundledWebViewFallback() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val table = context.assets.open("player_configs.json").bufferedReader().use { JSONObject(it.readText()) }
        assertEquals(1, table.getInt("schemaVersion"))
        val players = table.getJSONObject("players")
        assertTrue("The observed 5203c085 player needs a cipher fallback", players.has("5203c085"))
        val active = players.getJSONObject("5203c085")
        assertEquals("rb(2,3435,INPUT)", active.getString("sig"))
        assertEquals("bo", active.getString("nClass"))
        assertEquals("df6bd35f", active.getJSONArray("aliases").getString(0))
        val configs = PlayerCipherConfigStore.parse(table)
        assertEquals(PlayerCipherConfig("rb", listOf(2, 3435), "bo"), configs["5203c085"])
        assertEquals(configs["5203c085"], configs["df6bd35f"])
    }

    @Test
    fun bundledParametersContainOnlyCipherIdentifiersAndIntegerArguments() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val table = context.assets.open("player_configs.json").bufferedReader().use { JSONObject(it.readText()) }
        val players = table.getJSONObject("players")
        val identifier = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
        val signature = Regex("[A-Za-z_$][A-Za-z0-9_$]*\\((?:[0-9]+,)+INPUT\\)")
        val hashes = mutableSetOf<String>()
        players.keys().forEach { hash ->
            assertTrue(Regex("[0-9a-f]{8}").matches(hash))
            assertTrue("Duplicate player or alias: $hash", hashes.add(hash))
            val entry = players.getJSONObject(hash)
            assertTrue("Invalid cipher call for $hash", signature.matches(entry.getString("sig")))
            assertTrue("Invalid n-transform identifier for $hash", identifier.matches(entry.getString("nClass")))
            entry.optJSONArray("aliases")?.let { aliases ->
                for (index in 0 until aliases.length()) {
                    val alias = aliases.getString(index)
                    assertTrue(Regex("[0-9a-f]{8}").matches(alias))
                    assertTrue("Duplicate player or alias: $alias", hashes.add(alias))
                }
            }
        }
    }
}
