import SwiftUI
import UniformTypeIdentifiers

/// One entry in OLFX's library. The APK itself lives in Husk's store (`tlID`), so there is only one copy on disk.
struct GameItem: Identifiable, Codable, Hashable {
    var id = UUID()
    var name: String
    var tlID: String?
    var size: Int64
}

@MainActor
final class Library: ObservableObject {
    @Published var games: [GameItem] = [] { didSet { save() } }
    @Published var favs: Set<UUID> = [] { didSet { UserDefaults.standard.set(favs.map(\.uuidString), forKey: "favs") } }
    private static let index: URL = {
        let d = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d.appendingPathComponent("olfx-library.json")
    }()
    private static let staging: URL = {
        let d = FileManager.default.temporaryDirectory.appendingPathComponent("olfx-import")
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }()
    init() {
        if let d = try? Data(contentsOf: Library.index), let g = try? JSONDecoder().decode([GameItem].self, from: d) { games = g }
        favs = Set((UserDefaults.standard.stringArray(forKey: "favs") ?? []).compactMap(UUID.init))
    }
    private func save() { try? JSONEncoder().encode(games).write(to: Library.index) }

    /// Picked from Files, or opened from another app: copy to a private spot, then hand over to Husk's store (moved there).
    func add(_ url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        let dest = Library.staging.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try? FileManager.default.createDirectory(at: dest, withIntermediateDirectories: true)
        let file = dest.appendingPathComponent(url.lastPathComponent)
        let ok = (try? FileManager.default.copyItem(at: url, to: file)) != nil
        if scoped { url.stopAccessingSecurityScopedResource() }
        guard ok else { return }
        Task {
            if let app = await GameRunner.shared.add(file) {
                games.append(GameItem(name: app.label, tlID: app.id, size: GameRunner.shared.bytes(app)))
            }
            try? FileManager.default.removeItem(at: dest)
        }
    }
    func remove(_ g: GameItem) {
        if let id = g.tlID { GameRunner.shared.remove(tlID: id) }
        games.removeAll { $0 == g }; favs.remove(g.id)
    }
}

extension UTType { static let apk = UTType(filenameExtension: "apk") ?? .data }

struct GamesView: View {
    @EnvironmentObject var lib: Library
    @ObservedObject private var lang = Lang.shared
    @State private var importing = false
    var favoritesOnly = false
    private var shown: [GameItem] { favoritesOnly ? lib.games.filter { lib.favs.contains($0.id) } : lib.games }

    var body: some View {
        NavigationStack {
            ZStack {
                AuroraBackground()
                ScrollView {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 150, maximum: 230), spacing: 14, alignment: .top)], spacing: 16) {
                        ForEach(shown) { g in NavigationLink(value: g) { GameCard(game: g).contentShape(Rectangle()) }.buttonStyle(.plain) }
                    }.padding(14)
                    if shown.isEmpty {
                        Text(favoritesOnly ? T("noFav") : T("addHint")).foregroundStyle(.secondary).padding(.top, 120)
                    }
                }
            }
            .navigationTitle(favoritesOnly ? T("favorite") : T("games"))
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

/// A compact Quest-style tile: the cover (the app's icon blown up and softened, with the sharp icon on it), the name under it
/// with the small icon on its left.
struct GameCard: View {
    @EnvironmentObject var lib: Library
    @ObservedObject private var store = TranslationLayerStore.shared
    let game: GameItem
    private var app: TLApp? { store.apps.first { $0.id == game.tlID } }
    private var icon: UIImage? { app?.iconPath.flatMap { UIImage(contentsOfFile: $0) } }
    private var title: String { app?.label ?? game.name }
    var body: some View {
        VStack(spacing: 7) {
            ZStack {
                if let icon {
                    Image(uiImage: icon).resizable().scaledToFill().scaleEffect(2.6).blur(radius: 26).overlay(Color.black.opacity(0.22))
                    Image(uiImage: icon).resizable().scaledToFit().frame(width: 56, height: 56).clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .shadow(radius: 8)
                } else {
                    LinearGradient(colors: [.indigo, .purple], startPoint: .topLeading, endPoint: .bottomTrailing)
                    Text(String(title.prefix(1)).uppercased()).font(.system(size: 40, weight: .bold, design: .rounded)).foregroundStyle(.white)
                }
            }
            .aspectRatio(16.0 / 10.0, contentMode: .fit)
            .frame(maxWidth: .infinity)
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(alignment: .topTrailing) { if lib.favs.contains(game.id) { Image(systemName: "star.fill").font(.caption).foregroundStyle(.yellow).padding(7) } }
            HStack(spacing: 5) {
                if let icon { Image(uiImage: icon).resizable().scaledToFill().frame(width: 16, height: 16).clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous)) }
                Text(title).font(.caption.weight(.medium)).lineLimit(1)
            }
        }
        .contentShape(Rectangle())
        .onAppear { redrawIconIfMissing() }
    }
    /// The icon is drawn from the APK when it is added; if that left nothing, draw it again.
    private static var tried = Set<String>()       // once per game per run: a reload on every appearance made the grid redraw under the finger
    private func redrawIconIfMissing() {
        guard let app, icon == nil, Self.tried.insert(app.id).inserted else { return }
        let dir = TranslationLayer.root.appendingPathComponent(app.id, isDirectory: true)
        try? FileManager.default.removeItem(at: dir.appendingPathComponent("icon-version.txt"))
        Task.detached { TranslationLayerStore.refreshIcon(app); await MainActor.run { store.reload() } }
    }
}

struct DetailView: View {
    @EnvironmentObject var lib: Library
    @EnvironmentObject var tracking: TrackingStore
    @ObservedObject private var lang = Lang.shared
    @Environment(\.dismiss) var dismiss
    let game: GameItem
    @State private var quality = TLAppSettings.Resolution.medium
    var body: some View {
        ZStack {
            AuroraBackground()
            ScrollView { VStack(spacing: 14) {
                GameCard(game: game).frame(maxWidth: 300)
                Button { if let id = game.tlID { GameRunner.shared.play(tlID: id, tracking: tracking) } } label: {
                    Label(T("play"), systemImage: "play.fill").frame(maxWidth: .infinity).padding(.vertical, 4)
                }.buttonStyle(.borderedProminent).controlSize(.large).frame(maxWidth: 300)
                HStack(spacing: 10) {
                    Button { if lib.favs.contains(game.id) { lib.favs.remove(game.id) } else { lib.favs.insert(game.id) } } label: {
                        Image(systemName: lib.favs.contains(game.id) ? "star.fill" : "star").frame(maxWidth: .infinity)
                    }
                    Menu {
                        Picker(T("quality"), selection: $quality) {
                            Text(T("qFast")).tag(TLAppSettings.Resolution.low); Text(T("qBalanced")).tag(TLAppSettings.Resolution.medium); Text(T("qSharp")).tag(TLAppSettings.Resolution.high)
                        }
                    } label: { Image(systemName: "speedometer").frame(maxWidth: .infinity) }
                    Button(role: .destructive) { lib.remove(game); dismiss() } label: { Image(systemName: "trash").frame(maxWidth: .infinity) }
                }.buttonStyle(.bordered).controlSize(.regular).frame(maxWidth: 300)
                .onChange(of: quality) { q in if let id = game.tlID { var st = TLAppSettings.load(id); st.resolution = q; st.save(id) } }
            }.padding(18).frame(maxWidth: .infinity) }
        }
        .navigationTitle(game.name).navigationBarTitleDisplayMode(.inline)
        .onAppear { if let id = game.tlID { quality = TLAppSettings.load(id).resolution } }
    }
}
