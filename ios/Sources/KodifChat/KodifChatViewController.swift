import UIKit

/// The chat, full screen. Present it modally (``KodifChat/present(from:animated:)`` does), after
/// ``KodifChat/configure(integrationId:environment:)``.
@MainActor
public final class KodifChatViewController: UIViewController {
    /// Set by ``KodifChatView`` so a close dismisses the SwiftUI presentation.
    var onCloseRequest: (() -> Void)?

    private var bottomConstraint: NSLayoutConstraint?
    private var dismissWhenPresented = false

    public init() {
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .fullScreen
    }

    public required init?(coder: NSCoder) {
        super.init(coder: coder)
        modalPresentationStyle = .fullScreen
    }

    override public func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .white
        for name in [UIResponder.keyboardWillChangeFrameNotification, UIResponder.keyboardWillHideNotification] {
            NotificationCenter.default.addObserver(self, selector: #selector(keyboardWillChange), name: name, object: nil)
        }
    }

    override public func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        guard let webView = KodifChat.sharedSession()?.attach(self), webView.superview !== view else { return }
        webView.removeFromSuperview()
        webView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(webView)
        let bottom = webView.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        NSLayoutConstraint.activate([
            webView.topAnchor.constraint(equalTo: view.topAnchor),
            webView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            bottom,
        ])
        bottomConstraint = bottom
    }

    override public func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if dismissWhenPresented {
            dismissWhenPresented = false
            return dismissChat()
        }
        KodifChat.session?.setVisible(true, from: self)
    }

    override public func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        KodifChat.session?.setVisible(false, from: self)
    }

    /// Dismisses the chat (the shopper closed it, or it's unavailable).
    func dismissChat() {
        if isBeingPresented {
            dismissWhenPresented = true
        } else if let onCloseRequest {
            onCloseRequest()
        } else {
            presentingViewController?.dismiss(animated: true)
        }
    }

    /// Keeps the web view's bottom on the keyboard's top, so the layout viewport shrinks (like Android's
    /// adjustResize) and the composer stays visible.
    @objc private func keyboardWillChange(_ notification: Notification) {
        let info = notification.userInfo ?? [:]
        guard let bottomConstraint, let window = view.window,
              let frame = (info[UIResponder.keyboardFrameEndUserInfoKey] as? NSValue)?.cgRectValue
        else { return }
        let keyboard = view.convert(frame, from: window.screen.coordinateSpace)
        let isHiding = notification.name == UIResponder.keyboardWillHideNotification
        bottomConstraint.constant = isHiding ? 0 : -max(0, view.bounds.maxY - keyboard.minY)
        let duration = (info[UIResponder.keyboardAnimationDurationUserInfoKey] as? NSNumber)?.doubleValue ?? 0.25
        let curve = (info[UIResponder.keyboardAnimationCurveUserInfoKey] as? NSNumber)?.uintValue ?? 7
        UIView.animate(withDuration: duration, delay: 0, options: UIView.AnimationOptions(rawValue: curve << 16)) {
            self.view.layoutIfNeeded()
        }
    }
}
