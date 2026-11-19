import SwiftUI
import AVFoundation

/// Husk (the Android runtime) is compiled into this app: `HuskAppDelegate`, `IncomingFiles`, `ContentView`
/// come from ios/third_party/Husk. No separate Husk install is needed.
@_silgen_name("tl_jni_set_trace") func tl_jni_set_trace(_ level: Int32)

enum HuskEngine {
    static func boot() {
        Boot.mark("boot: start")
        tl_jni_set_trace(0)
        // Same order as Husk's own App.init: the log first (it redirects stderr), then JIT, then the trap guard.
        HuskLog.start()
        Boot.mark("boot: HuskLog started")
        XRBridge.install()
        PhoneLink.shared.start()
        Boot.mark("boot: XR runtime installed")   // OpenXR for games, fed by ARKit + Apple Vision
        HuskLog.logFootprint("app-launch")
        JITBootstrap.noteLaunchState()
        JITBootstrap.installTrapGuard()
        Boot.mark("boot: trap guard installed")
        let d = UserDefaults.standard
        if !d.bool(forKey: "husk.autoStart.offByDefault") {
            d.set(false, forKey: "husk.autoStart")
            d.set(true, forKey: "husk.autoStart.offByDefault")
        }
        IncomingFiles.clearLeftovers()
        Task { @MainActor in HuskGamepads.shared.start() }
        Boot.mark("boot: done")
    }
}

@main
struct OLFXApp: App {
    @UIApplicationDelegateAdaptor(HuskAppDelegate.self) private var appDelegate
    @StateObject private var tracking = TrackingStore()
    @StateObject private var library = Library()
    @StateObject private var lang = Lang.shared
    init() { Boot.mark("app: init"); HuskEngine.boot(); Boot.mark("app: init done") }
    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(tracking).environmentObject(library).environmentObject(lang).tint(.indigo)
                // APKs opened from Files / the share sheet go straight into the library.
                .onOpenURL { url in
                    if url.isFileURL, IncomingFiles.extensions.contains(url.pathExtension.lowercased()) { library.add(url); return }
                    if GameLink.handle(url) { return }
                    _ = SigningCertificate.shared.handle(url)
                }
        }
    }
}

@_silgen_name("olfx_take_entitlement_unavailable")
func takeEntitlementUnavailable() -> Int32

private struct OLFXGameView: View {
    let app: TLApp
    @ObservedObject private var runner = GameRunner.shared
    @ObservedObject private var jit = JITCoordinator.shared
    @State private var entitlementUnavailable = false
    @State private var showingRoom = true
    @State private var gameIssue: String?
    @State private var metaCheckFailed = false

    private var roomStatus: String {
        let base = gameIssue ?? runner.launchIssue ?? runner.status ?? T("launchPreparing")
        return metaCheckFailed ? base + "\n" + T("metaUnavailableTitle") : base
    }

    var body: some View {
        ZStack {
            if runner.playing?.id == app.id {
                TLAttemptView(app: app)
                    .allowsHitTesting(!showingRoom)
                    .accessibilityHidden(showingRoom)
                    .task { await observeLaunch() }
            }
            if showingRoom {
                VRLaunchRoom(title: app.label, status: roomStatus,
                             failed: gameIssue != nil || runner.launchIssue != nil, cancel: runner.close)
                    .ignoresSafeArea()
            }
        }
        .background(.black)
        .onAppear {
            HuskOrientation.set(.landscape)
            runner.prepareLaunch(app)
        }
        .sheet(isPresented: $jit.showSetup) { JITSetupFlow() }
        .alert(T("metaUnavailableTitle"), isPresented: $entitlementUnavailable) {
            Button(T("ok"), role: .cancel) {}
        } message: {
            Text(T("metaUnavailableMessage"))
        }
    }

    @MainActor
    private func observeLaunch() async {
        let native = app.report?.runsOnNativeRuntime == true
        let start = CACurrentMediaTime()
        var firstNativeFrame: CFTimeInterval?
        var reportedEntitlement = false
        while !Task.isCancelled {
            if !reportedEntitlement, takeEntitlementUnavailable() != 0 {
                reportedEntitlement = true
                metaCheckFailed = true
                if !showingRoom { entitlementUnavailable = true }
            }
            if showingRoom {
                let now = CACurrentMediaTime()
                let xr = olfxLaunchFrame()
                let state = native ? husk_unity_state() : 0
                var exitCode: Int32 = 0
                let failed = native
                    ? state == Int32(HUSK_UNITY_FAILED) || state == Int32(HUSK_UNITY_ENDED)
                    : app.report?.webKind == nil && husk_tl_attempt_done(&exitCode)
                if (native && state == Int32(HUSK_UNITY_RUNNING) && husk_unity_frames() > 0) ||
                    (!native && husk_tl_attempt_frames() > 0) {
                    if firstNativeFrame == nil { firstNativeFrame = now }
                }
                if !failed && (xr == 2 || app.report?.webKind != nil ||
                    (xr == 0 && firstNativeFrame.map { now - $0 >= 1 } == true)) {
                    withAnimation(.easeOut(duration: 0.25)) { showingRoom = false }
                    if metaCheckFailed { entitlementUnavailable = true }
                } else if failed {
                    gameIssue = native ? nativeLaunchIssue() : T("launchFailed")
                } else if gameIssue == nil, now - start >= 40 {
                    gameIssue = T(xr == 1 ? "launchNoStereoFrame" : "launchNoFrame")
                    HuskLog.log("olfx", "launch timed out waiting for a displayed frame (XR state \(xr))")
                }
            }
            if !showingRoom, reportedEntitlement { return }
            do { try await Task.sleep(nanoseconds: showingRoom ? 250_000_000 : 500_000_000) }
            catch { return }
        }
    }

    private func nativeLaunchIssue() -> String {
        let lines = HuskLog.recentLines(200)
        let launch = lines.lastIndex { $0.contains("native: launching") }
        if let launch {
            let current = lines[launch...]
            if current.contains(where: { $0.contains("libmain.so from lib/armeabi-v7a/") }),
               current.contains(where: { $0.contains("NativeLoader.load(Ljava/lang/String;)Z was not registered") }) {
                return T("launchUnityARM32")
            }
        }
        if AppDiagnosis.preflight(app)?.kind == .missingSplits { return T("launchMissingSplits") }
        return T("launchFailed")
    }
}

struct RootView: View {
    @ObservedObject private var lang = Lang.shared
    @ObservedObject private var runner = GameRunner.shared
    @ObservedObject private var jit = JITCoordinator.shared
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        TabView {
            GamesView().tabItem { Label(T("games"), systemImage: "gamecontroller") }
            GamesView(favoritesOnly: true).tabItem { Label(T("favorite"), systemImage: "star") }
            OLFXSettingsView().tabItem { Label(T("settings"), systemImage: "gearshape") }
        }
        .overlay { if runner.launching == nil, let s = runner.status { StatusToast(text: s) } }
        .fullScreenCover(item: $runner.launching, onDismiss: { runner.closed() }) { OLFXGameView(app: $0) }
        .onReceive(NotificationCenter.default.publisher(for: TLUnityUIView.huskStopPlaying)) { _ in runner.close() }
        .sheet(isPresented: Binding(get: { runner.launching == nil && jit.showSetup },
                                    set: { if runner.launching == nil { jit.showSetup = $0 } })) { JITSetupFlow() }
        .alert(T("restartTitle"), isPresented: Binding(get: { runner.restartFor != nil }, set: { if !$0 { runner.restartFor = nil } })) {
            Button(T("restart"), role: .destructive) { if let a = runner.restartFor { runner.confirmRestart(a) } }
            Button(T("cancel"), role: .cancel) {}
        } message: { Text(T("restartMsg")) }
        .alert(runner.message ?? "", isPresented: Binding(get: { runner.message != nil }, set: { if !$0 { runner.message = nil } })) {
            Button(T("ok"), role: .cancel) {}
        }
        .onChange(of: scenePhase) { phase in if phase == .active { runner.foreground() } }
        .onChange(of: jit.attachGeneration) { _ in runner.foreground() }
        .onAppear { Boot.mark("ui: root appeared"); runner.foreground(); Boot.mark("ui: foreground ok"); runner.resumeSwitchIfAny(); Boot.mark("ui: ready") }
        .task {
            if AVCaptureDevice.authorizationStatus(for: .video) == .notDetermined {
                _ = await AVCaptureDevice.requestAccess(for: .video)
            }
        }
    }
}

struct StatusToast: View {
    let text: String
    var body: some View {
        VStack { Spacer(); HStack(spacing: 10) { ProgressView(); Text(text).font(.subheadline) }
            .padding(14).glass(cornerRadius: 22).padding(.bottom, 90) }
        .allowsHitTesting(false)      // never swallow taps meant for the screen underneath
    }
}
