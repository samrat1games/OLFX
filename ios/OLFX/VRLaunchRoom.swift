import SwiftUI
import simd

@_silgen_name("olfx_reset_launch_frame") func olfxResetLaunchFrame()
@_silgen_name("olfx_launch_frame") func olfxLaunchFrame() -> Int32
@_silgen_name("olfx_loading_poses")
private func loadingPoses(_ head: UnsafeMutablePointer<Float>, _ hands: UnsafeMutablePointer<Float>) -> Int32

/// Two views of the same floor and tracked hands, projected from eyes 64 mm apart.
struct VRLaunchRoom: View {
    let title: String
    let status: String
    let failed: Bool
    let cancel: () -> Void
    @State private var scene = RoomPose()

    var body: some View {
        ZStack(alignment: .topTrailing) {
            GeometryReader { geometry in
                let horizontal = geometry.size.width >= geometry.size.height
                TimelineView(.animation(minimumInterval: 1.0 / 60)) { _ in
                    let pose = scene.sample()
                    let layout = horizontal ? AnyLayout(HStackLayout(spacing: 0)) : AnyLayout(VStackLayout(spacing: 0))
                    layout {
                        eye(pose, offset: -0.032)
                        eye(pose, offset: 0.032)
                    }
                }
            }
            Button(action: cancel) {
                Image(systemName: "xmark").font(.body.weight(.semibold))
                    .frame(width: 44, height: 44)
                    .background(.white.opacity(0.14), in: Circle())
            }
            .foregroundStyle(.white)
            .accessibilityLabel(T("cancel"))
            .padding(12)
        }
        .background(.black)
        .statusBarHidden()
    }

    private func eye(_ pose: Pose, offset: Float) -> some View {
        GeometryReader { geometry in
            ZStack(alignment: .topLeading) {
                room(pose, offset: offset)
                if let card = cardProjection(pose, offset: offset, size: geometry.size) {
                    statusCard
                        .scaleEffect(card.scale)
                        .position(card.position)
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 46))
        .overlay { RoundedRectangle(cornerRadius: 46).strokeBorder(.white.opacity(0.09)) }
        .padding(2)
        .allowsHitTesting(false)
    }

    private func cardProjection(_ pose: Pose, offset: Float, size: CGSize) -> (position: CGPoint, scale: CGFloat)? {
        let anchor = pose.anchor
        let p = pose.head.inverse * SIMD4(anchor.x, anchor.y, anchor.z, 1)
        guard p.z < -0.45 else { return nil }
        let focal = Float(min(size.width, size.height)) * 0.68
        let x = CGFloat(focal * (p.x - offset) / -p.z) + size.width / 2
        let y = CGFloat(focal * p.y / p.z) + size.height / 2
        return (CGPoint(x: x, y: y), CGFloat(min(1.28, 2.1 / -p.z)))
    }

    private var statusCard: some View {
        VStack(spacing: 8) {
            if failed {
                Image(systemName: "exclamationmark.triangle").font(.title3)
            } else {
                ProgressView().tint(.white)
            }
            Text(title).font(.headline).lineLimit(2)
            Text(status).font(.caption).foregroundStyle(.white.opacity(0.77)).lineLimit(7)
        }
        .foregroundStyle(.white)
        .multilineTextAlignment(.center)
        .padding(12)
        .frame(width: 230)
        .background(.black.opacity(0.85), in: RoundedRectangle(cornerRadius: 15))
    }

    private func room(_ pose: Pose, offset: Float) -> some View {
        Canvas { context, size in
            let focal = Float(min(size.width, size.height)) * 0.68
            let origin = CGPoint(x: size.width / 2, y: size.height / 2)
            let transform = pose.head.inverse
            func eyePoint(_ p: SIMD3<Float>) -> SIMD3<Float> {
                let v = transform * SIMD4(p.x, p.y, p.z, 1)
                return SIMD3(v.x - offset, v.y, v.z)
            }
            func project(_ p: SIMD3<Float>) -> CGPoint {
                CGPoint(x: origin.x + CGFloat(focal * p.x / -p.z),
                        y: origin.y - CGFloat(focal * p.y / -p.z))
            }
            func segment(_ first: SIMD3<Float>, _ second: SIMD3<Float>, in path: inout Path) {
                let near: Float = -0.12
                var a = eyePoint(first), b = eyePoint(second)
                guard a.z <= near || b.z <= near else { return }
                if a.z > near { a += (b - a) * ((near - a.z) / (b.z - a.z)) }
                if b.z > near { b += (a - b) * ((near - b.z) / (a.z - b.z)) }
                path.move(to: project(a))
                path.addLine(to: project(b))
            }

            let x = floor(pose.head.columns.3.x), z = floor(pose.head.columns.3.z)
            let span: Float = 38
            let bounds = Path(CGRect(origin: .zero, size: size))
            var grid = Path()
            for i in -38...38 {
                let v = Float(i)
                segment(SIMD3(x + v, 0, z - span), SIMD3(x + v, 0, z + span), in: &grid)
                segment(SIMD3(x - span, 0, z + v), SIMD3(x + span, 0, z + v), in: &grid)
            }
            context.fill(bounds, with: .radialGradient(
                Gradient(colors: [Color(white: 0.18), .black]),
                center: origin, startRadius: 0, endRadius: max(size.width, size.height) * 0.64))
            context.stroke(grid, with: .radialGradient(
                Gradient(colors: [.white.opacity(0.30), .white.opacity(0.04)]),
                center: origin, startRadius: 0, endRadius: size.height * 0.68), lineWidth: 0.65)

            for hand in pose.hands {
                let points = hand.joints.map(eyePoint)
                guard points[1].z < -0.10 else { continue }
                let palm = [1, 2, 7, 12, 17, 22]
                var shapes = [Path]()
                if palm.allSatisfy({ points[$0].z < -0.10 }) {
                    let outline = palm.map { project(points[$0]) }
                    var path = Path()
                    let ends = outline.indices.map { i -> CGPoint in
                        let a = outline[i], b = outline[(i + 1) % outline.count]
                        return CGPoint(x: (a.x + b.x) / 2, y: (a.y + b.y) / 2)
                    }
                    path.move(to: ends[ends.count - 1])
                    for i in outline.indices { path.addQuadCurve(to: ends[i], control: outline[i]) }
                    path.closeSubpath()
                    shapes.append(path)
                    let inset = CGFloat(focal * 0.022 / -points[1].z)
                    shapes.append(path.strokedPath(StrokeStyle(lineWidth: inset, lineJoin: .round)))
                }
                let fingers = [2...5, 7...10, 12...15, 17...20, 22...25]
                for (fingerIndex, finger) in fingers.enumerated() {
                    for joint in finger.dropLast() {
                        let a = points[joint], b = points[joint + 1]
                        guard a.z < -0.10, b.z < -0.10 else { continue }
                        let taper = 1 - Float(joint - finger.lowerBound) * 0.17
                        let diameter: Float = fingerIndex == 0 ? 0.023 : 0.018
                        let width = CGFloat(focal * diameter * taper / -((a.z + b.z) / 2))
                        var segment = Path()
                        segment.move(to: project(a))
                        segment.addLine(to: project(b))
                        shapes.append(segment.strokedPath(StrokeStyle(lineWidth: max(1, width), lineCap: .round)))
                    }
                }
                var translucent = context
                translucent.opacity = Double(hand.alpha) * 0.50
                translucent.drawLayer { layer in
                    for shape in shapes { layer.fill(shape, with: .color(Color(white: 0.60))) }
                }
            }
        }
    }

    private struct Pose {
        let head: simd_float4x4
        let anchor: SIMD3<Float>
        let hands: [Hand]
    }

    private struct Hand {
        var joints = [SIMD3<Float>]()
        var alpha: Float = 0
    }

    private final class RoomPose {
        private var anchor: SIMD3<Float>?
        private var lastSample = CACurrentMediaTime()
        private var handStates = [Hand(), Hand()]

        func sample() -> Pose {
            var matrix = [Float](repeating: 0, count: 16)
            var joints = [Float](repeating: 0, count: 156)
            let mask = loadingPoses(&matrix, &joints)
            let head = simd_float4x4(columns: (
                SIMD4(matrix[0], matrix[1], matrix[2], matrix[3]),
                SIMD4(matrix[4], matrix[5], matrix[6], matrix[7]),
                SIMD4(matrix[8], matrix[9], matrix[10], matrix[11]),
                SIMD4(matrix[12], matrix[13], matrix[14], matrix[15])))
            if anchor == nil, mask & 4 != 0 {
                let facing = SIMD2(-matrix[8], -matrix[10])
                let forward = simd_length_squared(facing) > 0.01 ? simd_normalize(facing) : SIMD2<Float>(0, -1)
                anchor = SIMD3(matrix[12] + forward.x * 2.1, matrix[13] - 0.13, matrix[14] + forward.y * 2.1)
            }

            let now = CACurrentMediaTime()
            let dt = Float(min(0.10, max(0, now - lastSample)))
            lastSample = now
            for hand in handStates.indices {
                if mask & (1 << hand) == 0 {
                    handStates[hand].alpha = max(0, handStates[hand].alpha - dt * 8)
                    if handStates[hand].alpha == 0 { handStates[hand].joints.removeAll(keepingCapacity: true) }
                    continue
                }
                let raw = (0..<26).map { joint in
                    let i = (hand * 26 + joint) * 3
                    return SIMD3(joints[i], joints[i + 1], joints[i + 2])
                }
                if handStates[hand].joints.isEmpty {
                    handStates[hand].joints = raw
                } else {
                    let speed = simd_distance(raw[1], handStates[hand].joints[1])
                    let response: Float = speed > 0.045 ? 25 : 14
                    let weight = 1 - exp(-dt * response)
                    handStates[hand].joints = zip(handStates[hand].joints, raw).map { previous, next in
                        previous + (next - previous) * weight
                    }
                }
                handStates[hand].alpha = min(1, handStates[hand].alpha + dt * 10)
            }
            return Pose(head: head, anchor: anchor ?? SIMD3(0, 1.47, -2.1),
                        hands: handStates.filter { !$0.joints.isEmpty && $0.alpha > 0 })
        }
    }
}
