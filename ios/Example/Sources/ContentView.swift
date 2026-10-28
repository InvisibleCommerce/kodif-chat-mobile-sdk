import KodifChat
import SwiftUI

struct ContentView: View {
    @StateObject private var events = ChatEvents()
    @State private var showChat = false

    var body: some View {
        VStack(spacing: 16) {
            if let badge = events.badge {
                Text(badge).font(.footnote).foregroundColor(.secondary)
            }
            Button("Chat with us") {
                events.badge = nil
                showChat = true
            }
            .buttonStyle(.borderedProminent)
        }
        .padding()
        .fullScreenCover(isPresented: $showChat) {
            KodifChatView().ignoresSafeArea()
        }
        .onAppear { KodifChat.delegate = events }
    }
}

@MainActor
final class ChatEvents: ObservableObject, KodifChatDelegate {
    @Published var badge: String?

    func kodifChat(didReceiveMessage preview: String, author: KodifChat.Author) {
        badge = "New reply: \(preview)"
    }

    func kodifChatUnavailable() {
        badge = "Chat is unavailable"
    }
}
