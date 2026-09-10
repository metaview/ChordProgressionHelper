import SwiftUI

/// Navigations-Container, der die moderne API nutzt, wo verfügbar:
/// NavigationStack ab iOS 16, ansonsten Fallback auf NavigationView (ab iOS 15).
/// So bleibt der Code auf iOS 16+ ohne Deprecation-Warnung und läuft zugleich auf iOS 15.x.
/// `.navigationViewStyle(.stack)` erzwingt im Fallback eine einspaltige Darstellung
/// (sonst würde NavigationView auf dem iPad zur Split-View).
struct AppNavigationContainer<Content: View>: View {
    @ViewBuilder var content: () -> Content

    var body: some View {
        if #available(iOS 16.0, *) {
            NavigationStack {
                content()
            }
        } else {
            NavigationView {
                content()
            }
            .navigationViewStyle(.stack)
        }
    }
}
