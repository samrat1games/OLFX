import SwiftUI

/// Library of Android games. Launching goes through the Husk translation layer (see docs/ios-husk.md).
struct GameItem: Identifiable { let id = UUID(); let name: String; let apkPath: String }

struct GamesView: View {
    @State private var games: [GameItem] = []
    var body: some View {
        NavigationStack {
            List(games) { g in Text(g.name) }
                .overlay { if games.isEmpty { Text("Add an APK to start").foregroundStyle(.secondary) } }
                .navigationTitle("Games")
        }
    }
}

struct FavoritesView: View {
    var body: some View { NavigationStack { Text("No favourites yet").foregroundStyle(.secondary).navigationTitle("Favorite") } }
}
