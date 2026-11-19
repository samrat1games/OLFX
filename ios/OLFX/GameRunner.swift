import SwiftUI

/// Runs Android games on the embedded Husk engine WITHOUT Husk's own screens: adds the APK to Husk's store,
/// turns JIT on when needed, and shows Husk's full-screen game view (`TLAttemptView`) directly.
@_silgen_name("olfx_ovrp_set_gles") func olfx_ovrp_set_gles(_ on: Int32)
@_silgen_name("tl_vk_configure") func tl_vk_configure(_ path: UnsafePointer<CChar>?, _ frameDir: UnsafePointer<CChar>?, _ every: Int32)

@MainActor
final class GameRunner: ObservableObject {
    static let shared = GameRunner()
    @Published var launching: TLApp?
    @Published private(set) var playing: TLApp?
    @Published private(set) var launchIssue: String?
    @Published var message: String?
    @Published var status: String?
    @Published var restartFor: TLApp?
    private var launchTask: Task<Void, Never>?
    private var trackingStarted = false
    private var tracking: TrackingStore?

    private var store: TranslationLayerStore { .shared }
    private var jit: JITCoordinator { .shared }
    /// Called once at start: things Husk's own root view did when it appeared.
    func foreground() {
        jit.enableAtLaunchIfAsked()
        if JITBootstrap.ready { _ = JITBootstrap.prewarm() }
    }

    /// The game Husk was closed to switch to.
    func resumeSwitchIfAny() {
        guard let id = UserDefaults.standard.string(forKey: "husk.switchToGame") else { return }
        UserDefaults.standard.removeObject(forKey: "husk.switchToGame")
        start(id)
    }

    // MARK: adding
    /// Moves the APK into Husk's store (no second copy) and returns what it became.
    func add(_ file: URL) async -> TLApp? {
        let before = Set(store.apps.map(\.id))
        status = T("adding")
        while store.busy != nil { try? await Task.sleep(nanoseconds: 200_000_000) }
        store.add([file], move: true)
        let deadline = Date().addingTimeInterval(120)
        while Date() < deadline {
            try? await Task.sleep(nanoseconds: 250_000_000)
            if store.busy == nil, let new = store.apps.first(where: { !before.contains($0.id) }) {
                status = nil
                var st = TLAppSettings.load(new.id); st.resolution = .medium; st.showStats = false; st.cleanView = true; st.save(new.id)    // balanced by default
                return new
            }
            if store.busy == nil, store.lastError != nil { break }
        }
        status = nil
        if let e = store.lastError { message = e }
        return nil
    }
    func remove(tlID: String) { if let app = store.apps.first(where: { $0.id == tlID }) { store.remove(app) } }
    func app(_ tlID: String?) -> TLApp? { tlID.flatMap { id in store.apps.first { $0.id == id } } }
    func bytes(_ app: TLApp) -> Int64 {
        app.apks.reduce(0) { $0 + ((try? FileManager.default.attributesOfItem(atPath: $1)[.size] as? Int64) ?? 0) }
    }

    // MARK: playing
    func play(tlID: String, tracking: TrackingStore?) {
        HuskLog.log("olfx", "play requested: \(tlID); thermal=\(ProcessInfo.processInfo.thermalState.rawValue); busy=\(launching != nil || launchTask != nil)")
        guard launching == nil, launchTask == nil else {
            status = T("launchBusy")
            DispatchQueue.main.asyncAfter(deadline: .now() + 4) { if self.launching == nil { self.status = nil } }
            return
        }
        self.tracking = tracking
        start(tlID)
    }

    private func start(_ id: String) {
        guard launching == nil, let app = store.apps.first(where: { $0.id == id }) else { return }
        if let loaded = husk_native_loaded_apk().map({ String(cString: $0) }), loaded != app.apks.first {
            restartFor = app
            return
        }
        HuskLog.log("olfx", "starting game: \(app.id)")
        launchIssue = nil
        status = T("launchPreparing")
        launching = app
    }

    func prepareLaunch(_ app: TLApp) {
        guard launching?.id == app.id, launchTask == nil else { return }
        let xr = UserDefaults.standard.object(forKey: "olfx.xr") as? Bool ?? true
        if xr {
            if tracking == nil { tracking = TrackingStore() }
            tracking?.start()
            trackingStarted = true
        }
        launchTask = Task {
            do {
                if app.report?.webKind == nil, !JITLess.enabled, !JITState.hasRegion {
                    status = T("turningJit")
                    if !JITBootstrap.debuggedFlag { jit.enable() }
                    let deadline = CACurrentMediaTime() + 45
                    while !JITState.hasRegion {
                        if JITBootstrap.debuggedFlag || JITBootstrap.ready { _ = JITBootstrap.prewarm() }
                        if JITState.hasRegion { break }
                        if CACurrentMediaTime() >= deadline { launchIssue = T("jitNeeded"); return }
                        try await Task.sleep(nanoseconds: 250_000_000)
                    }
                }
                if app.report?.webKind == nil, JITLess.enabled, SigningCertificate.credentials() != nil {
                    try await JITLess.prepare(apks: app.apks) { text in
                        Task { @MainActor in
                            if self.launching?.id == app.id, self.playing == nil, self.launchIssue == nil {
                                self.status = text
                            }
                        }
                    }
                }
                try Task.checkCancellation()
                guard launching?.id == app.id else { return }
                present(app)
            } catch is CancellationError {
                return
            } catch {
                if !Task.isCancelled { launchIssue = error.localizedDescription }
            }
        }
    }

    /// Vulkan (MoltenVK) is always available to games. Husk wires it for Unreal/SDL itself; this does the same for Unity, so a game built
    /// for Vulkan can start. A game that also has OpenGL ES simply picks whichever it prefers.
    private func present(_ app: TLApp) {
        var display = TLAppSettings.load(app.id)
        display.cleanView = true
        display.showStats = false
        display.save(app.id)
        switch TLAppSettings.load(app.id).resolution {            // the performance choice also sets how many pixels each eye gets
        case .low: XRBridge.setEyeSize(640)
        case .medium: XRBridge.setEyeSize(768)
        default: XRBridge.setEyeSize(896)
        }
        if let fw = Bundle.main.privateFrameworksPath {
            let mvk = fw + "/MoltenVK.framework/MoltenVK"
            husk_ue4_set_vulkan(mvk)                          // Unreal / SDL games (Husk configures Vulkan itself for these)
            mvk.withCString { tl_vk_configure($0, nil, 0) }   // every other engine, Unity included: Husk never does it for them
        }
        olfx_ovrp_set_gles(UserDefaults.standard.bool(forKey: "olfx.ovrpGLES") ? 1 : 0)
        if trackingStarted { StereoLayer.attach() }
        _ = takeEntitlementUnavailable()
        if husk_native_loaded_apk() == nil { olfxResetLaunchFrame() }
        status = T("launchLoading")
        HuskLog.log("olfx", "present: showing the game view for \(app.id)")
        playing = app
    }

    func close() {
        launchTask?.cancel()
        launching = nil
    }

    func closed() {
        launchTask?.cancel()
        launchTask = nil
        if trackingStarted { tracking?.stop(); trackingStarted = false }
        tracking = nil
        StereoLayer.detach()
        playing = nil
        status = nil
        launchIssue = nil
        HuskOrientation.set(HuskOrientation.standard)
    }

    func confirmRestart(_ app: TLApp) {
        Router.shared.switchTo(app.id); CrashReport.gameEnded(); HuskLog.flushNow(); exit(0)
    }
}
