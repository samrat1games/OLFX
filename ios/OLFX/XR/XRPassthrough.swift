import AVFoundation
import CoreImage
import UIKit

@_silgen_name("olfx_xr_publish_camera") func olfx_xr_publish_camera(_ rgba: UnsafePointer<UInt8>, _ w: Int32, _ h: Int32, _ stride: Int32)

/// Camera feed for OpenXR passthrough (XR_FB_passthrough, olfx_xr_pt.c). With ARKit tracking the frames come from the AR session
/// (it owns the camera); in gyro mode a plain capture session is started while a game has passthrough running.
enum XRPassthrough {
    nonisolated(unsafe) static var wanted = false
    nonisolated(unsafe) private static var rotate180 = false
    nonisolated(unsafe) private static var last = 0.0
    nonisolated(unsafe) private static var buf = [UInt8](repeating: 0, count: 960 * 540 * 4)
    nonisolated(unsafe) private static var feed: Feed?
    private static let ci = CIContext(options: [.useSoftwareRenderer: false])
    private static let q = DispatchQueue(label: "olfx.passthrough")
    static let w = 960, h = 540

    static func request(_ on: Bool) {
        wanted = on
        DispatchQueue.main.async {
            let o = UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.interfaceOrientation }.first
            rotate180 = (o == .landscapeLeft)
            if on, UserDefaults.standard.string(forKey: "trk.mode") == "gamepad" {   // gyro mode: ARKit is not running, so open the camera ourselves
                if feed == nil { feed = Feed() }
                feed?.start()
            } else { feed?.stop() }
        }
        HuskLog.log("passthrough", on ? "camera requested" : "camera released")
    }

    /// From the ARKit delegate (any queue): ~30 fps.
    static func offer(_ pb: CVPixelBuffer) {
        guard wanted else { return }
        let now = CACurrentMediaTime()
        guard now - last >= 1.0 / 30 else { return }
        last = now
        publish(pb)
    }

    fileprivate static func publish(_ pb: CVPixelBuffer) {
        q.sync {
            var img = CIImage(cvPixelBuffer: pb)
            if rotate180 { img = img.oriented(.down) }
            let sx = CGFloat(w) / img.extent.width, sy = CGFloat(h) / img.extent.height
            img = img.transformed(by: CGAffineTransform(scaleX: sx, y: sy))
            buf.withUnsafeMutableBytes { p in
                ci.render(img, toBitmap: p.baseAddress!, rowBytes: w * 4, bounds: CGRect(x: 0, y: 0, width: w, height: h), format: .RGBA8, colorSpace: CGColorSpace(name: CGColorSpace.sRGB))
            }
            buf.withUnsafeBufferPointer { olfx_xr_publish_camera($0.baseAddress!, Int32(w), Int32(h), Int32(w * 4)) }
        }
    }

    final class Feed: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
        private let session = AVCaptureSession()
        private var configured = false
        func start() {
            guard AVCaptureDevice.authorizationStatus(for: .video) == .authorized else {
                AVCaptureDevice.requestAccess(for: .video) { ok in if ok { DispatchQueue.main.async { self.start() } } }; return
            }
            if !configured {
                session.sessionPreset = .hd1280x720
                guard let dev = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
                      let input = try? AVCaptureDeviceInput(device: dev), session.canAddInput(input) else { return }
                session.addInput(input)
                let out = AVCaptureVideoDataOutput()
                out.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
                out.alwaysDiscardsLateVideoFrames = true
                out.setSampleBufferDelegate(self, queue: DispatchQueue(label: "olfx.passthrough.capture"))
                guard session.canAddOutput(out) else { return }
                session.addOutput(out); configured = true
            }
            DispatchQueue.global().async { if !self.session.isRunning { self.session.startRunning() } }
        }
        func stop() { DispatchQueue.global().async { if self.session.isRunning { self.session.stopRunning() } } }
        func captureOutput(_ o: AVCaptureOutput, didOutput sb: CMSampleBuffer, from c: AVCaptureConnection) {
            guard XRPassthrough.wanted, let pb = CMSampleBufferGetImageBuffer(sb) else { return }
            XRPassthrough.publish(pb)
        }
    }
}

@_cdecl("olfx_xr_camera_request") public func olfx_xr_camera_request(_ on: Int32) { XRPassthrough.request(on != 0) }
