package ai.kodif.chat.example

import ai.kodif.chat.KodifChat
import android.app.Application

class ExampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val environment = when (BuildConfig.KODIF_ENVIRONMENT) {
            "production" -> KodifChat.Environment.Production
            "staging" -> KodifChat.Environment.Staging
            else -> KodifChat.Environment.Development
        }
        KodifChat.configure(this, BuildConfig.KODIF_INTEGRATION_ID, environment)
        KodifChat.voiceEnabled = true
        KodifChat.customContext = mapOf("app" to "kodif-chat-example", "appVersion" to BuildConfig.VERSION_NAME)
        // Anonymous shopper. A signed-in app fetches a JWT signed by its backend here.
        KodifChat.tokenProvider = null
    }
}
