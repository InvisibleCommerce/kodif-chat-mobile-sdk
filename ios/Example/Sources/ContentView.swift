import KodifChat
import SwiftUI

struct ContentView: View {
    @StateObject private var chat = ChatEvents()
    @State private var showChat = false

    var body: some View {
        VStack(spacing: 16) {
            if let status = chat.status {
                Text(status).font(.footnote).foregroundColor(.secondary)
            }
            Button(chat.unreadCount > 0 ? "Chat with us (\(chat.unreadCount))" : "Chat with us") {
                chat.status = nil
                showChat = true
            }
            .buttonStyle(.borderedProminent)
        }
        .padding()
        .fullScreenCover(isPresented: $showChat) {
            KodifChatView().ignoresSafeArea()
        }
        .onAppear {
            KodifChat.delegate = chat
            chat.unreadCount = KodifChat.unreadCount
            // From the first screen, not at launch: the chat starts once this screen is up.
            KodifChat.preload()
        }
    }
}

@MainActor
final class ChatEvents: ObservableObject, KodifChatDelegate {
    @Published var unreadCount = 0
    @Published var status: String?

    func kodifChat(unreadCountDidChange count: Int) {
        unreadCount = count
    }

    func kodifChatUnavailable() {
        status = "Chat is unavailable"
    }
}
