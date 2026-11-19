import Foundation
import UIKit

/// Breadcrumbs for launch crashes on other iOS versions: each stage appends a line to Documents/olfx-boot.txt (visible in Files > OLFX)
/// the moment it is reached. If the app dies, the last line says how far it got; no file at all means it died before our code ran.
enum Boot {
    private static let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("olfx-boot.txt")
    private nonisolated(unsafe) static var started = false
    static func mark(_ step: String) {
        if !started {
            started = true
            try? "".write(to: url, atomically: true, encoding: .utf8)            // new file each launch
            append("OLFX launch · iOS \(UIDevice.current.systemVersion) · \(UIDevice.current.model) · build \(Bundle.main.object(forInfoDictionaryKey: "OLFXVersion") as? String ?? "?")")
        }
        append(step)
    }
    private static func append(_ line: String) {
        guard let h = try? FileHandle(forWritingTo: url) else { try? (line + "\n").write(to: url, atomically: true, encoding: .utf8); return }
        h.seekToEndOfFile(); h.write((line + "\n").data(using: .utf8)!); try? h.synchronize(); try? h.close()
    }
}
