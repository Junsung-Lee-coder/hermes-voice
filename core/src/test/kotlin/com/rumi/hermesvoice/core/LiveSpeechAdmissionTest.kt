package com.rumi.hermesvoice.core

import com.rumi.hermesvoice.core.settings.WatchSettings
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*

class LiveSpeechAdmissionTest {
    @Test fun actualChatSendSuppressesLegacySpeechByPhoneOriginNotWatchRelayMode() = runBlocking {
        val h=CoreHarness()
        try {
            val id=h.core.sessions.createConversation("Work","work","").storedSessionId
            var legacy=0
            val chat=ChatService(h.core.sessions,typedFinal={_,_,_,_->legacy++;false},liveMode={h.settings.watchSettings().phoneLiveEnabled})
            for(phone in listOf(false,true)) for(watch in listOf(false,true)) {
                h.settings.saveWatchSettings(WatchSettings(phoneLiveEnabled=phone,watchLiveEnabled=watch))
                val before=legacy
                assertTrue(chat.send(id,"text $phone $watch") is ChatSendResult.Replied)
                assertEquals(if(phone) 0 else 1,legacy-before)
            }
            val before=legacy
            assertTrue(chat.sendLive(id,"voice work","User: voice work") is ChatSendResult.Replied)
            assertEquals(before,legacy)
            assertFalse(h.fake.restLog.any {it.contains("/api/audio/speak")})
            assertEquals(5,h.fake.prompts.size)
        } finally {h.close()}
    }
    @Test fun alreadyAdmittedOffOperationRetainsLegacyChoiceAndIsNotResubmittedOnModeChange() = runBlocking {
        val h=CoreHarness()
        try {
            val id=h.core.sessions.createConversation("Work","work","").storedSessionId
            h.fake.turnBehavior={"hold"}
            var legacy=0
            val chat=ChatService(h.core.sessions,typedFinal={_,_,_,_->legacy++;false},liveMode={h.settings.watchSettings().phoneLiveEnabled})
            val send=async(Dispatchers.Default){chat.send(id,"already admitted legacy")}
            withTimeout(8000){while(!h.fake.heldTurns.containsKey(id)) delay(10)}
            h.settings.saveWatchSettings(WatchSettings(phoneLiveEnabled=true))
            h.fake.finishHeldTurn(id)
            assertTrue(withTimeout(8000){send.await()} is ChatSendResult.Replied)
            assertEquals(1,legacy);assertEquals(1,h.fake.prompts.size)
        } finally {h.close()}
    }
}
