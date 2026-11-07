import SwiftUI
import UniformTypeIdentifiers

struct GameItem: Identifiable, Codable, Hashable {
    var id = UUID()
    var name: String
    var file: String   // file name inside Documents/APKs
    var size: Int64
}

final class Library: ObservableObject {
    @Published var games: [GameItem] = [] { didSet { save() } }
    @Published var favs: Set<UUID> = [] { didSet { UserDefaults.standard.set(favs.map(\.uuidString), forKey: "favs") } }
    static let dir: URL = {
        let d = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("APKs")
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }()
    private var index: URL { Library.dir.appendingPathComponent("index.json") }
    init() {
        if let d = try? Data(contentsOf: index), let g = try? JSONDecoder().decode([GameItem].self, from: d) { games = g }
        favs = Set((UserDefaults.standard.stringArray(forKey: "favs") ?? []).compactMap(UUID.init))
    }
    private func save() { try? JSONEncoder().encode(games).write(to: index) }

    func add(_ url: URL) {
        let scoped = url.startAccessingSecurityScopedResource(); defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let dest = Library.dir.appendingPathComponent(url.lastPathComponent)
        try? FileManager.default.removeItem(at: dest)
        guard (try? FileManager.default.copyItem(at: url, to: dest)) != nil else { return }
        let size = (try? FileManager.default.attributesOfItem(atPath: dest.path)[.size] as? Int64) ?? 0
        games.append(GameItem(name: url.deletingPathExtension().lastPathComponent, file: dest.lastPathComponent, size: size ?? 0))
    }
    func remove(_ g: GameItem) {
        try? FileManager.default.removeItem(at: Library.dir.appendingPathComponent(g.file))
        games.removeAll { $0 == g }; favs.remove(g.id)
    }
    func url(_ g: GameItem) -> URL { Library.dir.appendingPathComponent(g.file) }
}

extension UTType { static let apk = UTType(filenameExtension: "apk") ?? .data }

struct GamesView: View {
    @EnvironmentObject var lib: Library
    @State private var importing = false
    var favoritesOnly = false
    private var shown: [GameItem] { favoritesOnly ? lib.games.filter { lib.favs.contains($0.id) } : lib.games }

    var body: some View {
        NavigationStack {
            ZStack {
                AuroraBackground()
                ScrollView {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 16)], spacing: 16) {
                        ForEach(shown) { g in NavigationLink(value: g) { GameCard(game: g) } .buttonStyle(.plain) }
                    }.padding(16)
                    if shown.isEmpty {
                        Text(favoritesOnly ? "No favourites yet" : "Tap + to add an APK").foregroundStyle(.secondary).padding(.top, 120)
                    }
                }
            }
            .navigationTitle(favoritesOnly ? "Favorite" : "Games")
            .navigationDestination(for: GameItem.self) { DetailView(game: $0) }
            .toolbar {
                if !favoritesOnly { ToolbarItem(placement: .primaryAction) { Button { importing = true } label: { Image(systemName: "plus") } } }
            }
            .fileImporter(isPresented: $importing, allowedContentTypes: [.apk, .data], allowsMultipleSelection: true) { r in
                if case .success(let urls) = r { urls.forEach(lib.add) }
            }
        }
    }
}

struct GameCard: View {
    @EnvironmentObject var lib: Library
    let game: GameItem
    var body: some View {
        VStack(spacing: 10) {
            ZStack {
                RoundedRectangle(cornerRadius: 22, style: .continuous)
                    .fill(LinearGradient(colors: [.indigo, .purple], startPoint: .topLeading, endPoint: .bottomTrailing))
                Text(String(game.name.prefix(1)).uppercased()).font(.system(size: 44, weight: .bold, design: .rounded)).foregroundStyle(.white)
            }.frame(width: 90, height: 90)
            Text(game.name).font(.subheadline.weight(.semibold)).lineLimit(2).multilineTextAlignment(.center)
            Text(ByteCountFormatter.string(fromByteCount: game.size, countStyle: .file)).font(.caption2).foregroundStyle(.secondary)
        }
        .padding(14).frame(maxWidth: .infinity)
        .glass(cornerRadius: 28, interactive: true)
        .overlay(alignment: .topTrailing) { if lib.favs.contains(game.id) { Image(systemName: "star.fill").foregroundStyle(.yellow).padding(12) } }
    }
}

struct DetailView: View {
    @EnvironmentObject var lib: Library
    @Environment(\.dismiss) var dismiss
    let game: GameItem
    @State private var share = false
    var body: some View {
        ZStack {
            AuroraBackground()
            VStack(spacing: 16) {
                GameCard(game: game).frame(width: 220)
                Button { share = true } label: { Label("Play with Husk", systemImage: "play.fill").frame(maxWidth: .infinity).padding(.vertical, 6) }
                    .buttonStyle(.borderedProminent).controlSize(.large)
                Button { if lib.favs.contains(game.id) { lib.favs.remove(game.id) } else { lib.favs.insert(game.id) } } label: {
                    Label(lib.favs.contains(game.id) ? "Remove from favourites" : "Add to favourites", systemImage: "star").frame(maxWidth: .infinity)
                }.buttonStyle(.bordered).controlSize(.large)
                Button(role: .destructive) { lib.remove(game); dismiss() } label: { Label("Delete", systemImage: "trash").frame(maxWidth: .infinity) }
                    .buttonStyle(.bordered).controlSize(.large)
                Text("Opens the APK in Husk (install Husk via SideStore first).").font(.footnote).foregroundStyle(.secondary)
            }.padding(24)
        }
        .navigationTitle(game.name).navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $share) { ShareSheet(items: [lib.url(game)]) }
    }
}

/// Hands the APK to Husk through the system share sheet ("Open in Husk").
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: items, applicationActivities: nil) }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}
