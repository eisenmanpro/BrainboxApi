package com.afrithecus.brainbox.api.push

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.util.UUID

/** Push fan-out: token selection, send and invalid-token pruning. */
class PushFanoutServiceTest {

    private val registry = SimpleMeterRegistry()

    private class FakeStore : DeviceTokenStore {
        val pruned = mutableListOf<String>()
        override fun tokensFor(userId: UUID) = listOf("token-1", "token-2")
        override fun deleteTokens(userId: UUID, tokens: Collection<String>) { pruned += tokens }
    }

    private class CaptureSender(private val invalid: List<String>) : PushSender {
        val calls = mutableListOf<Pair<List<String>, PushMessage>>()
        override fun send(tokens: List<String>, message: PushMessage): List<String> {
            calls += tokens to message
            return invalid
        }
    }

    @Test
    fun `dispatch sends to every token and prunes the invalid ones`() {
        val store = FakeStore()
        val sender = CaptureSender(listOf("token-2"))
        PushFanoutService(store, sender, registry).dispatch(
            UUID.randomUUID(),
            PushMessage(title = "T", message = "M", type = "ATTENDANCE"),
        )
        check(sender.calls.size == 1)
        check(sender.calls.single().first == listOf("token-1", "token-2"))
        check(sender.calls.single().second.type == "ATTENDANCE")
        check(store.pruned == listOf("token-2"))
        check(registry.counter("brainbox.push.tokens", "outcome", "sent").count() == 1.0)
        check(registry.counter("brainbox.push.tokens", "outcome", "invalid").count() == 1.0)
    }

    @Test
    fun `no tokens means no send`() {
        val store = object : DeviceTokenStore {
            override fun tokensFor(userId: UUID) = emptyList<String>()
            override fun deleteTokens(userId: UUID, tokens: Collection<String>) = Unit
        }
        val sender = CaptureSender(emptyList())
        PushFanoutService(store, sender, registry).dispatch(UUID.randomUUID(), PushMessage(title = "T", message = "M"))
        check(sender.calls.isEmpty())
    }
}
