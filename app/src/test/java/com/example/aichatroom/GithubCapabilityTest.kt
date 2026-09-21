package com.example.aichatroom

import com.example.aichatroom.network.*
import com.google.gson.JsonParser
import java.util.Base64
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** No write is allowed via the read path, without approval, or with a replayed/repo-swapped approval. */
class GithubCapabilityTest {
    private val action = GithubAction(GithubOperation.CREATE_ISSUE, title = "Test", body = "Exact proposal")
    @Test fun onlyWholeExplicitActionBlocksAreRecognized() {
        val text = "```github\n{\"operation\":\"CREATE_ISSUE\",\"title\":\"Test\"}\n```"
        assertNotNull(GithubProtocol.parse(text))
        assertNull(GithubProtocol.parse("Example:\n$text"))
        assertNull(GithubProtocol.parse("```github\n{\"operation\":\"DELETE_FILE\"}\n```"))
        assertNull(GithubProtocol.parse("```github\n{\"operation\":\"LIST_FILES\",\"repository\":\"other/repo\"}\n```"))
    }
    @Test fun approvalsAreBoundToMessageAndRepositoryAndConsumedOnce() {
        val gate = WriteConfirmations()
        gate.offer(PendingGithubWrite(3, "Agent", "owner/repo", action))
        assertNull(gate.consume(4, "owner/repo"))
        assertNull(gate.consume(3, "other/repo"))
        gate.offer(PendingGithubWrite(3, "Agent", "owner/repo", action))
        assertEquals(action, gate.consume(3, "owner/repo")?.action)
        assertNull(gate.consume(3, "owner/repo"))
    }
    @Test fun rejectionAndClearInvalidateProposals() {
        val gate = WriteConfirmations()
        gate.offer(PendingGithubWrite(3, "Agent", "owner/repo", action)); gate.reject(3)
        assertNull(gate.consume(3, "owner/repo"))
        gate.offer(PendingGithubWrite(4, "Agent", "owner/repo", action)); gate.clear()
        assertNull(gate.consume(4, "owner/repo"))
    }
    @Test fun invalidPathsCannotChangeApiDestination() {
        for (path in listOf("../secret", "a/../b", "/root", "a?ref=other", "a%2fb"))
            assertThrows(IllegalArgumentException::class.java) { GithubAction(GithubOperation.READ_FILE, path).validate() }
    }
    @Test fun writeNeedsConfirmationAndCreatesOnlyExactApprovedBody() = runTest {
        val server = MockWebServer()
        try {
            val api = Retrofit.Builder().baseUrl(server.url("/")).addConverterFactory(GsonConverterFactory.create()).build().create(GithubApi::class.java)
            val capability = GithubCapability(api) { "github-only" }
            try { capability.read("owner/repo", action); fail("write must be refused") } catch (_: IllegalArgumentException) {}
            assertEquals(0, server.requestCount)
            val gate = WriteConfirmations(); gate.offer(PendingGithubWrite(1, "Agent", "owner/repo", action))
            server.enqueue(MockResponse().setBody("{\"number\":1}"))
            capability.confirm(1, "owner/repo", gate)
            val request = server.takeRequest()
            assertEquals("/repos/owner/repo/issues", request.path)
            assertEquals("Bearer github-only", request.getHeader("Authorization"))
            assertTrue(request.body.readUtf8().contains("Exact proposal"))
            try { capability.confirm(1, "owner/repo", gate); fail("no replay") } catch (_: IllegalStateException) {}
            assertEquals(1, server.requestCount)
            val file = GithubAction(GithubOperation.CREATE_FILE, path = "note.txt", body = "approved content")
            gate.offer(PendingGithubWrite(2, "Agent", "owner/repo", file))
            server.enqueue(MockResponse().setBody("{\"content\":{\"path\":\"note.txt\"}}"))
            capability.confirm(2, "owner/repo", gate)
            val createFile = server.takeRequest()
            assertEquals("PUT", createFile.method)
            assertEquals("/repos/owner/repo/contents/note.txt", createFile.path)
            val payload = JsonParser.parseString(createFile.body.readUtf8()).asJsonObject
            assertFalse(payload.has("sha")) // Existing files cannot be overwritten by this capability.
            assertEquals("approved content", String(Base64.getDecoder().decode(payload["content"].asString)))
            server.enqueue(MockResponse().setBody("{\"encoding\":\"base64\",\"content\":\"aGVsbG8=\"}"))
            assertEquals("hello", capability.read("owner/repo", GithubAction(GithubOperation.READ_FILE, "note.txt")))
            assertEquals("GET", server.takeRequest().method)
            server.enqueue(MockResponse().setBody("[]"))
            assertEquals("[]", capability.read("owner/repo", GithubAction(GithubOperation.LIST_ISSUES)))
            assertEquals("/repos/owner/repo/issues?per_page=20", server.takeRequest().path)
        } finally { server.shutdown() }
    }
}
