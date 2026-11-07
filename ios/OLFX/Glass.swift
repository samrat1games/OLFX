import SwiftUI

/// Liquid Glass on iOS 26+ (when built with the new SDK), blurred material everywhere else.
extension View {
    @ViewBuilder func glass(cornerRadius: CGFloat = 24, interactive: Bool = false) -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26.0, *) {
            self.glassEffect(interactive ? .regular.interactive() : .regular, in: .rect(cornerRadius: cornerRadius))
        } else {
            self.background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        }
        #else
        self.background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        #endif
    }
}

struct AuroraBackground: View {
    @State private var t = false
    var body: some View {
        ZStack {
            Color(red: 0.04, green: 0.05, blue: 0.09)
            Circle().fill(Color.indigo.opacity(0.55)).frame(width: 340).blur(radius: 90).offset(x: t ? -90 : 90, y: t ? -220 : -120)
            Circle().fill(Color.cyan.opacity(0.35)).frame(width: 300).blur(radius: 90).offset(x: t ? 110 : -60, y: t ? 200 : 260)
            Circle().fill(Color.purple.opacity(0.4)).frame(width: 260).blur(radius: 80).offset(x: t ? 20 : -120, y: t ? 40 : -20)
        }
        .ignoresSafeArea()
        .onAppear { withAnimation(.easeInOut(duration: 9).repeatForever(autoreverses: true)) { t.toggle() } }
    }
}
