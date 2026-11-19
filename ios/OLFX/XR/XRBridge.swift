import ARKit
import Metal
import QuartzCore
import GameController
import Vision
import simd

// C side: OLFX/XR/olfx_xr*.c (the OpenXR runtime games talk to). No bridging header needed.
@_silgen_name("olfx_xr_install") func olfx_xr_install(_ libdir: UnsafePointer<CChar>?)
@_silgen_name("olfx_xr_publish_head") func olfx_xr_publish_head(_ m16: UnsafePointer<Float>)
@_silgen_name("olfx_xr_publish_pad") func olfx_xr_publish_pad(_ v32: UnsafePointer<Float>)
@_silgen_name("olfx_xr_publish_hand") func olfx_xr_publish_hand(_ hand: Int32, _ active: Int32, _ joints: UnsafePointer<Float>)
@_silgen_name("olfx_xr_set_metal_layer") func olfx_xr_set_metal_layer(_ layer: UnsafeMutableRawPointer?)
@_silgen_name("olfx_vk_frame_visible") func olfx_vk_frame_visible() -> Int32
@_silgen_name("olfx_xr_set_vulkan_lib") func olfx_xr_set_vulkan_lib(_ path: UnsafePointer<CChar>?)
@_silgen_name("olfx_xr_set_eye_size") func olfx_xr_set_eye_size(_ px: Int32)
@_silgen_name("olfx_xr_set_pinch") func olfx_xr_set_pinch(_ close: Float, _ range: Float)

/// User-tunable tracking parameters (Hand-Tracking tab). Read on the vision queue, written from the UI.
struct XRTuning {
    var smoothing: Float = 0.35        // 0 = raw, 0.9 = very smooth
    var handScale: Float = 1.0         // multiplies the assumed hand size (wrist -> middle knuckle 9.5 cm)
    var positionScale: Float = 1.0     // head/hand translation multiplier (6DoF)
    var minConfidence: Float = 0.2
    var swapHands = false
    var useLiDAR = true
    var pinchSensitivity: Float = 0.5  // 0..1
}

struct HandMetrics { var pinch: Float = 0; var fist: Float = 0; var depth: Float = 0; var lidar = false }

/// Turns ARKit's head pose and Apple Vision's 2D hand landmarks into what OpenXR wants:
/// a head matrix and 26 world-space joints per hand, with per-bone orientations (like Meta's hand tracking).
enum XRBridge {
    nonisolated(unsafe) static var tuning = XRTuning() { didSet { applyPinch() } }
    nonisolated(unsafe) static var origin = matrix_identity_float4x4      // set by Recenter
    private nonisolated(unsafe) static var prev: [Int32: [SIMD3<Float>]] = [:]

    static func install() {
        let dir = (Bundle.main.resourcePath ?? "") + "/XRLibs"
        dir.withCString { olfx_xr_install($0) }
        ((Bundle.main.privateFrameworksPath ?? "") + "/MoltenVK.framework/MoltenVK").withCString { olfx_xr_set_vulkan_lib($0) }
        applyPinch()
    }
    /// Eye image size the game renders at (square, pixels): the biggest lever on frame rate.
    static func setEyeSize(_ px: Int) { olfx_xr_set_eye_size(Int32(px)) }
    static func applyPinch() { olfx_xr_set_pinch(0.014, 0.02 + 0.05 * (1 - tuning.pinchSensitivity)) }
    static func recenter(to head: simd_float4x4) {
        // keep only yaw so "forward" is where the user looks now, and the floor stays level
        let f = SIMD3<Float>(-head.columns.2.x, 0, -head.columns.2.z)
        let yaw = atan2(f.x, -f.z)
        var m = simd_float4x4(simd_quatf(angle: yaw, axis: SIMD3(0, 1, 0)))
        m.columns.3 = SIMD4(head.columns.3.x, head.columns.3.y, head.columns.3.z, 1)
        origin = m.inverse
    }

    /// world (ARKit) -> OLFX world: relative to the recenter origin, translation scaled.
    private static func toWorld(_ m: simd_float4x4) -> simd_float4x4 {
        var r = origin * m
        r.columns.3 = SIMD4(r.columns.3.x * tuning.positionScale, r.columns.3.y * tuning.positionScale + 1.6, r.columns.3.z * tuning.positionScale, 1)   // world is floor-based: tracking starts at eye height
        return r
    }
    private static func toWorld(_ p: SIMD3<Float>) -> SIMD3<Float> {
        let w = toWorld(simd_float4x4(translation: p)).columns.3
        return SIMD3(w.x, w.y, w.z)
    }

    static func publishHead(_ t: simd_float4x4, sixDoF: Bool) {
        var h = toWorld(t)
        if !sixDoF { h.columns.3 = SIMD4(0, 1.6, 0, 1) }                              // rotation only
        var m = [Float](repeating: 0, count: 16)
        for c in 0..<4 { for r in 0..<4 { m[c * 4 + r] = h[c][r] } }
        m.withUnsafeBufferPointer { olfx_xr_publish_head($0.baseAddress!) }
    }

    // MARK: controllers (gamepad / Joy-Con as Oculus Touch)
    private nonisolated(unsafe) static var padRef: [Int: simd_quatf] = [:]      // gyro zero per side
    private nonisolated(unsafe) static var padInt: [Int: simd_quatf] = [:]      // integrated orientation when there is no attitude
    private nonisolated(unsafe) static var padLast = CACurrentMediaTime()
    static func recenterPads() { padRef = [:]; padInt = [:] }

    private static func side(of c: GCController) -> Int? {       // 0 left, 1 right, nil = a normal gamepad or a Joy-Con pair
        let n = ((c.vendorName ?? "") + " " + (c.productCategory)).lowercased()
        guard n.contains("joy-con") || n.contains("joycon") else { return nil }
        if n.contains("(l/r)") || n.contains("l/r") { return nil }
        if n.contains("(l)") || n.hasSuffix(" l") { return 0 }
        if n.contains("(r)") || n.hasSuffix(" r") { return 1 }
        return nil
    }

    /// Game controllers stand in for Touch controllers: a pair of Joy-Cons (or two single ones) = left + right Touch, with orientation from
    /// their gyro; one ordinary gamepad drives both (sticks, triggers, buttons) with no pose. Single Joy-Cons are not "extended" gamepads on
    /// iOS, so every controller is read through its physical input profile (by element name) as well.
    static func publishPads(_ list: [GCController]) {
        var v = [Float](repeating: 0, count: 32)
        let now = CACurrentMediaTime(); let dt = Float(max(0.001, min(0.1, now - padLast))); padLast = now
        var seenMotionSides = Set<Int>()
        for c in list {
            let s = side(of: c)
            func m(_ i: Int, _ x: Float) { if abs(x) > abs(v[i]) { v[i] = x } }
            var stickX: Float = 0, stickY: Float = 0, trig: Float = 0, grip: Float = 0, click: Float = 0
            var a: Float = 0, b: Float = 0, x: Float = 0, y: Float = 0, menu: Float = 0
            if let g = c.extendedGamepad {
                let sx = abs(g.leftThumbstick.xAxis.value) + abs(g.leftThumbstick.yAxis.value) >= abs(g.rightThumbstick.xAxis.value) + abs(g.rightThumbstick.yAxis.value)
                stickX = sx ? g.leftThumbstick.xAxis.value : g.rightThumbstick.xAxis.value
                stickY = sx ? g.leftThumbstick.yAxis.value : g.rightThumbstick.yAxis.value
                trig = max(g.leftTrigger.value, g.rightTrigger.value); grip = max(g.leftShoulder.value, g.rightShoulder.value)
                click = max(g.leftThumbstickButton?.value ?? 0, g.rightThumbstickButton?.value ?? 0)
                a = g.buttonA.value; b = g.buttonB.value; x = g.buttonX.value; y = g.buttonY.value; menu = g.buttonMenu.value
                if s == nil {
                    m(0, g.leftThumbstick.xAxis.value); m(1, g.leftThumbstick.yAxis.value); m(2, g.rightThumbstick.xAxis.value); m(3, g.rightThumbstick.yAxis.value)
                    m(4, g.leftTrigger.value); m(5, g.rightTrigger.value); m(6, g.leftShoulder.value); m(7, g.rightShoulder.value)
                    m(13, g.leftThumbstickButton?.value ?? 0); m(14, g.rightThumbstickButton?.value ?? 0)
                }
            }
            // physical input profile: whatever the controller has, found by name
            for (name, btn) in c.physicalInputProfile.buttons {
                let n = name.lowercased(), val = btn.value
                if n.contains("trigger") { trig = max(trig, val) }
                else if n.contains("shoulder") || n == "l" || n == "r" || n.contains("sl") || n.contains("sr") { grip = max(grip, val) }
                else if n.contains("menu") || n.contains("options") || n.contains("plus") || n.contains("minus") { menu = max(menu, val) }
                else if n.contains("stick") || n.contains("thumb") { click = max(click, val) }
                else if n.contains("button a") || n == "a" { a = max(a, val) }
                else if n.contains("button b") || n == "b" { b = max(b, val) }
                else if n.contains("button x") || n == "x" { x = max(x, val) }
                else if n.contains("button y") || n == "y" { y = max(y, val) }
            }
            for (name, pad) in c.physicalInputProfile.dpads {
                let n = name.lowercased()
                if n.contains("stick") || n.contains("direction") || n.contains("d-pad") || n.contains("dpad") {
                    if abs(pad.xAxis.value) + abs(pad.yAxis.value) > abs(stickX) + abs(stickY) { stickX = pad.xAxis.value; stickY = pad.yAxis.value }
                }
            }
            if let s {
                m(s * 2, stickX); m(s * 2 + 1, stickY); m(4 + s, trig); m(6 + s, grip); m(13 + s, click)
            }
            m(8, a); m(9, b); m(10, x); m(11, y); m(12, menu)
            // gyro pose (Joy-Con): relative to where it pointed at the last recenter, so "forward" is wherever you hold it at first.
            // A Joy-Con pair is one controller: its motion drives the right hand.
            let isJoy = ((c.vendorName ?? "") + (c.productCategory)).lowercased().contains("joy")
            let poseSide = s ?? (isJoy ? 1 : nil)
            if let ps = poseSide, let mo = c.motion, mo.hasAttitude || mo.hasRotationRate {
                seenMotionSides.insert(ps)
                if !mo.sensorsActive { mo.sensorsActive = true }
                var q: simd_quatf
                if mo.hasAttitude { let at = mo.attitude; q = simd_quatf(ix: Float(at.x), iy: Float(at.y), iz: Float(at.z), r: Float(at.w)) }
                else {
                    let r = mo.rotationRate; let w = SIMD3<Float>(Float(r.x), Float(r.y), Float(r.z))
                    let ang = simd_length(w) * dt
                    var cur = padInt[ps] ?? simd_quatf(angle: 0, axis: SIMD3(0, 1, 0))
                    if ang > 1e-6 { cur = (cur * simd_quatf(angle: ang, axis: simd_normalize(w))).normalized }
                    padInt[ps] = cur; q = cur
                }
                if padRef[ps] == nil { padRef[ps] = q.inverse }
                let rel = (padRef[ps]! * q).normalized
                v[16 + 4 * ps] = rel.imag.x; v[17 + 4 * ps] = rel.imag.y; v[18 + 4 * ps] = rel.imag.z; v[19 + 4 * ps] = rel.real; v[24 + ps] = 1
            }
        }
        for side in [0, 1] where !seenMotionSides.contains(side) {
            padRef.removeValue(forKey: side)
            padInt.removeValue(forKey: side)
        }
        PhoneLink.shared.merge(into: &v)
        v.withUnsafeBufferPointer { olfx_xr_publish_pad($0.baseAddress!) }
    }

    static func clearHand(_ idx: Int32) {
        prev[idx] = nil; vel[idx] = nil
        var z = [Float](repeating: 0, count: 26 * 8)
        z.withUnsafeMutableBufferPointer { olfx_xr_publish_hand(idx, 0, $0.baseAddress!) }
    }

    private static let fingers: [[VNHumanHandPoseObservation.JointName]] = [
        [.thumbCMC, .thumbMP, .thumbIP, .thumbTip],
        [.indexMCP, .indexPIP, .indexDIP, .indexTip],
        [.middleMCP, .middlePIP, .middleDIP, .middleTip],
        [.ringMCP, .ringPIP, .ringDIP, .ringTip],
        [.littleMCP, .littlePIP, .littleDIP, .littleTip],
    ]

    /// Depth (metres along the camera axis) at a sensor pixel from ARKit's LiDAR depth map, or nil.
    private static func lidarDepth(_ map: CVPixelBuffer, _ px: SIMD2<Float>, _ size: SIMD2<Float>) -> Float? {
        let dw = CVPixelBufferGetWidth(map), dh = CVPixelBufferGetHeight(map)
        guard let base = CVPixelBufferGetBaseAddress(map) else { return nil }
        let stride = CVPixelBufferGetBytesPerRow(map) / MemoryLayout<Float32>.size
        let cx = Int(px.x / size.x * Float(dw)), cy = Int(px.y / size.y * Float(dh))
        var sum: Float = 0, n: Float = 0
        for dy in -1...1 { for dx in -1...1 {
            let x = cx + dx, y = cy + dy
            if x < 0 || y < 0 || x >= dw || y >= dh { continue }
            let d = base.assumingMemoryBound(to: Float32.self)[y * stride + x]
            if d > 0.1 && d < 2.5 { sum += d; n += 1 }
        } }
        return n > 0 ? sum / n : nil
    }

    // MARK: hands
    /// Average adult hand, metres: for each finger the bones from its parent (wrist for the first), outwards.
    /// Fixing the lengths is what stops fingers stretching into noodles when a 2D landmark wobbles.
    private static let boneLen: [[Float]] = [
        [0.030, 0.040, 0.033, 0.027],      // thumb: wrist-CMC, CMC-MP, MP-IP, IP-tip
        [0.090, 0.043, 0.024, 0.019],      // index: wrist-MCP, MCP-PIP, PIP-DIP, DIP-tip
        [0.092, 0.048, 0.027, 0.020],
        [0.087, 0.043, 0.026, 0.020],
        [0.080, 0.034, 0.018, 0.017],
    ]
    private nonisolated(unsafe) static var lastWrist: [Int32: SIMD3<Float>] = [:]
    private nonisolated(unsafe) static var lastSeen: [Int32: Double] = [:]
    private nonisolated(unsafe) static var lastTime: [Int32: Double] = [:]
    private nonisolated(unsafe) static var vel: [Int32: [SIMD3<Float>]] = [:]       // smoothed joint velocities, m/s
    private nonisolated(unsafe) static var radii: [Int32: [Float]] = [:]
    // Keep the projected direction of each bone across short occlusions.
    private nonisolated(unsafe) static var rayDirs: [Int32: [SIMD3<Float>]] = [:]
    private nonisolated(unsafe) static var rayTimes: [Int32: Double] = [:]
    private static let leadSeconds: Float = 0.015                                    // how far ahead of the camera frame the hand is drawn

    private struct Solved {
        var P: [SIMD3<Float>]; var R: [Float]
        var wrist: SIMD3<Float>; var u: Float; var chirality: Int32; var depth: Float; var lidar: Bool
        var points: [VNHumanHandPoseObservation.JointName: CGPoint]
    }

    private static func onRay(_ r: SIMD3<Float>, parent: SIMD3<Float>, length L: Float,
                              previous: SIMD3<Float>?) -> SIMD3<Float> {
        let a = simd_dot(r, r), b = simd_dot(r, parent), c = simd_dot(parent, parent) - L * L
        let disc = b * b - a * c
        var t = b / a
        if disc >= 0 {
            let sq = sqrt(disc), t1 = (b - sq) / a, t2 = (b + sq) / a
            let want = -parent.z
            t = abs(t1 - want) <= abs(t2 - want) ? t1 : t2
            if let previous, t1 > 0, t2 > 0 {
                let d1 = r * t1 - parent, d2 = r * t2 - parent
                if simd_length_squared(d1) > 1e-8 && simd_length_squared(d2) > 1e-8 {
                    t = simd_dot(simd_normalize(d1), previous) >= simd_dot(simd_normalize(d2), previous) ? t1 : t2
                }
            }
            if t <= 0 { t = max(t1, t2) }
        }
        let candidate = r * max(t, 0.05)
        let delta = candidate - parent
        let magnitude = simd_length(delta)
        guard magnitude.isFinite, magnitude > 1e-6 else { return parent + SIMD3(0, 0, -L) }
        return parent + delta * (L / magnitude)
    }

    private static func solve(_ obs: VNHumanHandPoseObservation, cameraToWorld: simd_float4x4, K: simd_float3x3, size: CGSize, depthMap: CVPixelBuffer?) -> Solved? {
        let t = tuning
        guard let pts = try? obs.recognizedPoints(.all), let wrist = pts[.wrist], wrist.confidence > t.minConfidence,
              let midMCP = pts[.middleMCP], midMCP.confidence > t.minConfidence else { return nil }
        let fx = K[0][0], fy = K[1][1], cx = K[2][0], cy = K[2][1]
        let W = Float(size.width), H = Float(size.height)
        func sensor(_ p: CGPoint) -> SIMD2<Float> { SIMD2((1 - Float(p.y)) * W, (1 - Float(p.x)) * H) }   // upright -> sensor px
        func ray(_ p: CGPoint) -> SIMD3<Float> { let s = sensor(p); return SIMD3((s.x - cx) / fx, -(s.y - cy) / fy, -1) }
        let sw = sensor(wrist.location), sm = sensor(midMCP.location)
        let lenPx = simd_length(sw - sm)
        guard lenPx > 4 else { return nil }
        let scale = t.handScale
        // Use several palm spans: wrist-to-middle alone collapses when the palm rotates.
        var depthCandidates: [Float] = []
        let spans: [(VNHumanHandPoseObservation.JointName, VNHumanHandPoseObservation.JointName, Float)] = [
            (.wrist, .middleMCP, 0.092), (.wrist, .indexMCP, 0.090),
            (.wrist, .ringMCP, 0.087), (.wrist, .littleMCP, 0.080),
            (.indexMCP, .littleMCP, 0.055)
        ]
        for (a, b, metres) in spans {
            if let pa = pts[a], let pb = pts[b], pa.confidence > t.minConfidence, pb.confidence > t.minConfidence {
                let pixels = simd_length(sensor(pa.location) - sensor(pb.location))
                if pixels > 6 { depthCandidates.append(fx * metres * scale / pixels) }
            }
        }
        guard !depthCandidates.isEmpty else { return nil }
        depthCandidates.sort()
        var depth = depthCandidates[depthCandidates.count / 2]
        var usedLiDAR = false
        if let dm = depthMap, t.useLiDAR {
            CVPixelBufferLockBaseAddress(dm, .readOnly)
            if let d = lidarDepth(dm, (sw + sm) / 2, SIMD2(W, H)), abs(d - depth) < 0.25 { depth = d; usedLiDAR = true }
            CVPixelBufferUnlockBaseAddress(dm, .readOnly)
        }
        // Reject degenerate camera geometry instead of injecting huge values into game physics.
        guard fx.isFinite, fy.isFinite, fx > 1, fy > 1, depth.isFinite else { return nil }
        depth = min(max(depth, 0.15), 1.2)
        let wristCam = ray(wrist.location) * depth
        // Use the previous solution to avoid choosing alternating 3D depths for the same 2D bone.
        let side: Int32 = obs.chirality == .left ? 0 : 1
        let now = CACurrentMediaTime()
        let previous = now - (rayTimes[side] ?? 0) < 0.4 ? rayDirs[side] : nil
        var directions = [SIMD3<Float>](repeating: .zero, count: 20)
        var cam = [SIMD3<Float>](); var R = [Float]()
        cam.append(.zero); R.append(0.02)
        cam.append(wristCam); R.append(0.02)
        for (fi, f) in fingers.enumerated() {
            var parent = wristCam; var prevDir = simd_normalize(wristCam)
            var chain = [SIMD3<Float>]()
            for k in 0..<4 {
                let L = boneLen[fi][k] * scale
                var p: SIMD3<Float>
                if let pt = pts[f[k]], pt.confidence > 0.2 {
                    let prior = previous.flatMap { $0.count == 20 ? $0[fi * 4 + k] : nil }
                    p = onRay(ray(pt.location), parent: parent, length: L, previous: prior)
                } else {
                    let cached = previous.flatMap { $0.count == 20 ? $0[fi * 4 + k] : nil }
                    let direction = cached.flatMap { simd_length_squared($0) > 1e-8 ? $0 : nil } ?? prevDir
                    p = parent + direction * L
                }
                let delta = p - parent
                let length = simd_length(delta)
                if length.isFinite, length > 1e-5 { prevDir = delta / length }
                directions[fi * 4 + k] = prevDir
                parent = p; chain.append(p)
            }
            if fi == 0 { cam += chain; R += [0.012, 0.011, 0.010, 0.009] }
            else { cam.append(wristCam + (chain[0] - wristCam) * 0.6); R.append(0.010); cam += chain; R += [0.009, 0.008, 0.007, 0.006] }
        }
        rayDirs[side] = directions
        rayTimes[side] = now
        // world
        var P = cam.map { c -> SIMD3<Float> in
            let w = toWorld(cameraToWorld * simd_float4x4(translation: c)); return SIMD3(w.columns.3.x, w.columns.3.y, w.columns.3.z) }
        // joint index map (OpenXR order): 0 palm, 1 wrist, 2-5 thumb, 6-10 index, 11-15 middle, 16-20 ring, 21-25 little
        guard P.allSatisfy({ $0.x.isFinite && $0.y.isFinite && $0.z.isFinite }) else { return nil }
        P[0] = (P[1] + P[12]) / 2                                       // palm: between wrist and the middle knuckle (middle: 11 metacarpal, 12 MCP)
        let recognized = pts.filter { $0.value.confidence > 0.2 }.mapValues(\.location)
        return Solved(P: P, R: R, wrist: P[1], u: Float(wrist.location.x), chirality: obs.chirality == .left ? 0 : 1, depth: depth, lidar: usedLiDAR, points: recognized)
    }

    /// Joint orientations (OpenXR: -Z toward the fingertips, +Y out of the back of the hand, +X to the right of the hand): the palm's frame for the
    /// palm and wrist, and each finger bone's own direction for the finger joints.
    private static func orient(_ P: [SIMD3<Float>], right: Bool) -> [simd_quatf] {
        let forward = P[12] - P[1]
        let acrossVector = right ? P[22] - P[7] : P[7] - P[22]
        let forwardLength = simd_length(forward), acrossLength = simd_length(acrossVector)
        guard forwardLength.isFinite, acrossLength.isFinite, forwardLength > 1e-5, acrossLength > 1e-5 else {
            return Array(repeating: simd_quatf(angle: 0, axis: SIMD3(0, 1, 0)), count: P.count)
        }
        let zAxis = -forward / forwardLength
        let across = acrossVector / acrossLength
        let projected = across - simd_dot(across, zAxis) * zAxis
        let projectedLength = simd_length(projected)
        guard projectedLength.isFinite, projectedLength > 1e-5 else {
            return Array(repeating: simd_quatf(angle: 0, axis: SIMD3(0, 1, 0)), count: P.count)
        }
        let xAxis = projected / projectedLength
        let yAxis = simd_cross(zAxis, xAxis)
        let palmQ = simd_quatf(simd_float3x3(columns: (xAxis, yAxis, zAxis)))
        var Q = [simd_quatf](repeating: palmQ, count: P.count)
        let chains: [[Int]] = [[1, 2, 3, 4, 5], [1, 6, 7, 8, 9, 10], [1, 11, 12, 13, 14, 15], [1, 16, 17, 18, 19, 20], [1, 21, 22, 23, 24, 25]]
        for chain in chains {
            for k in 1..<chain.count {
                let j = chain[k], from = chain[k - 1]
                let dir = k + 1 < chain.count ? P[chain[k + 1]] - P[j] : P[j] - P[from]
                if !simd_length(dir).isFinite || simd_length(dir) < 1e-4 { continue }
                let z = -simd_normalize(dir)
                // Keep the finger's flexion axis aligned with the palm as it curls.
                // Crossing against the palm normal becomes singular at a 90-degree bend.
                let projectedX = xAxis - simd_dot(xAxis, z) * z
                let length = simd_length(projectedX)
                if !length.isFinite || length < 1e-3 { continue }
                let x = projectedX / length
                Q[j] = simd_quatf(simd_float3x3(columns: (x, simd_cross(z, x), z)))
            }
        }
        return Q
    }

    /// All hands seen in one camera frame: solved, given a left/right slot, smoothed and published.
    /// Slots follow position, not Vision's handedness (which flips from frame to frame): two hands -> the one on the left of the picture is
    /// the left hand; one hand -> the slot whose hand was last near that spot, and Vision's guess only when nothing else decides.
    @discardableResult
    static func publishAll(_ list: [VNHumanHandPoseObservation], cameraToWorld: simd_float4x4, K: simd_float3x3, size: CGSize, depthMap: CVPixelBuffer?) -> [(idx: Int32, m: HandMetrics, points: [VNHumanHandPoseObservation.JointName: CGPoint])] {
        let t = tuning
        let now = CACurrentMediaTime()
        let solved = list.compactMap { solve($0, cameraToWorld: cameraToWorld, K: K, size: size, depthMap: depthMap) }
        var slots = [Int32](repeating: -1, count: solved.count)
        if solved.count >= 2 {
            let order = solved.indices.sorted { solved[$0].u < solved[$1].u }
            var a = order[0], b = order[1]
            // Use the detector's handedness when it separates the pair. Keep recent identities
            // through crossings below so a brief classification flip does not swap both hands.
            if solved[a].chirality == 1 && solved[b].chirality == 0 { swap(&a, &b) }
            if t.swapHands { swap(&a, &b) }
            // hands that cross: when both slots were tracked a moment ago, the pairing that moves each hand the least wins
            if let l = lastWrist[0], let r = lastWrist[1], now - (lastSeen[0] ?? 0) < 0.4, now - (lastSeen[1] ?? 0) < 0.4 {
                let straight = simd_distance(solved[a].wrist, l) + simd_distance(solved[b].wrist, r)
                let crossed = simd_distance(solved[b].wrist, l) + simd_distance(solved[a].wrist, r)
                if crossed + 0.05 < straight { swap(&a, &b) }
            }
            slots[a] = 0; slots[b] = 1
        } else if solved.count == 1 {
            var best: Int32? = nil
            var bd: Float = 0.30
            for slot in [Int32(0), 1] {
                if let lw = lastWrist[slot], now - (lastSeen[slot] ?? 0) < 1.5 {
                    let d = simd_distance(lw, solved[0].wrist); if d < bd { bd = d; best = slot }
                }
            }
            slots[0] = best ?? solved[0].chirality
            if t.swapHands && best == nil { slots[0] = 1 - slots[0] }
        }
        var out: [(idx: Int32, m: HandMetrics, points: [VNHumanHandPoseObservation.JointName: CGPoint])] = []
        for (n, s) in solved.enumerated() where slots[n] >= 0 {
            let idx = slots[n]
            var P = s.P
            let dt = Float(max(0.01, now - (lastTime[idx] ?? now - 0.05)))
            // adaptive smoothing: calm when still (no jitter), practically raw when moving fast (no lag)
            let base = 1 - min(max(t.smoothing, 0), 0.92)
            var V = vel[idx] ?? [SIMD3<Float>](repeating: .zero, count: P.count)
            if let old = prev[idx], old.count == P.count, now - (lastSeen[idx] ?? 0) < 0.6 {
                // A rotating hand can look smaller in 2D, producing a spurious depth jump.
                // Limit translation of the entire hand without suppressing individual finger motion.
                let wristDelta = P[1] - old[1]
                let wristDistance = simd_length(wristDelta)
                let maxTravel = max(Float(0.035), min(Float(0.12), dt * 1.2))
                if wristDistance.isFinite, wristDistance > maxTravel {
                    let correction = wristDelta * (1 - maxTravel / wristDistance)
                    for i in P.indices { P[i] -= correction }
                }
                for i in 0..<P.count {
                    let speed = simd_distance(P[i], old[i]) / dt
                    let a = min(1, base + speed * 2.0)
                    let sm = old[i] + (P[i] - old[i]) * a
                    V[i] = V[i] * 0.5 + ((sm - old[i]) / dt) * 0.5
                    P[i] = sm
                }
            } else { V = [SIMD3<Float>](repeating: .zero, count: P.count) }
            prev[idx] = P; vel[idx] = V; radii[idx] = s.R
            lastWrist[idx] = P[1]; lastSeen[idx] = now; lastTime[idx] = now
            publishHand(idx, P, V, radii: s.R, lead: leadSeconds)
            func d(_ i: Int, _ j: Int) -> Float { simd_length(P[i] - P[j]) }
            let pinch = max(0, min(1, 1 - (d(5, 10) - 0.014) / (0.02 + 0.05 * (1 - t.pinchSensitivity))))
            let fist = max(0, min(1, (0.095 - (d(15, 0) + d(20, 0) + d(25, 0)) / 3) / 0.04))
            out.append((idx, HandMetrics(pinch: pinch, fist: fist, depth: s.depth, lidar: s.lidar), s.points))
        }
        return out
    }

    /// Sends a hand to the runtime, drawn `lead` seconds ahead along its own velocity (the camera frame is already that old when it arrives).
    /// A resting hand is not pushed (speed gate), so noise is not amplified; a fast one is not left behind.
    private static func publishHand(_ idx: Int32, _ P: [SIMD3<Float>], _ V: [SIMD3<Float>], radii R: [Float], lead: Float) {
        let wristSpeed = simd_length(V[1])
        let gate = min(1, max(0, (wristSpeed - 0.05) / 0.15))
        var Pp = P
        for i in 0..<P.count {
            var d = V[i] * lead * gate
            let len = simd_length(d); if len > 0.15 { d *= 0.15 / len }
            Pp[i] = P[i] + d
        }
        let chains: [[Int]] = [[1,2,3,4,5], [1,7,8,9,10], [1,12,13,14,15], [1,17,18,19,20], [1,22,23,24,25]]
        for (finger, chain) in chains.enumerated() {
            for k in 1..<chain.count {
                let parent = chain[k - 1], child = chain[k]
                let delta = Pp[child] - Pp[parent]
                let length = simd_length(delta)
                if length.isFinite, length > 1e-5 {
                    Pp[child] = Pp[parent] + delta * (boneLen[finger][k - 1] * tuning.handScale / length)
                }
            }
        }
        for (meta, mcp) in [(6,7), (11,12), (16,17), (21,22)] {
            Pp[meta] = Pp[1] + (Pp[mcp] - Pp[1]) * 0.6
        }
        Pp[0] = (Pp[1] + Pp[12]) * 0.5
        let Q = orient(Pp, right: idx == 1)
        var o = [Float](); o.reserveCapacity(26 * 8)
        for i in 0..<Pp.count { o += [Pp[i].x, Pp[i].y, Pp[i].z, Q[i].imag.x, Q[i].imag.y, Q[i].imag.z, Q[i].real, R[i]] }
        o.withUnsafeBufferPointer { olfx_xr_publish_hand(idx, 1, $0.baseAddress!) }
    }

    /// Brief occlusions keep the last pose; do not extrapolate noisy finger velocities.
    static func noteMissed(_ idx: Int32) {
        let dt = CACurrentMediaTime() - (lastSeen[idx] ?? 0)
        guard dt <= 0.15, let P = prev[idx], let R = radii[idx] else { clearHand(idx); return }
        publishHand(idx, P, [SIMD3<Float>](repeating: .zero, count: P.count), radii: R, lead: 0)
    }

}

extension simd_float4x4 {
    init(translation t: SIMD3<Float>) { self = matrix_identity_float4x4; columns.3 = SIMD4(t.x, t.y, t.z, 1) }
}


/// The Metal layer the Vulkan stereo compositor draws on: put above the game's view while a game runs. Transparent until the first frame.
@MainActor
enum StereoLayer {
    private static var layer: CAMetalLayer?
    private static var timer: Timer?

    static func attach() {
        detach()
        let l = CAMetalLayer()
        l.device = MTLCreateSystemDefaultDevice()
        l.pixelFormat = .bgra8Unorm
        l.framebufferOnly = false
        l.isOpaque = false
        // GLES and non-XR games render underneath; show this layer only after Vulkan presents.
        l.opacity = 0
        l.contentsScale = UIScreen.main.scale
        layer = l
        olfx_xr_set_metal_layer(Unmanaged.passUnretained(l).toOpaque())
        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { _ in Task { @MainActor in place() } }
        place()
    }
    static func detach() {
        timer?.invalidate(); timer = nil
        olfx_xr_set_metal_layer(nil)
        layer?.removeFromSuperlayer(); layer = nil
    }
    /// Keeps the layer on the topmost view (the game's full-screen cover) and the size of the screen.
    private static func place() {
        guard let l = layer,
              let win = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).flatMap({ $0.windows }).first(where: { $0.isKeyWindow }) else { return }
        var vc = win.rootViewController
        while let p = vc?.presentedViewController { vc = p }
        guard let host = vc?.view else { return }
        if l.superlayer !== host.layer { host.layer.addSublayer(l) }
        l.zPosition = 1000
        let opacity: Float = olfx_vk_frame_visible() != 0 ? 1 : 0
        if l.opacity != opacity {
            CATransaction.begin(); CATransaction.setDisableActions(true); l.opacity = opacity; CATransaction.commit()
        }
        if l.frame != host.bounds { CATransaction.begin(); CATransaction.setDisableActions(true); l.frame = host.bounds; CATransaction.commit() }
    }
}
