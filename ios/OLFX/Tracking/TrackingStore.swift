import SwiftUI
import Vision
import ARKit
import GameController
import CoreImage
import CoreMotion

enum HandEngine: String, CaseIterable, Identifiable {
    case appleVision = "Apple Vision"
    case mediaPipe = "MediaPipe"
    var id: String { rawValue }
}

/// What the preview draws for one hand.
struct HandViz: Identifiable {
    let id: Int32                                           // 0 left, 1 right (after any swap)
    var points: [VNHumanHandPoseObservation.JointName: CGPoint]   // upright image, 0..1, origin bottom-left
    var metrics: HandMetrics
}

/// Settings + live state for hand tracking and 6DoF head pose. One ARKit session does both:
/// the camera frames go to Apple Vision (hands) and ARKit's own world tracking gives the head pose;
/// both are published to the OpenXR runtime (XRBridge) and drawn in the Hand-Tracking tab.
final class TrackingStore: NSObject, ObservableObject, ARSessionDelegate {
    @AppStorage("handEngine") var engineRaw = HandEngine.appleVision.rawValue
    @AppStorage("sixDoF") var sixDoF = true { didSet { reconfigure() } }
    @AppStorage("handsOn") var handsOn = true
    @AppStorage("olfx.xr") var xrInGames = true
    @AppStorage("olfx.ovrpGLES") var ovrpGLES = false      // Oculus-plugin games: try real VR through OpenGL ES (experimental)
    @AppStorage("trk.smoothing") var smoothing = 0.35 { didSet { push() } }
    @AppStorage("trk.handScale") var handScale = 1.0 { didSet { push() } }
    @AppStorage("trk.posScale") var positionScale = 1.0 { didSet { push() } }
    @AppStorage("trk.minConf") var minConfidence = 0.3 { didSet { push() } }
    @AppStorage("trk.swap") var swapHands = false { didSet { push() } }
    @AppStorage("trk.lidar") var useLiDAR = true { didSet { push(); reconfigure() } }
    @AppStorage("trk.pinch") var pinchSensitivity = 0.5 { didSet { push() } }
    @AppStorage("trk.maxHands") var maxHands = 2
    @AppStorage("trk.skeleton") var showSkeleton = true
    @AppStorage("trk.handHz") var handHz = 24.0
    /// The camera preview is only made while the Hand-Tracking tab is on screen (it costs CPU in a game).
    var previewEnabled = false
    private var lastVision = CACurrentMediaTime()
    private let arQueue = DispatchQueue(label: "olfx.ar", qos: .userInteractive)
    /// "full": ARKit 6DoF + Apple Vision hands.  "gamepad": no camera at all (best speed); head = phone gyro (3DoF), controllers = gamepad / Joy-Cons.
    @AppStorage("trk.mode") var modeRaw = "full"
    var gamepadMode: Bool { modeRaw == "gamepad" }
    private let motion = CMMotionManager()

    @Published var headPose = simd_float4x4(1)
    @Published var preview: UIImage?
    @Published var hands: [HandViz] = []
    @Published var fps = 0.0
    @Published var arState = "—"
    @Published var running = false
    @Published var lidarAvailable = ARWorldTrackingConfiguration.supportsFrameSemantics(.sceneDepth)
    @Published var pads = GCController.controllers().count

    var engine: HandEngine { HandEngine(rawValue: engineRaw) ?? .appleVision }
    var supported: Bool { ARWorldTrackingConfiguration.isSupported }
    private let session = ARSession()
    private let visionQueue = DispatchQueue(label: "olfx.vision")
    private let previewQueue = DispatchQueue(label: "olfx.preview")
    private let ci = CIContext()
    private var busy = false, previewBusy = false
    private var lastPreview = CACurrentMediaTime()
    private var frames = 0, fpsSince = CACurrentMediaTime()
    private var padTimer: Timer?
    private var padSig = ""
    private var users = 0                 // the tab and a game can both want tracking
    override init() { super.init(); push() }

    private func push() {
        XRBridge.tuning = XRTuning(smoothing: Float(smoothing), handScale: Float(handScale), positionScale: Float(positionScale),
                                   minConfidence: Float(minConfidence), swapHands: swapHands, useLiDAR: useLiDAR, pinchSensitivity: Float(pinchSensitivity))
    }

    private func configuration() -> ARWorldTrackingConfiguration {
        let c = ARWorldTrackingConfiguration()
        // A smaller camera image is much cheaper for ARKit and Vision; 6DoF does not need more.
        let fmts = ARWorldTrackingConfiguration.supportedVideoFormats
        if let f = fmts.filter({ $0.imageResolution.width <= 1280 && $0.framesPerSecond == 30 }).max(by: { $0.imageResolution.width < $1.imageResolution.width })
            ?? fmts.filter({ $0.imageResolution.width <= 1280 }).min(by: { $0.framesPerSecond < $1.framesPerSecond }) { c.videoFormat = f }
        if useLiDAR, ARWorldTrackingConfiguration.supportsFrameSemantics(.sceneDepth) { c.frameSemantics.insert(.sceneDepth) }
        return c
    }
    private func reconfigure() { if running { session.run(configuration(), options: []) } }

    func start() {
        users += 1
        guard users == 1 else { return }
        push()
        running = true
        if gamepadMode { startGyro() }
        else if supported {
            session.delegate = self; session.delegateQueue = arQueue
            session.run(configuration(), options: [.resetTracking, .removeExistingAnchors])
        } else { arState = "ARKit is not supported on this device" }
        padTimer?.invalidate()
        padTimer = Timer.scheduledTimer(withTimeInterval: 1.0 / 60, repeats: true) { [weak self] _ in
            let list = GCController.controllers()
            XRBridge.publishPads(list)
            self?.pads = list.count
            let sig = list.map { "\($0.vendorName ?? "?")|\($0.productCategory)|ext:\($0.extendedGamepad != nil)|micro:\($0.microGamepad != nil)|motion:\($0.motion != nil)" }.joined(separator: "; ")
            if sig != self?.padSig { self?.padSig = sig; HuskLog.log("pad", "controllers: \(sig.isEmpty ? "none" : sig)") }
        }
    }
    func stop() {
        users = max(0, users - 1)
        guard users == 0 else { return }
        session.pause(); motion.stopDeviceMotionUpdates(); running = false
        padTimer?.invalidate(); padTimer = nil
        hands = []; preview = nil
        XRBridge.clearHand(0); XRBridge.clearHand(1)
    }
    func recenter() { XRBridge.recenter(to: headPose); XRBridge.recenterPads() }
    /// Call after changing the mode while tracking is running.
    func modeChanged() { guard running else { return }; session.pause(); motion.stopDeviceMotionUpdates(); hands = []; preview = nil
        XRBridge.clearHand(0); XRBridge.clearHand(1)
        if gamepadMode { startGyro() } else if supported { session.delegate = self; session.delegateQueue = arQueue; session.run(configuration(), options: [.resetTracking]) } }

    /// 3DoF head from the phone's gyro: the phone sits in a headset, back camera forward. Head axes in device coordinates depend on how
    /// the phone is turned (landscape either way).
    private func startGyro() {
        guard motion.isDeviceMotionAvailable else { arState = "gyro not available"; return }
        arState = "gyro"
        motion.deviceMotionUpdateInterval = 1.0 / 60
        motion.startDeviceMotionUpdates(using: .xArbitraryZVertical, to: .main) { [weak self] dm, _ in
            guard let self, let dm else { return }
            let r = dm.attitude.rotationMatrix                         // reference -> device
            // device -> reference (z up), then to y-up: (x, y, z) -> (x, z, -y)
            let dev2ref = simd_float3x3(columns: (SIMD3(Float(r.m11), Float(r.m12), Float(r.m13)), SIMD3(Float(r.m21), Float(r.m22), Float(r.m23)), SIMD3(Float(r.m31), Float(r.m32), Float(r.m33))))
            let P = simd_float3x3(columns: (SIMD3(1, 0, 0), SIMD3(0, 0, -1), SIMD3(0, 1, 0)))
            let toWorld = P * dev2ref
            let ccw = UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.interfaceOrientation }.first == .landscapeRight
            // head basis columns (right, up, back) in device coordinates
            let basis = ccw ? simd_float3x3(columns: (SIMD3(0, -1, 0), SIMD3(1, 0, 0), SIMD3(0, 0, 1)))
                            : simd_float3x3(columns: (SIMD3(0, 1, 0), SIMD3(-1, 0, 0), SIMD3(0, 0, 1)))
            let R = toWorld * basis
            var t = matrix_identity_float4x4
            t.columns.0 = SIMD4(R.columns.0, 0); t.columns.1 = SIMD4(R.columns.1, 0); t.columns.2 = SIMD4(R.columns.2, 0)
            t.columns.3 = SIMD4(0, 0, 0, 1)       // XRBridge adds eye height
            self.headPose = t
            XRBridge.publishHead(t, sixDoF: true)
        }
    }

    func session(_ s: ARSession, didUpdate frame: ARFrame) {
        guard !gamepadMode else { return }
        let cam = frame.camera
        XRBridge.publishHead(cam.transform, sixDoF: sixDoF)          // first, on the AR queue: the pose is what the game waits for
        frames += 1
        let now = CACurrentMediaTime()
        if previewEnabled || now - fpsSince >= 1 {
            let tt = cam.transform, tsr = cam.trackingState
            let f: Double? = now - fpsSince >= 1 ? Double(frames) / (now - fpsSince) : nil
            if f != nil { frames = 0; fpsSince = now }
            DispatchQueue.main.async {
                self.headPose = tt
                switch tsr {
                case .normal: self.arState = "OK"
                case .notAvailable: self.arState = "not available"
                case .limited(let r): self.arState = "limited: \(r)"
                }
                if let f { self.fps = f }
            }
        }

        let buf = frame.capturedImage
        XRPassthrough.offer(buf)
        // Preview: ~15 fps, rendered off the main thread.
        if previewEnabled, now - lastPreview > 1.0 / 15, !previewBusy {
            lastPreview = now; previewBusy = true
            previewQueue.async { [weak self] in
                guard let self else { return }
                let img = CIImage(cvPixelBuffer: buf).oriented(.right)
                let out = self.ci.createCGImage(img, from: img.extent).map { UIImage(cgImage: $0) }
                DispatchQueue.main.async { self.preview = out; self.previewBusy = false }
            }
        }
        guard handsOn, engine == .appleVision, !busy, now - lastVision >= 1.0 / max(5, min(handHz, 30)) else { return }
        lastVision = now; busy = true
        let camT = cam.transform, K = cam.intrinsics, depth = frame.sceneDepth?.depthMap
        let imgSize = CGSize(width: CVPixelBufferGetWidth(buf), height: CVPixelBufferGetHeight(buf))
        let maxH = maxHands
        visionQueue.async { [weak self] in
            let req = VNDetectHumanHandPoseRequest(); req.maximumHandCount = maxH
            try? VNImageRequestHandler(cvPixelBuffer: buf, orientation: .right).perform([req])
            let found = XRBridge.publishAll(req.results ?? [], cameraToWorld: camT, K: K, size: imgSize, depthMap: depth)
            let viz = found.map { HandViz(id: $0.idx, points: $0.points, metrics: $0.m) }
            let seen = Set(found.map(\.idx))
            for i in [Int32(0), 1] where !seen.contains(i) { XRBridge.noteMissed(i) }
            DispatchQueue.main.async { self?.hands = viz; self?.busy = false }
        }
    }
}
