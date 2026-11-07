import SwiftUI

@main
struct OLFXApp: App {
    @StateObject private var tracking = TrackingStore()
    @StateObject private var library = Library()
    var body: some Scene {
        WindowGroup { RootView().environmentObject(tracking).environmentObject(library).tint(.indigo) }
    }
}

struct RootView: View {
    var body: some View {
        TabView {
            GamesView().tabItem { Label("Games", systemImage: "gamecontroller") }
            GamesView(favoritesOnly: true).tabItem { Label("Favorite", systemImage: "star") }
            HandTrackingView().tabItem { Label("Hand-Tracking", systemImage: "hand.raised") }
        }
    }
}
