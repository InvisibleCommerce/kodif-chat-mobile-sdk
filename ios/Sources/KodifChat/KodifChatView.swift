import SwiftUI

/// The chat for SwiftUI. Show it with `.fullScreenCover` (or `.sheet`); closing the chat dismisses it.
///
/// ```swift
/// .fullScreenCover(isPresented: $showChat) { KodifChatView().ignoresSafeArea() }
/// ```
public struct KodifChatView: UIViewControllerRepresentable {
    public init() {}

    public func makeUIViewController(context: Context) -> KodifChatViewController {
        KodifChatViewController()
    }

    public func updateUIViewController(_ controller: KodifChatViewController, context: Context) {
        let dismiss = context.environment.dismiss
        controller.onCloseRequest = { dismiss() }
    }
}
