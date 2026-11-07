import SwiftUI

struct HandTrackingView: View {
    @EnvironmentObject var t: TrackingStore
    var body: some View {
        NavigationStack {
            Form {
                Section("Hand tracking") {
                    Toggle("Enable hands", isOn: t.$handsOn)
                    Picker("Engine", selection: t.$engineRaw) {
                        ForEach(HandEngine.allCases) { Text($0.rawValue).tag($0.rawValue) }
                    }.pickerStyle(.segmented)
                    if t.engine == .mediaPipe {
                        Text("MediaPipe runs the same model as on Android. Apple Vision is faster on iPhone.").font(.footnote).foregroundStyle(.secondary)
                    }
                }
                Section("Head tracking") {
                    Toggle("6DoF (position + rotation)", isOn: t.$sixDoF)
                }
                Section("Live") {
                    Text("Hands detected: \(t.handPoints.count)")
                }
            }
            .navigationTitle("Hand-Tracking")
            .onAppear { t.start() }.onDisappear { t.stop() }
        }
    }
}
