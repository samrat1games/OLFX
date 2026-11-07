import SwiftUI
import Vision
import ARKit

enum HandEngine: String, CaseIterable, Identifiable {
    case appleVision = "Apple Vision"
    case mediaPipe = "MediaPipe"
    var id: String { rawValue }
}

/// Settings + live state for hand tracking and 6DoF head pose.
final class TrackingStore: NSObject, ObservableObject, ARSessionDelegate {
    @AppStorage("handEngine") var engineRaw = HandEngine.appleVision.rawValue
    @AppStorage("sixDoF") var sixDoF = true
    @AppStorage("handsOn") var handsOn = true
    @Published var headPose = simd_float4x4(1)
    @Published var handPoints: [[CGPoint]] = []

    var engine: HandEngine { HandEngine(rawValue: engineRaw) ?? .appleVision }
    private let session = ARSession()
    private let visionQueue = DispatchQueue(label: "olfx.vision")
    private var busy = false

    func start() {
        guard ARWorldTrackingConfiguration.isSupported else { return }
        session.delegate = self
        session.run(sixDoF ? ARWorldTrackingConfiguration() : ARWorldTrackingConfiguration()) // 3DoF fallback: ignore translation in the consumer
    }
    func stop() { session.pause() }

    func session(_ s: ARSession, didUpdate frame: ARFrame) {
        headPose = frame.camera.transform
        guard handsOn, engine == .appleVision, !busy else { return }
        busy = true
        let buf = frame.capturedImage
        visionQueue.async { [weak self] in
            let req = VNDetectHumanHandPoseRequest(); req.maximumHandCount = 2
            try? VNImageRequestHandler(cvPixelBuffer: buf, orientation: .right).perform([req])
            let pts: [[CGPoint]] = (req.results ?? []).map { obs in
                ((try? obs.recognizedPoints(.all)) ?? [:]).values.filter { $0.confidence > 0.3 }.map(\.location)
            }
            DispatchQueue.main.async { self?.handPoints = pts; self?.busy = false }
        }
    }
}
