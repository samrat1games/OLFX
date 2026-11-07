import SwiftUI

struct HandTrackingView: View {
    @EnvironmentObject var t: TrackingStore
    var body: some View {
        NavigationStack {
            ZStack {
                AuroraBackground()
                ScrollView {
                    VStack(spacing: 16) {
                        CameraCheckView()
                        VStack(alignment: .leading, spacing: 12) {
                            Toggle("Enable hands", isOn: t.$handsOn)
                            Picker("Engine", selection: t.$engineRaw) {
                                ForEach(HandEngine.allCases) { Text($0.rawValue).tag($0.rawValue) }
                            }.pickerStyle(.segmented)
                            if t.engine == .mediaPipe {
                                Text("MediaPipe: same model as on Android (engine hookup in progress).").font(.footnote).foregroundStyle(.secondary)
                            }
                            Toggle("6DoF head tracking", isOn: t.$sixDoF)
                            Text("Hands detected: \(t.handPoints.count)").font(.footnote).foregroundStyle(.secondary)
                        }.padding(16).glass(cornerRadius: 24)
                    }.padding(16)
                }
            }
            .navigationTitle("Hand-Tracking")
            .onAppear { t.start() }.onDisappear { t.stop() }
        }
    }
}
