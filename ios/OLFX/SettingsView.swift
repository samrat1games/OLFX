import SwiftUI

/// True when Husk really holds executable memory. On iOS 26+ a debugger merely being attached is not enough.
enum JITState {
    static var hasRegion: Bool {
        JITBootstrap.canGrantOwnJIT ? JITBootstrap.ready : (JITBootstrap.prewarmed || JITBootstrap.isLive)
    }
}

/// Deliberately small: language and JIT.
struct OLFXSettingsView: View {
    @EnvironmentObject private var tracking: TrackingStore
    @ObservedObject private var lang = Lang.shared
    @ObservedObject private var jit = JITCoordinator.shared
    @AppStorage(JITCoordinator.autoEnableKey) private var jitAuto = false
    @State private var logFiles: [URL]?

    var body: some View {
        NavigationStack {
            ZStack {
                AuroraBackground()
                ScrollView { VStack(spacing: 16) {
                    card {
                        Picker(T("language"), selection: $lang.current) {
                            ForEach(Language.allCases) { Text($0.title).tag($0) }
                        }.pickerStyle(.menu)
                    }
                    card {
                        NavigationLink { PhoneControllerView() } label: {
                            Label("Телефоны-контроллеры", systemImage: "iphone")
                        }
                    }
                    card {
                        Text(T("handsSection")).font(.headline)
                        Toggle(T("enableHands"), isOn: tracking.$handsOn)
                        Toggle(T("swapHands"), isOn: tracking.$swapHands)
                        Toggle(T("sixdof"), isOn: tracking.$sixDoF)
                        Picker(T("modeTitle"), selection: tracking.$modeRaw) {
                            Text(T("modeFull")).tag("full")
                            Text(T("modeGamepad")).tag("gamepad")
                        }.onChange(of: tracking.modeRaw) { _ in tracking.modeChanged() }
                        Toggle(T("xrOn"), isOn: tracking.$xrInGames)
                        Toggle(T("ovrpGLES"), isOn: tracking.$ovrpGLES)
                        NavigationLink { HandTrackingView() } label: {
                            Label(T("tracking"), systemImage: "hand.raised")
                        }
                    }
                    card {
                        HStack {
                            Text("JIT"); Spacer()
                            Label(JITState.hasRegion ? T("on") : T("off"), systemImage: JITState.hasRegion ? "checkmark.circle.fill" : "xmark.circle")
                                .foregroundStyle(JITState.hasRegion ? .green : .orange)
                        }
                        if jit.busy { HStack { ProgressView(); Text(jit.status ?? T("turningJit")).font(.footnote) } }
                        if let e = jit.error { Text(e).font(.footnote).foregroundStyle(.red) }
                        if JITBootstrap.debuggedFlag && !JITState.hasRegion {
                            Text(T("jitContainer")).font(.footnote).foregroundStyle(.secondary)
                        }
                        Button { jit.enable() } label: { Label(T("jitEnable"), systemImage: "bolt.fill").frame(maxWidth: .infinity) }
                            .buttonStyle(.borderedProminent).disabled(JITState.hasRegion || jit.busy)
                        Toggle(T("jitAuto"), isOn: $jitAuto)
                    }
                    card {
                        Button { HuskLog.flushNow(); logFiles = [HuskLog.logFileURL, HuskLog.nativeLogURL, HuskLog.logFileURL.deletingLastPathComponent().appendingPathComponent("husk-prev.log"), HuskLog.previousNativeLogURL].filter { FileManager.default.fileExists(atPath: $0.path) } } label: {
                            Label(T("shareLog"), systemImage: "square.and.arrow.up").frame(maxWidth: .infinity)
                        }.buttonStyle(.bordered)
                    }
                    card {
                        HStack(spacing: 12) {
                            Image("AboutIcon").resizable().scaledToFit().frame(width: 56, height: 56)
                                .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
                            VStack(alignment: .leading, spacing: 2) {
                                Text("OLFX").font(.headline)
                                Text("\(T("version")) \(Bundle.main.object(forInfoDictionaryKey: "OLFXVersion") as? String ?? "1.7.0") · iOS").font(.footnote).foregroundStyle(.secondary)
                            }
                            Spacer()
                        }
                        Text(T("developers")).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                        Text("Клод тапочки — @Beketov_Samrat\nJoaolimz\nAkunDiscoRemake — @nanygata22").font(.footnote)
                    }
                }.padding(16) }
            }
            .navigationTitle(T("settings"))
            .id(jit.attachGeneration)
            .sheet(isPresented: Binding(get: { logFiles != nil }, set: { if !$0 { logFiles = nil } })) { ShareSheet(items: logFiles ?? []) }
        }
    }
    private func card<C: View>(@ViewBuilder _ c: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 12, content: c).padding(16).glass(cornerRadius: 24)
    }
}
