import SwiftUI
import Vision

/// Live view of what the games will get: the camera frame with the detected hand skeletons on top, readouts,
/// and every tracking setting. Runs the same ARKit + Apple Vision pipeline that feeds the OpenXR runtime.
struct HandTrackingView: View {
    @EnvironmentObject var t: TrackingStore
    @ObservedObject private var lang = Lang.shared

    var body: some View {
        NavigationStack {
            ZStack {
                AuroraBackground()
                ScrollView {
                    VStack(spacing: 16) {
                        modeCard
                        if t.gamepadMode {
                            gamepadCard
                        } else {
                            previewCard
                            readoutCard
                            handsCard
                            headCard
                        }
                        gameCard
                    }.padding(16)
                }
            }
            .navigationTitle(T("tracking"))
            .onAppear { t.previewEnabled = true; t.start() }.onDisappear { t.previewEnabled = false; t.stop() }
        }
    }

    // MARK: mode
    private var modeCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(T("modeTitle")).font(.headline)
            Picker("", selection: t.$modeRaw) { Text(T("modeFull")).tag("full"); Text(T("modeGamepad")).tag("gamepad") }
                .pickerStyle(.segmented).onChange(of: t.modeRaw) { _ in t.modeChanged() }
            Text(t.gamepadMode ? T("modeGamepadNote") : T("modeFullNote")).font(.footnote).foregroundStyle(.secondary)
        }.padding(16).glass(cornerRadius: 24)
    }
    private var gamepadCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("\(T("gamepads")): \(t.pads)").font(.subheadline)
            Text(T("joyconNote")).font(.footnote).foregroundStyle(.secondary)
            Button { t.recenter() } label: { Label(T("recenter"), systemImage: "scope").frame(maxWidth: .infinity) }.buttonStyle(.bordered)
        }.padding(16).glass(cornerRadius: 24)
    }

    // MARK: preview
    private var previewCard: some View {
        VStack(spacing: 10) {
            ZStack {
                Color.black
                if let img = t.preview {
                    GeometryReader { g in
                        let fit = AVFit(imageSize: img.size, in: g.size)
                        ZStack {
                            Image(uiImage: img).resizable().aspectRatio(contentMode: .fit).frame(width: g.size.width, height: g.size.height)
                            if t.showSkeleton {
                                Canvas { ctx, _ in
                                    for h in t.hands { SkeletonDrawer.draw(h, fit: fit, in: &ctx) }
                                }.allowsHitTesting(false)
                            }
                        }
                    }
                } else if !t.supported {
                    Text(T("arUnsupported")).foregroundStyle(.secondary).padding()
                } else {
                    VStack(spacing: 8) { ProgressView(); Text(T("camStarting")).font(.footnote).foregroundStyle(.secondary) }
                }
            }
            .aspectRatio(3.0 / 4.0, contentMode: .fit)
            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            HStack(spacing: 8) {
                chip("\(Int(t.fps)) fps", "speedometer")
                chip("\(T("handsDetected")): \(t.hands.count)", "hand.raised")
                chip("6DoF: \(t.arState)", "move.3d")
                chip(t.lidarAvailable && t.useLiDAR ? "LiDAR" : "RGB", "camera.metering.matrix")
            }.font(.caption2)
        }.padding(16).glass(cornerRadius: 24)
    }
    private func chip(_ s: String, _ icon: String) -> some View {
        Label(s, systemImage: icon).lineLimit(1).minimumScaleFactor(0.6).padding(.horizontal, 8).padding(.vertical, 5)
            .background(.thinMaterial, in: Capsule())
    }

    // MARK: readout
    private var readoutCard: some View {
        let p = t.headPose.columns.3
        let fwd = SIMD3<Float>(-t.headPose.columns.2.x, -t.headPose.columns.2.y, -t.headPose.columns.2.z)
        let yaw = atan2(fwd.x, -fwd.z) * 180 / .pi, pitch = asin(max(-1, min(1, fwd.y))) * 180 / .pi
        return VStack(alignment: .leading, spacing: 8) {
            Text(T("liveValues")).font(.headline)
            Text(String(format: "%@  x %.2f  y %.2f  z %.2f m   yaw %.0f°  pitch %.0f°", T("head"), p.x, p.y, p.z, yaw, pitch))
                .font(.system(.footnote, design: .monospaced))
            ForEach([Int32(0), 1], id: \.self) { i in
                let h = t.hands.first { $0.id == i }
                HStack {
                    Text(i == 0 ? T("leftHand") : T("rightHand")).font(.footnote)
                    Spacer()
                    if let m = h?.metrics {
                        Text(String(format: "%@ %3.0f%%   %@ %3.0f%%   %.2f m%@", T("pinch"), m.pinch * 100, T("fist"), m.fist * 100, m.depth, m.lidar ? " LiDAR" : ""))
                            .font(.system(.footnote, design: .monospaced))
                    } else { Text("—").foregroundStyle(.secondary) }
                }
            }
        }.frame(maxWidth: .infinity, alignment: .leading).padding(16).glass(cornerRadius: 24)
    }

    // MARK: settings
    private var handsCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(T("handsSection")).font(.headline)
            Toggle(T("enableHands"), isOn: t.$handsOn)
            Picker(T("engine"), selection: t.$engineRaw) { ForEach(HandEngine.allCases) { Text($0.rawValue).tag($0.rawValue) } }.pickerStyle(.segmented)
            if t.engine == .mediaPipe { Text(T("mediapipeSoon")).font(.footnote).foregroundStyle(.secondary) }
            Picker(T("maxHands"), selection: t.$maxHands) { Text("1").tag(1); Text("2").tag(2) }.pickerStyle(.segmented)
            slider(T("handRate"), t.$handHz, 10...30, "%.0f Hz")
            slider(T("smoothing"), t.$smoothing, 0...0.9, "%.2f")
            slider(T("minConf"), t.$minConfidence, 0.1...0.9, "%.2f")
            slider(T("handSize"), t.$handScale, 0.7...1.4, "×%.2f")
            slider(T("pinchSens"), t.$pinchSensitivity, 0...1, "%.2f")
            Toggle(T("swapHands"), isOn: t.$swapHands)
            Toggle(T("lidar"), isOn: t.$useLiDAR).disabled(!t.lidarAvailable)
            Toggle(T("skeleton"), isOn: t.$showSkeleton)
        }.padding(16).glass(cornerRadius: 24)
    }
    private var headCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(T("headSection")).font(.headline)
            Toggle(T("sixdof"), isOn: t.$sixDoF)
            slider(T("posScale"), t.$positionScale, 0.5...2.0, "×%.2f")
            Button { t.recenter() } label: { Label(T("recenter"), systemImage: "scope").frame(maxWidth: .infinity) }.buttonStyle(.bordered)
        }.padding(16).glass(cornerRadius: 24)
    }
    private var gameCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(T("xr")).font(.headline)
            Toggle(T("xrOn"), isOn: t.$xrInGames)
            Toggle(T("ovrpGLES"), isOn: t.$ovrpGLES)
            Text(T("xrNote")).font(.footnote).foregroundStyle(.secondary)
            Text("\(T("gamepads")): \(t.pads)").font(.footnote).foregroundStyle(.secondary)
        }.padding(16).glass(cornerRadius: 24)
    }
    private func slider(_ title: String, _ v: Binding<Double>, _ r: ClosedRange<Double>, _ fmt: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack { Text(title).font(.subheadline); Spacer(); Text(String(format: fmt, v.wrappedValue)).font(.system(.footnote, design: .monospaced)).foregroundStyle(.secondary) }
            Slider(value: v, in: r)
        }
    }
}

/// Where an image sits inside a container when scaled to fit, so overlay points can be mapped onto it.
struct AVFit {
    let rect: CGRect
    init(imageSize: CGSize, in box: CGSize) {
        let s = min(box.width / imageSize.width, box.height / imageSize.height)
        let w = imageSize.width * s, h = imageSize.height * s
        rect = CGRect(x: (box.width - w) / 2, y: (box.height - h) / 2, width: w, height: h)
    }
    /// Vision's normalised point (origin bottom-left) -> view point.
    func map(_ p: CGPoint) -> CGPoint { CGPoint(x: rect.minX + p.x * rect.width, y: rect.minY + (1 - p.y) * rect.height) }
}

enum SkeletonDrawer {
    typealias J = VNHumanHandPoseObservation.JointName
    static let bones: [[J]] = [
        [.wrist, .thumbCMC, .thumbMP, .thumbIP, .thumbTip],
        [.wrist, .indexMCP, .indexPIP, .indexDIP, .indexTip],
        [.wrist, .middleMCP, .middlePIP, .middleDIP, .middleTip],
        [.wrist, .ringMCP, .ringPIP, .ringDIP, .ringTip],
        [.wrist, .littleMCP, .littlePIP, .littleDIP, .littleTip],
        [.indexMCP, .middleMCP, .ringMCP, .littleMCP],
    ]
    static func draw(_ h: HandViz, fit: AVFit, in ctx: inout GraphicsContext) {
        let color: Color = h.id == 0 ? .cyan : .orange
        for chain in bones {
            var path = Path(); var started = false
            for j in chain {
                guard let p = h.points[j] else { started = false; continue }
                let q = fit.map(p)
                if started { path.addLine(to: q) } else { path.move(to: q); started = true }
            }
            ctx.stroke(path, with: .color(color.opacity(0.9)), lineWidth: 3)
        }
        for (_, p) in h.points {
            let q = fit.map(p)
            ctx.fill(Path(ellipseIn: CGRect(x: q.x - 4, y: q.y - 4, width: 8, height: 8)), with: .color(.white))
        }
        // pinch marker between the thumb and index tips
        if let a = h.points[.thumbTip], let b = h.points[.indexTip], h.metrics.pinch > 0.6 {
            let m = fit.map(CGPoint(x: (a.x + b.x) / 2, y: (a.y + b.y) / 2))
            ctx.fill(Path(ellipseIn: CGRect(x: m.x - 10, y: m.y - 10, width: 20, height: 20)), with: .color(.green.opacity(0.8)))
        }
    }
}
