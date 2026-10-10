package com.bitchat.android.ui

import android.app.Application
import android.content.Context
import android.content.MutableContextWrapper
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.R
import com.bitchat.android.onboarding.PermissionManager
import com.bitchat.android.services.AppStateStore
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ChineseInterfaceBehaviorTest {
    private fun localizedContext(tag: String): Context {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList(Locale.forLanguageTag(tag)))
        return context.createConfigurationContext(configuration)
    }

    @After
    fun clearState() = AppStateStore.clear()

    @Test
    fun `Chinese command menu and channel errors use translated resources`() {
        for (tag in listOf("zh", "zh-CN", "zh-TW")) {
            val context = localizedContext(tag)
            val scope = TestScope()
            val state = ChatState(scope)
            val messages = MessageManager(state, context)
            val data = DataManager(context)
            val channels = ChannelManager(state, messages, data, scope)
            val privateChats = PrivateChatManager(state, messages, data, mock())
            val commands = CommandProcessor(state, messages, channels, privateChats)

            commands.updateCommandSuggestions("/")
            assertTrue(state.getCommandSuggestionsValue().isNotEmpty())
            assertTrue(state.getCommandSuggestionsValue().all { suggestion ->
                suggestion.description.any { it.code in 0x4E00..0x9FFF }
            })
            assertFalse(channels.joinChannel("", myPeerID = "synthetic-peer"))
            assertEquals("频道名称无效".takeIf { tag != "zh-TW" } ?: "頻道名稱無效",
                state.getMessagesValue().last().content)
            assertTrue(PermissionManager(context).getCategorizedPermissions().all { category ->
                category.description.any { it.code in 0x4E00..0x9FFF }
            })
        }
    }

    @Test
    fun `system feedback follows locale changes instead of caching English`() {
        val context = MutableContextWrapper(localizedContext("en"))
        val messages = MessageManager(ChatState(TestScope()), context)
        assertEquals("Incorrect channel password", messages.getString(R.string.topic_incorrect_password))
        context.baseContext = localizedContext("zh-CN")
        assertEquals("频道密码错误", messages.getString(R.string.topic_incorrect_password))
        assertEquals("已加入频道 #sample", messages.getString(R.string.command_joined, "#sample"))
        context.baseContext = localizedContext("en")
        assertEquals("Incorrect channel password", messages.getString(R.string.topic_incorrect_password))
    }
}
