import UIKit
import SwiftUI
import Shared

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Self.Context) -> UIViewController {
        #if DEBUG
        let demoMode = MainViewControllerKt.isComposeDemoModeEnabled()
        #else
        let demoMode = false
        #endif
        return MainViewControllerKt.MainViewController(demoMode: demoMode)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Self.Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea()
            .onOpenURL { url in
                IncomingInvitationLinksKt.receiveIncomingInvitationUrl(url: url.absoluteString)
            }
    }
}
