package com.example.aichatroom

import com.example.aichatroom.domain.*
import com.example.aichatroom.network.*
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ReplyTuningTest {
    @Test fun quickModeDefaultsOnAndBothNvidiaModelsDisableThinking() {
        val prefs = Preferences()
        assertTrue(prefs.quickReplies)
        for (model in listOf(prefs.nvidiaModel, prefs.nvidiaFallbackModel)) {
            val request = OpenAiRequest(model, emptyList(), chatTemplateKwargs = ReplyTuning.nvidia(model, true))
            val json = JsonParser.parseString(Gson().toJson(request)).asJsonObject
            assertFalse(json["chat_template_kwargs"].asJsonObject["enable_thinking"].asBoolean)
        }
    }
    @Test fun deeperModeAndCustomModelsOmitUnsupportedThinkingFields() {
        assertNull(ReplyTuning.nvidia(Preferences().nvidiaModel, false))
        assertNull(ReplyTuning.nvidia("other/custom", true))
        assertNull(ReplyTuning.gemini(Preferences().geminiModel, false))
        assertNull(ReplyTuning.gemini("gemini-2.5-pro", true))
        val json = Gson().toJson(GenerationConfig(thinkingConfig = ReplyTuning.gemini("custom", true)))
        assertFalse(json.contains("thinkingConfig"))
    }
    @Test fun expertRetainsMoreAnswerSpaceAndDeepModeOriginalBudget() {
        assertEquals(2048, ReplyTuning.tokens(true, Mode.FRIENDLY))
        assertEquals(4096, ReplyTuning.tokens(true, Mode.EXPERT))
        assertEquals(8192, ReplyTuning.tokens(false, Mode.EXPERT))
        assertEquals("original", ReplyTuning.prompt("original", false))
    }
}

