package com.fetch.core.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fetch.core.config.EngineConfig
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
public class FetchEngineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    public fun testFetchEngineFactoryCreation() {
        val config = EngineConfig()
        val engine = FetchEngine.create(context, config)

        assertNotNull(engine)
        engine.close()
    }

    @Test
    public fun testFetchEngineHealth() {
        val engine = FetchEngine.create(context)

        kotlinx.coroutines.runBlocking {
            val health = engine.health()
            assertNotNull(health)
            assertTrue(health.engineVersion.isNotBlank())
            assertTrue(health.capabilities.containsKey("search"))
        }

        engine.close()
    }
}
