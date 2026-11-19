import SwiftUI
import Network
import CoreMotion
import simd
import Darwin

/// OLFX1: PIN, side, quaternion xyzw, trigger, grip. Shared with Android.
final class PhoneLink {
    static let shared = PhoneLink()
    private let queue = DispatchQueue(label: "org.olfx.phone-controller")
    private let lock = NSLock()
    private var listener: NWListener?
    private var connections: [NWConnection] = []
    private struct State { var values: [Float]; var received: TimeInterval }
    private var hands: [Int: State] = [:]
    var enabled: Bool { UserDefaults.standard.bool(forKey: "olfx.phone.host") }
    var pin: String {
        if let value = UserDefaults.standard.string(forKey: "olfx.phone.pin"), value.count == 6 { return value }
        let value = String(format: "%06d", Int.random(in: 0..<1_000_000))
        UserDefaults.standard.set(value, forKey: "olfx.phone.pin"); return value
    }
    func setEnabled(_ on: Bool) {
        UserDefaults.standard.set(on, forKey: "olfx.phone.host")
        if on { start() } else {
            queue.async { self.listener?.cancel(); self.listener = nil; self.connections.forEach { $0.cancel() }; self.connections.removeAll() }
            lock.lock(); hands.removeAll(); lock.unlock()
        }
    }
    func start() {
        guard enabled else { return }
        let expected = pin
        queue.async { self.startListener(expected: expected) }
    }
    private func startListener(expected: String) {
        guard enabled, listener == nil else { return }
        guard let server = try? NWListener(using: .udp, on: 39093) else { return }
        listener = server
        server.newConnectionHandler = { [weak self] connection in
            guard let self else { return }
            guard self.connections.count < 8 else { connection.cancel(); return }
            self.connections.append(connection)
            connection.stateUpdateHandler = { [weak self, weak connection] state in
                if case .failed = state, let connection { self?.connections.removeAll { $0 === connection } }
                if case .cancelled = state, let connection { self?.connections.removeAll { $0 === connection } }
            }
            connection.start(queue: self.queue)
            self.receive(connection, expected: expected)
        }
        server.start(queue: queue)
    }
    private func receive(_ connection: NWConnection, expected: String) {
        connection.receiveMessage { [weak self, weak connection] data, _, _, error in
            guard let self, let connection, error == nil else { connection?.cancel(); return }
            if let data, let text = String(data: data, encoding: .utf8) {
                let p = text.split(separator: " ")
                if p.count == 9, p[0] == "OLFX1", p[1] == Substring(expected), let side = Int(p[2]), (0...1).contains(side) {
                    let numbers = p[3...8].compactMap { Float($0) }
                    if numbers.count == 6, numbers.allSatisfy({ $0.isFinite }) {
                        let norm = numbers[0]*numbers[0] + numbers[1]*numbers[1] + numbers[2]*numbers[2] + numbers[3]*numbers[3]
                        if norm > 0.5, norm < 1.5 {
                            let scale = 1 / sqrt(norm)
                            let v = [numbers[0]*scale, numbers[1]*scale, numbers[2]*scale, numbers[3]*scale, min(1,max(0,numbers[4])), min(1,max(0,numbers[5]))]
                            self.lock.lock(); self.hands[side] = State(values: v, received: ProcessInfo.processInfo.systemUptime); self.lock.unlock()
                        }
                    }
                }
            }
            self.receive(connection, expected: expected)
        }
    }
    func merge(into pad: inout [Float]) {
        lock.lock(); defer { lock.unlock() }
        for (side, state) in hands where ProcessInfo.processInfo.systemUptime - state.received < 0.5 {
            for i in 0..<4 { pad[16 + side*4 + i] = state.values[i] }
            pad[24 + side] = 1; pad[4 + side] = max(pad[4 + side], state.values[4]); pad[6 + side] = max(pad[6 + side], state.values[5])
        }
    }
    static func localIP() -> String {
        var list: UnsafeMutablePointer<ifaddrs>?; guard getifaddrs(&list) == 0 else { return "—" }; defer { freeifaddrs(list) }
        var cur = list
        while let item = cur {
            defer { cur = item.pointee.ifa_next }
            guard let sa = item.pointee.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET), String(cString: item.pointee.ifa_name) == "en0" else { continue }
            var addr = sa.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr }; var buf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            if inet_ntop(AF_INET, &addr, &buf, socklen_t(buf.count)) != nil { return String(cString: buf) }
        }
        return "—"
    }
}

final class PhoneSender: ObservableObject {
    private let motion = CMMotionManager(); private var connection: NWConnection?; private var timer: Timer?; private var reference: simd_quatf?
    @Published var trigger = false; @Published var grip = false; @Published var active = false
    func start(host: String, pin: String, side: Int) {
        guard !host.isEmpty, pin.count == 6, pin.allSatisfy({ $0.isNumber }), motion.isDeviceMotionAvailable else { return }
        let c = NWConnection(host: NWEndpoint.Host(host), port: 39093, using: .udp); connection = c; c.start(queue: .global(qos: .userInteractive))
        motion.deviceMotionUpdateInterval = 1.0 / 50; motion.startDeviceMotionUpdates(); active = true; UIApplication.shared.isIdleTimerDisabled = true
        timer = Timer.scheduledTimer(withTimeInterval: 0.025, repeats: true) { [weak self] _ in
            guard let self, let m = self.motion.deviceMotion else { return }
            let a = m.attitude.quaternion; let q = simd_quatf(ix: Float(a.x), iy: Float(a.y), iz: Float(a.z), r: Float(a.w)).normalized
            if self.reference == nil { self.reference = q.inverse }
            let r = (self.reference! * q).normalized
            let text = "OLFX1 \(pin) \(side) \(r.imag.x) \(r.imag.y) \(r.imag.z) \(r.real) \(self.trigger ? 1 : 0) \(self.grip ? 1 : 0)"
            c.send(content: Data(text.utf8), completion: .contentProcessed { _ in })
        }
    }
    func recenter() { reference = nil }
    func stop() { timer?.invalidate(); timer = nil; motion.stopDeviceMotionUpdates(); connection?.cancel(); connection = nil; reference = nil; active = false; trigger = false; grip = false; UIApplication.shared.isIdleTimerDisabled = false }
    deinit { stop() }
}

struct PhoneControllerView: View {
    @AppStorage("olfx.phone.host") private var hostEnabled = false
    @StateObject private var sender = PhoneSender()
    @State private var address = ""; @State private var pin = ""; @State private var side = 0
    var body: some View {
        Form {
            Section("Телефон с игрой") {
                Toggle("Принимать контроллеры", isOn: $hostEnabled).onChange(of: hostEnabled) { PhoneLink.shared.setEnabled($0) }
                if hostEnabled { Text("Адрес: \(PhoneLink.localIP())"); Text("Код: \(PhoneLink.shared.pin)") }
            }
            Section("Этот телефон — контроллер") {
                TextField("IP телефона с игрой", text: $address).keyboardType(.decimalPad).textInputAutocapitalization(.never)
                TextField("Код подключения", text: $pin).keyboardType(.numberPad)
                Picker("Рука", selection: $side) { Text("Левая").tag(0); Text("Правая").tag(1) }.pickerStyle(.segmented)
                if !sender.active { Button("Подключить") { sender.start(host: address, pin: pin, side: side) } }
                else {
                    hold("Курок", down: $sender.trigger); hold("Захват", down: $sender.grip)
                    Button("Центрировать") { sender.recenter() }; Button("Отключить") { sender.stop() }
                }
            }
        }.navigationTitle("Телефоны-контроллеры").onDisappear { sender.stop() }
    }
    private func hold(_ text: String, down: Binding<Bool>) -> some View {
        Text(text).frame(maxWidth: .infinity).padding().background(down.wrappedValue ? Color.blue : Color.gray.opacity(0.3)).cornerRadius(10)
            .gesture(DragGesture(minimumDistance: 0).onChanged { _ in down.wrappedValue = true }.onEnded { _ in down.wrappedValue = false })
    }
}
