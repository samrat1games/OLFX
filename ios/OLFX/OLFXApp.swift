import SwiftUI

@main
struct OLFXApp: App {
    @StateObject private var tracking = TrackingStore()
    var body: some Scene {
        WindowGroup { RootView().environmentObject(tracking).tint(.indigo) }
    }
}

struct RootView: View {
    var body: some View {
        TabView {
            GamesView().tabItem { Label("Games", systemImage: "gamecontroller") }
            FavoritesView().tabItem { Label("Favorite", systemImage: "star") }
            HandTrackingView().tabItem { Label("Hand-Tracking", systemImage: "hand.raised") }
        }
    }
}
