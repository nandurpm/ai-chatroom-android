package com.example.aichatroom

import com.example.aichatroom.domain.AgentProfile
import com.example.aichatroom.network.*

/** Keeps the original 21 test scenarios readable while routing all of them through the generic client. */
fun NvidiaParticipant(api: OpenAiApi, key: () -> String, model: String,
    fallbackModel: String = "qwen/qwen3.5-397b-a17b") = OpenAiCompatibleParticipant(api,
    AgentProfile.NVIDIA.copy(providerConfig = AgentProfile.NVIDIA.providerConfig.copy(
        modelId = model, fallbackModel = fallbackModel)), key)
