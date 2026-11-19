import SwiftUI

enum Language: String, CaseIterable, Identifiable {
    case ru, en, ptBR = "pt-BR", pt
    var id: String { rawValue }
    var title: String {
        switch self { case .ru: return "Русский"; case .en: return "English"; case .ptBR: return "Português (Brasil)"; case .pt: return "Português (Portugal)" }
    }
}

/// In-app language, switchable without restarting. `Lang.shared.t("key")`; views observe `Lang.shared` so they redraw.
final class Lang: ObservableObject {
    static let shared = Lang()
    @Published var current: Language {
        didSet { UserDefaults.standard.set(current.rawValue, forKey: "olfx.lang") }
    }
    init() {
        if let s = UserDefaults.standard.string(forKey: "olfx.lang"), let l = Language(rawValue: s) { current = l }
        else {
            let pref = Locale.preferredLanguages.first ?? "en"
            current = pref.hasPrefix("ru") ? .ru : pref.lowercased().hasPrefix("pt-br") ? .ptBR : pref.hasPrefix("pt") ? .pt : .en
        }
    }
    func t(_ key: String) -> String {
        guard let row = Strings.table[key] else { return key }
        switch current {
        case .en: return row[0]
        case .ru: return row[1]
        case .ptBR: return row[2]
        case .pt: return row.count > 3 ? row[3] : row[2]
        }
    }
}

/// key: [en, ru, pt-BR, pt-PT (optional: falls back to pt-BR)]
enum Strings {
    static let table: [String: [String]] = [
        "thermalStop": ["The device is too warm. The game was stopped. Let the device cool down before playing again.", "Устройство слишком нагрелось. Игра остановлена. Дайте ему остыть перед следующим запуском.", "O aparelho está quente demais. O jogo foi interrompido. Deixe-o esfriar antes de jogar novamente."],
        "launchPaused": ["Launch is temporarily paused. Try again later.", "Запуск временно приостановлен. Повторите попытку позже.", "Inicialização pausada. Tente novamente mais tarde."],
        "launchBusy": ["Another launch is already in progress.", "Другой запуск уже выполняется.", "Outra inicialização já está em andamento."],
        "games": ["Games", "Игры", "Jogos"],
        "favorite": ["Favorite", "Избранное", "Favoritos"],
        "arUnsupported": ["ARKit is not supported on this device", "ARKit не поддерживается на этом устройстве", "ARKit não é compatível com este aparelho", "O ARKit não é suportado neste dispositivo"],
        "camStarting": ["Starting the camera…", "Запускаем камеру…", "Iniciando a câmera…", "A iniciar a câmara…"],
        "liveValues": ["Live values", "Значения в реальном времени", "Valores ao vivo"],
        "head": ["Head", "Голова", "Cabeça"],
        "leftHand": ["Left hand", "Левая рука", "Mão esquerda"],
        "rightHand": ["Right hand", "Правая рука", "Mão direita"],
        "pinch": ["pinch", "щипок", "pinça"],
        "fist": ["fist", "кулак", "punho"],
        "handsSection": ["Hands (like Meta hand tracking)", "Руки (как в Meta hand tracking)", "Mãos (como o rastreio de mãos da Meta)", "Mãos (como o seguimento de mãos da Meta)"],
        "headSection": ["Head / 6DoF", "Голова / 6DoF", "Cabeça / 6DoF"],
        "mediapipeSoon": ["MediaPipe: not available in this build yet.", "MediaPipe: в этой сборке пока недоступен.", "MediaPipe: ainda não disponível nesta versão."],
        "maxHands": ["Hands tracked", "Сколько рук отслеживать", "Mãos rastreadas", "Mãos seguidas"],
        "handRate": ["Hand update rate (lower = faster game)", "Частота рук (ниже = быстрее игра)", "Taxa das mãos (menor = jogo mais rápido)"],
        "smoothing": ["Smoothing", "Сглаживание", "Suavização"],
        "minConf": ["Minimum confidence", "Минимальная уверенность", "Confiança mínima"],
        "handSize": ["Hand size", "Размер кисти", "Tamanho da mão"],
        "pinchSens": ["Pinch sensitivity", "Чувствительность щипка", "Sensibilidade da pinça"],
        "swapHands": ["Swap left / right", "Поменять левую и правую", "Trocar esquerda / direita"],
        "lidar": ["Use LiDAR depth (if available)", "Глубина LiDAR (если есть)", "Usar profundidade LiDAR (se houver)"],
        "skeleton": ["Show skeleton", "Показывать скелет", "Mostrar esqueleto"],
        "posScale": ["Movement scale", "Масштаб перемещения", "Escala de movimento"],
        "recenter": ["Recenter", "Центрировать", "Recentralizar"],
        "modeTitle": ["Tracking mode", "Режим трекинга", "Modo de rastreio", "Modo de seguimento"],
        "modeFull": ["Hands + 6DoF", "Руки + 6DoF", "Mãos + 6DoF"],
        "modeGamepad": ["Gamepad only", "Только геймпад", "Só controle", "Só comando"],
        "modeFullNote": ["Camera: ARKit 6DoF + Apple Vision hands. Heavier.", "Камера: ARKit 6DoF + руки Apple Vision. Тяжелее.", "Câmera: ARKit 6DoF + mãos Apple Vision. Mais pesado.", "Câmara: ARKit 6DoF + mãos Apple Vision. Mais pesado."],
        "modeGamepadNote": ["No camera, no ARKit, no Vision: the most speed. Head = phone gyro (3DoF). Controllers = gamepad or Joy-Cons.", "Без камеры, ARKit и Vision: максимум скорости. Голова = гироскоп телефона (3DoF). Контроллеры = геймпад или Joy-Con.", "Sem câmera, ARKit ou Vision: máxima velocidade. Cabeça = giroscópio do celular (3DoF). Controles = gamepad ou Joy-Con.", "Sem câmara, ARKit ou Vision: velocidade máxima. Cabeça = giroscópio do telemóvel (3DoF). Comandos = gamepad ou Joy-Con."],
        "joyconNote": ["Joy-Con L + R act as the two Oculus Touch controllers: sticks, triggers (ZL/ZR), grips (L/R), A/B/X/Y, menu, and orientation from the gyro. One normal gamepad drives both hands without pose. Connect them in Bluetooth settings, then press Recenter while holding them forward.", "Joy-Con L + R работают как два контроллера Oculus Touch: стики, курки (ZL/ZR), хваты (L/R), A/B/X/Y, меню и ориентация по гироскопу. Один обычный геймпад управляет обеими руками без позы. Подключите их в настройках Bluetooth, затем нажмите «Центрировать», держа их перед собой.", "Joy-Con L + R funcionam como os dois controles Oculus Touch: analógicos, gatilhos (ZL/ZR), grips (L/R), A/B/X/Y, menu e orientação pelo giroscópio. Um controle comum move as duas mãos sem pose. Conecte no Bluetooth e toque em Recentralizar segurando-os à frente."],
        "cover": ["Choose cover", "Выбрать обложку", "Escolher capa"],
        "quality": ["Performance", "Производительность", "Desempenho"],
        "qFast": ["Fast (60 fps)", "Быстро (60 fps)", "Rápido (60 fps)"],
        "qBalanced": ["Balanced", "Баланс", "Equilibrado"],
        "qSharp": ["Sharp", "Чётко", "Nítido"],
        "tracking": ["Hand-Tracking", "Трекинг рук", "Rastreio de mãos", "Seguimento de mãos"],
        "settings": ["Settings", "Настройки", "Configurações", "Definições"],
        "addHint": ["Tap + to add an APK", "Нажмите +, чтобы добавить APK", "Toque em + para adicionar um APK"],
        "noFav": ["No favourites yet", "Пока нет избранного", "Nenhum favorito ainda", "Ainda sem favoritos"],
        "play": ["Play", "Играть", "Jogar"],
        "addFav": ["Add to favourites", "В избранное", "Adicionar aos favoritos"],
        "removeFav": ["Remove from favourites", "Убрать из избранного", "Remover dos favoritos"],
        "delete": ["Delete", "Удалить", "Excluir", "Eliminar"],
        "adding": ["Adding the game…", "Добавляем игру…", "Adicionando o jogo…", "A adicionar o jogo…"],
        "playNote": ["Runs on the built-in engine. JIT must be on.", "Работает на встроенном движке. Нужен включённый JIT.", "Roda no motor integrado. O JIT precisa estar ligado.", "Corre no motor integrado. O JIT tem de estar ligado."],
        "turningJit": ["Turning on JIT…", "Включаем JIT…", "Ligando o JIT…", "A ligar o JIT…"],
        "jitNeeded": ["JIT is off. Turn it on in Settings, then press Play again.", "JIT выключен. Включите его в Настройках и нажмите «Играть» ещё раз.", "O JIT está desligado. Ligue em Configurações e toque em Jogar de novo.", "O JIT está desligado. Ligue em Definições e toque em Jogar outra vez."],
        "restartTitle": ["Restart OLFX?", "Перезапустить OLFX?", "Reiniciar o OLFX?"],
        "restartMsg": ["Another game is already loaded and only one game can run per launch. OLFX closes and starts this one when you open it again.", "Другая игра уже загружена, за один запуск можно играть только в одну. OLFX закроется и сам запустит эту игру при следующем открытии.", "Outro jogo já está carregado e só um jogo roda por vez. O OLFX fecha e inicia este jogo quando você abrir de novo.", "Já está carregado outro jogo e só um corre de cada vez. O OLFX fecha e inicia este jogo quando o abrir outra vez."],
        "restart": ["Restart", "Перезапустить", "Reiniciar"],
        "cancel": ["Cancel", "Отмена", "Cancelar"],
        "launchPreparing": ["Preparing launch…", "Подготовка к запуску…", "A preparar o jogo…"],
        "launchLoading": ["Loading the game…", "Загрузка игры…", "A carregar o jogo…"],
        "launchNoFrame": ["Still waiting for the first frame. You can wait or close this screen.", "Первый кадр ещё не появился. Можно подождать или закрыть экран.", "Ainda à espera do primeiro fotograma. Pode esperar ou fechar este ecrã."],
        "launchNoStereoFrame": ["The XR session opened, but the game hasn't displayed a stereo frame.", "XR-сессия открылась, но игра пока не вывела стереокадр.", "A sessão XR abriu, mas o jogo ainda não apresentou uma imagem estéreo."],
        "launchUnityARM32": ["The 32-bit Unity loader failed to start. Use the complete ARM64 edition of the game if one is available.", "32-битный загрузчик Unity не запустился. Если доступна полная версия игры для ARM64, импортируй её.", "O carregador Unity de 32 bits falhou. Se existir uma versão completa para ARM64, importa-a."],
        "launchMissingSplits": ["This APK requires additional files. Import the complete app bundle.", "Для этого APK нужны дополнительные части. Импортируй полный комплект приложения.", "Este APK precisa de partes adicionais. Importa o pacote completo."],
        "launchFailed": ["The engine stopped before displaying a frame.", "Движок остановился до вывода первого кадра.", "O motor parou antes de apresentar o primeiro fotograma."],
        "metaUnavailableTitle": ["Meta ownership check unavailable", "Проверка покупки Meta недоступна", "Verificação de compra da Meta indisponível"],
        "metaUnavailableMessage": ["This game asked Meta to verify ownership. Meta Platform is unavailable in OLFX on iOS. If the game requires verification, open it on a supported Meta device with the account that owns it.", "Игра запросила проверку покупки через Meta. Meta Platform недоступна в OLFX на iOS. Если игра требует проверки, запустите её на совместимом устройстве Meta под аккаунтом, на котором она куплена.", "O jogo pediu à Meta para verificar a compra. A Meta Platform está indisponível no OLFX para iOS. Se o jogo exigir a verificação, abra-o num dispositivo Meta compatível com a conta que comprou o jogo."],
        "ok": ["OK", "Ок", "OK"],
        "language": ["Language", "Язык", "Idioma"],
        "jit": ["JIT (needed for APKs)", "JIT (нужен для APK)", "JIT (necessário para APKs)"],
        "jitStatus": ["JIT status", "Состояние JIT", "Estado do JIT"],
        "on": ["On", "Включён", "Ligado"],
        "off": ["Off", "Выключен", "Desligado"],
        "jitMethod": ["JIT method", "Способ включения JIT", "Método do JIT"],
        "jitEnable": ["Turn on JIT", "Включить JIT", "Ligar o JIT"],
        "jitSetup": ["JIT setup / pairing…", "Настройка JIT / сопряжение…", "Configurar JIT / pareamento…", "Configurar JIT / emparelhamento…"],
        "jitAuto": ["Turn on JIT at launch", "Включать JIT при запуске", "Ligar o JIT ao abrir"],
        "jitless": ["JIT-less mode (needs signing certificate)", "Режим без JIT (нужен сертификат подписи)", "Modo sem JIT (precisa de certificado)"],
        "xr": ["VR / Quest games", "VR / игры Quest", "Jogos VR / Quest"],
        "ovrpGLES": ["Oculus-plugin games: try VR through OpenGL ES (experimental, may freeze)", "Игры с Oculus-плагином: VR через OpenGL ES (экспериментально, может зависнуть)", "Jogos com plugin Oculus: VR via OpenGL ES (experimental, pode travar)", "Jogos com plugin Oculus: VR via OpenGL ES (experimental, pode bloquear)"],
        "xrOn": ["Hands, 6DoF and gamepad as VR controllers", "Руки, 6DoF и геймпад как VR-контроллеры", "Mãos, 6DoF e controle como controles de VR", "Mãos, 6DoF e comando como comandos de VR"],
        "xrNote": ["Needs camera access. Games must use OpenXR + OpenGL ES (no Vulkan yet).", "Нужен доступ к камере. Игры должны использовать OpenXR + OpenGL ES (Vulkan пока нет).", "Precisa de acesso à câmera. Os jogos devem usar OpenXR + OpenGL ES (ainda sem Vulkan).", "Precisa de acesso à câmara. Os jogos têm de usar OpenXR + OpenGL ES (ainda sem Vulkan)."],
        "vulkan": ["Vulkan (try if the game says \"device does not match requirements\"; restart OLFX after changing)", "Vulkan (включите, если игра пишет «устройство не соответствует требованиям»; после смены перезапустите OLFX)", "Vulkan (tente se o jogo disser \"dispositivo não atende aos requisitos\"; reinicie o OLFX após mudar)", "Vulkan (experimente se o jogo disser \"dispositivo não cumpre os requisitos\"; reinicie o OLFX depois de mudar)"],
        "version": ["Version", "Версия", "Versão"],
        "developers": ["Developers", "Разработчики", "Desenvolvedores"],
        "shareLog": ["Share log", "Поделиться логом", "Compartilhar log", "Partilhar registo"],
        "jitContainer": ["JIT is attached, but the app cannot get executable memory. This happens inside LiveContainer on iOS 26+. Install OLFX directly (SideStore) and enable JIT with StikDebug.", "JIT подключён, но приложение не может получить исполняемую память. Так бывает внутри LiveContainer на iOS 26+. Установите OLFX напрямую (SideStore) и включите JIT через StikDebug.", "O JIT está conectado, mas o app não consegue memória executável. Isso acontece dentro do LiveContainer no iOS 26+. Instale o OLFX direto (SideStore) e ligue o JIT com o StikDebug.", "O JIT está ligado, mas a app não consegue memória executável. Isto acontece dentro do LiveContainer no iOS 26+. Instale o OLFX diretamente (SideStore) e ligue o JIT com o StikDebug."],
        "enableHands": ["Enable hands", "Включить руки", "Ativar mãos"],
        "engine": ["Engine", "Движок", "Motor"],
        "sixdof": ["6DoF head tracking", "6DoF-трекинг головы", "Rastreio de cabeça 6DoF", "Seguimento de cabeça 6DoF"],
        "handsDetected": ["Hands detected", "Найдено рук", "Mãos detectadas"],
        "camNeeded": ["Camera permission needed", "Нужен доступ к камере", "Permissão da câmera necessária", "Permissão da câmara necessária"],
        "camAllow": ["Allow camera", "Разрешить камеру", "Permitir câmera", "Permitir câmara"],
        "camOpen": ["Open Settings", "Открыть настройки", "Abrir Ajustes", "Abrir Definições"],
        "gamepads": ["Game controllers connected", "Подключено геймпадов", "Controles conectados", "Comandos ligados"],
    ]
}

/// Shorthand for views: `T("games")`, redrawn when the language changes because the root observes `Lang.shared`.
func T(_ key: String) -> String { Lang.shared.t(key) }
