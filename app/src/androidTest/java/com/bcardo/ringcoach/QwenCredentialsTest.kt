package com.bcardo.ringcoach

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class QwenCredentialsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "test-qwen-" + UUID.randomUUID()
    private lateinit var credentials: QwenCredentials

    @Before
    fun setup() {
        credentials = QwenCredentials(context, name, name)
    }

    @After
    fun cleanup() {
        context.deleteSharedPreferences(name)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(name)
    }

    @Test
    fun encryptsWithRandomIvAndPersistsWithoutPlaintext() {
        val key = "sk-fake-instrumentation-key"
        credentials.save(key)
        val preferences = context.getSharedPreferences(name, 0)
        val first = preferences.getString("encrypted_key", null)
        assertFalse(first.orEmpty().contains(key))
        credentials.save(key)
        assertNotEquals(first, preferences.getString("encrypted_key", null))
        assertEquals(key, QwenCredentials(context, name, name).read())
        credentials.clear()
        assertEquals(null, credentials.read())
    }

    @Test
    fun invalidReplacementPreservesSavedKey() {
        val original = "sk-fake-original"
        credentials.save(original)
        try {
            credentials.save("sk-invalid\nreplacement")
            throw AssertionError("Invalid credential accepted")
        } catch (_: IllegalArgumentException) {
            assertEquals(original, credentials.read())
        }
    }
}
