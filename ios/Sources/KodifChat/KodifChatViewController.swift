import UIKit
import WebKit

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

    override public func loadView() {
        view = UIView()
        view.backgroundColor = .white
    }

    override public func viewDidLoad() {
        super.viewDidLoad()
        let center = NotificationCenter.default
        center.addObserver(
            self, selector: #selector(keyboardWillChangeFrame(_:)),
            name: UIResponder.keyboardWillChangeFrameNotification, object: nil
        )
        center.addObserver(
            self, selector: #selector(keyboardWillChangeFrame(_:)),
            name: UIResponder.keyboardWillHideNotification, object: nil
        )
    }

    override public func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        attachWebView()
    }

    override public func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if dismissWhenPresented {
            dismissWhenPresented = false
            dismissChat()
            return
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
            return
        }
        if let onCloseRequest {
            onCloseRequest()
        } else {
            presentingViewController?.dismiss(animated: true)
        }
    }

    private func attachWebView() {
        guard let session = KodifChat.sharedSession() else { return }
        let webView = session.attach(self)
        guard webView.superview !== view else { return }
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

    /// Keeps the web view's bottom on the keyboard's top, so the page's layout viewport shrinks like Android's
    /// adjustResize and the composer stays visible.
    @objc private func keyboardWillChangeFrame(_ notification: Notification) {
        guard let bottomConstraint, let window = view.window,
              let frame = (notification.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? NSValue)?.cgRectValue
        else { return }
        let keyboard = view.convert(frame, from: window.screen.coordinateSpace)
        let overlap = notification.name == UIResponder.keyboardWillHideNotification
            ? 0
            : max(0, view.bounds.maxY - keyboard.minY)
        guard bottomConstraint.constant != -overlap else { return }
        bottomConstraint.constant = -overlap

        let duration = (notification.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? NSNumber)?
            .doubleValue ?? 0.25
        let curve = (notification.userInfo?[UIResponder.keyboardAnimationCurveUserInfoKey] as? NSNumber)?
            .uintValue ?? UInt(UIView.AnimationCurve.easeInOut.rawValue)
        UIView.animate(
            withDuration: duration,
            delay: 0,
            options: [UIView.AnimationOptions(rawValue: curve << 16), .beginFromCurrentState]
        ) {
            self.view.layoutIfNeeded()
        }
    }
}
