package com.fetch.core.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fetch.core.sdk.FetchEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PassageSelectionTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun testPassageSelectionFavorsProximityAndRarityOverTermRepetition() = runBlocking {
        val engine = FetchEngine.create(context)

        val documentContent = """
            # Architecture Guide
            
            This section discusses quantum mechanics and how quantum physics operates in modern laboratories.
            
            Quantum quantum quantum quantum quantum quantum quantum quantum quantum quantum quantum.
            
            When studying quantum mechanics, researchers analyze physical systems using quantum physics principles to solve complex problems cleanly.
        """.trimIndent()

        val doc = engine.add(content = documentContent, title = "Quantum Mechanics Guide", url = "https://example.com/quantum")

        // Query contains 2 distinct terms: "quantum mechanics"
        val evidences = engine.find(url = doc.urls.requested, query = "quantum mechanics", maxPassages = 2)

        assertNotNull(evidences)
        assertTrue("At least 1 passage returned", evidences.isNotEmpty())

        // The passage with high proximity and term rarity ("quantum mechanics" together) should be ranked first
        val topPassage = evidences.first().passage
        assertTrue(
            "Top passage must be the relevant explanatory section rather than repetitive word spam",
            topPassage.contains("researchers analyze physical systems") || topPassage.contains(" quantum physics operates")
        )

        engine.close()
    }
}
