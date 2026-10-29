import KodifChat
import SwiftUI

@main
struct ExampleApp: App {
    init() {
        let info = Bundle.main.infoDictionary ?? [:]
        let integrationId = info["KodifIntegrationId"] as? String ?? "YOUR_INTEGRATION_ID"
        let environment: KodifChat.Environment
        switch info["KodifEnvironment"] as? String {
        case "production": environment = .production
        case "staging": environment = .staging
        default: environment = .development
        }
        KodifChat.configure(integrationId: integrationId, environment: environment)
        KodifChat.voiceEnabled = true
        KodifChat.customContext = ["app": "kodif-chat-example"]
        // Anonymous shopper. A signed-in app fetches a JWT signed by its backend here:
        // KodifChat.tokenProvider = { completion in MyBackend.chatToken { completion($0) } }
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
