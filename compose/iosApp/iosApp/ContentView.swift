import UIKit
import SwiftUI
import Shared

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Self.Context) -> UIViewController {
        let apiOrigin = Bundle.main.object(forInfoDictionaryKey: "FT_API_ORIGIN") as? String ?? ""
        return MainViewControllerKt.MainViewController(apiOrigin: apiOrigin)
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
