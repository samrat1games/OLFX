import SwiftUI
import AVFoundation

/// Same idea as the Android "Allow camera" button: shows permission state and a live preview.
final class CameraCheck: NSObject, ObservableObject {
    @Published var status = AVCaptureDevice.authorizationStatus(for: .video)
    let session = AVCaptureSession()
    private var configured = false

    var text: String {
        switch status {
        case .authorized: return "Camera OK"
        case .denied, .restricted: return "Camera blocked: enable it in Settings"
        default: return "Camera permission needed"
        }
    }
    func request() {
        AVCaptureDevice.requestAccess(for: .video) { ok in
            DispatchQueue.main.async { self.status = AVCaptureDevice.authorizationStatus(for: .video); if ok { self.startPreview() } }
        }
    }
    func startPreview() {
        guard status == .authorized else { return }
        if !configured, let dev = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
           let input = try? AVCaptureDeviceInput(device: dev), session.canAddInput(input) {
            session.addInput(input); configured = true
        }
        DispatchQueue.global().async { if !self.session.isRunning { self.session.startRunning() } }
    }
    func stop() { DispatchQueue.global().async { self.session.stopRunning() } }
}

struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession
    final class V: UIView { override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self } }
    func makeUIView(context: Context) -> V {
        let v = V(); (v.layer as! AVCaptureVideoPreviewLayer).session = session
        (v.layer as! AVCaptureVideoPreviewLayer).videoGravity = .resizeAspectFill; return v
    }
    func updateUIView(_ v: V, context: Context) {}
}

struct CameraCheckView: View {
    @StateObject private var cam = CameraCheck()
    var body: some View {
        VStack(spacing: 12) {
            HStack {
                Image(systemName: cam.status == .authorized ? "checkmark.circle.fill" : "camera.fill")
                    .foregroundStyle(cam.status == .authorized ? .green : .orange)
                Text(cam.text).font(.headline); Spacer()
            }
            if cam.status == .authorized {
                CameraPreview(session: cam.session).frame(height: 200).clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            } else if cam.status == .notDetermined {
                Button("Allow camera") { cam.request() }.buttonStyle(.borderedProminent)
            } else {
                Button("Open Settings") { if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) } }.buttonStyle(.bordered)
            }
        }
        .padding(16).glass(cornerRadius: 24)
        .onAppear { cam.startPreview() }.onDisappear { cam.stop() }
    }
}
