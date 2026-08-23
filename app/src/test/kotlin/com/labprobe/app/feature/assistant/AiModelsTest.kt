package com.labprobe.app.feature.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiModelsTest {
    @Test fun tokenSummaryAddsPromptAndCompletion() {
        assertEquals(42, AiTokenSummary(17, 25).total)
    }

    @Test fun settingsDefaultsToDeepSeek() {
        val settings = AiSettings()
        assertEquals("deepseek-v4-flash", settings.model)
        assertTrue(!settings.hasApiKey)
    }
}
