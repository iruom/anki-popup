import AppKit

func tr(_ japanese: String, _ english: String) -> String {
    Locale.preferredLanguages.first?.hasPrefix("ja") == true ? japanese : english
}

func plain(_ raw: String) -> String {
    // Strip active content and media before asking AppKit to decode text entities.
    let clean = raw.replacingOccurrences(of: #"(?is)<(script|style)\b[^>]*>.*?</\1>"#, with: "", options: .regularExpression)
        .replacingOccurrences(of: #"\[sound:[^\]]*\]"#, with: "", options: .regularExpression)
        .replacingOccurrences(of: #"(?i)<br\s*/?>|</(?:div|p|li|h[1-6])>"#, with: "\n", options: .regularExpression)
        .replacingOccurrences(of: #"<[^>]*>"#, with: "", options: .regularExpression)
        .replacingOccurrences(of: "\n", with: "<br>")
    guard let data = clean.data(using: .utf8),
          let result = try? NSAttributedString(data: data, options: [.documentType: NSAttributedString.DocumentType.html, .characterEncoding: String.Encoding.utf8.rawValue], documentAttributes: nil) else { return clean }
    return result.string.trimmingCharacters(in: .whitespacesAndNewlines)
}

class PopupPanel: NSPanel {
    override var canBecomeKey: Bool { false }
    override var canBecomeMain: Bool { false }
}

class ClickTextView: NSTextView {
    var play: (() -> Void)?
    override func mouseDown(with event: NSEvent) { play?() }
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
}

class CardView: NSVisualEffectView {
    var play: (() -> Void)?
    override func mouseDown(with event: NSEvent) { play?() }
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
}

class DragHeader: NSView {
    var moved: (() -> Void)?
    override func mouseDown(with event: NSEvent) {
        window?.performDrag(with: event)
        moved?()
    }
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
    override func resetCursorRects() { addCursorRect(bounds, cursor: .openHand) }
}

class HeaderLabel: NSTextField {
    override func hitTest(_ point: NSPoint) -> NSView? { nil }
}

class Controller: NSObject, NSApplicationDelegate, NSSoundDelegate {
    var status: NSStatusItem!
    var panel: PopupPanel!
    var label: ClickTextView!
    var scroll: NSScrollView!
    var header: DragHeader!
    var heading: NSTextField!
    var audioButton: NSButton!
    var progress: NSProgressIndicator!
    var cards: [StudyCard] = []
    var remaining: [Int] = []
    var lastIndex: Int?
    var timer: Timer?
    var cycle = PopupCycle(interval: 30, now: ProcessInfo.processInfo.systemUptime)
    var preferences = Preferences()
    var settingsWindow: SettingsWindow?
    var summaryItem: NSMenuItem!
    var paused = false
    var corner = 0
    var pauseItem: NSMenuItem!
    var customTopLeft: NSPoint?
    var activeSound: NSSound?
    var audioQueue: [URL] = []

    func applicationDidFinishLaunching(_ notification: Notification) {
        if !CommandLine.arguments.contains("--demo"), let data = UserDefaults.standard.data(forKey: "preferences"), let saved = try? JSONDecoder().decode(Preferences.self, from: data) { preferences = saved }
        if !preferences.interval.isFinite || !(5...3600).contains(preferences.interval) { preferences.interval = 30 }
        if !(0...3).contains(preferences.corner) { preferences.corner = 0 }
        status = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        status.button?.title = "Anki"
        status.button?.toolTip = "Anki Popup"
        let menu = NSMenu()
        summaryItem = menu.addItem(withTitle: "Anki Popup", action: nil, keyEquivalent: "")
        menu.addItem(.separator())
        pauseItem = menu.addItem(withTitle: tr("一時停止（非表示）", "Pause and hide"), action: #selector(togglePause), keyEquivalent: "")
        menu.addItem(withTitle: tr("次のカード", "Next card"), action: #selector(next), keyEquivalent: "")
        menu.addItem(withTitle: tr("このカードを隠す", "Hide this card"), action: #selector(dismissCard), keyEquivalent: "")
        menu.addItem(withTitle: tr("設定…", "Settings…"), action: #selector(showSettings), keyEquivalent: "")
        let position = NSMenuItem(title: tr("表示位置", "Position"), action: nil, keyEquivalent: "")
        let positions = NSMenu()
        for (i, title) in [tr("右上", "Top right"), tr("右下", "Bottom right"), tr("左上", "Top left"), tr("左下", "Bottom left")].enumerated() {
            let item = positions.addItem(withTitle: title, action: #selector(move(_:)), keyEquivalent: "")
            item.tag = i
            item.target = self
        }
        position.submenu = positions
        menu.addItem(position)
        menu.addItem(.separator())
        menu.addItem(withTitle: tr("終了", "Quit"), action: #selector(quit), keyEquivalent: "")
        for item in menu.items where item.action != nil { item.target = self }
        status.menu = menu
        panel = PopupPanel(contentRect: NSRect(x: 0, y: 0, width: 390, height: 300), styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        panel.level = .floating
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = true
        panel.ignoresMouseEvents = false
        panel.hidesOnDeactivate = false
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary]
        let view = CardView(frame: panel.contentView!.bounds)
        view.play = { [weak self] in self?.playAudio() }
        view.material = .hudWindow
        view.blendingMode = .behindWindow
        view.state = .active
        view.appearance = NSAppearance(named: .darkAqua)
        view.wantsLayer = true
        view.layer?.cornerRadius = 18
        view.layer?.masksToBounds = true
        view.autoresizingMask = [.width, .height]
        panel.contentView = view
        header = DragHeader(frame: NSRect(x: 0, y: 256, width: 390, height: 44))
        header.autoresizingMask = [.minYMargin, .width]
        header.toolTip = tr("ここをドラッグして移動", "Drag here to move")
        header.moved = { [weak self] in self?.rememberPosition() }
        view.addSubview(header)
        heading = HeaderLabel(labelWithString: "Anki Popup")
        heading.font = .systemFont(ofSize: 11)
        heading.textColor = .white.withAlphaComponent(0.82)
        heading.frame = NSRect(x: 22, y: 13, width: 250, height: 18)
        // The header handles dragging, including when the pointer is over its label.
        heading.isEnabled = false
        header.addSubview(heading)
        audioButton = NSButton(title: "▶︎", target: self, action: #selector(playAudio))
        audioButton.frame = NSRect(x: 304, y: 8, width: 32, height: 28)
        audioButton.isBordered = false
        audioButton.contentTintColor = .white
        audioButton.toolTip = tr("音声を再生（カードをクリックしても再生）", "Play audio (or click the card)")
        audioButton.setAccessibilityLabel(tr("音声を再生", "Play audio"))
        header.addSubview(audioButton)
        let close = NSButton(title: "×", target: self, action: #selector(dismissCard))
        close.frame = NSRect(x: 344, y: 8, width: 30, height: 28)
        close.isBordered = false
        close.font = .systemFont(ofSize: 22)
        close.contentTintColor = .white
        close.toolTip = tr("次の切り替えまで隠す", "Hide until the next scheduled card")
        close.setAccessibilityLabel(tr("次のカードまで隠す", "Hide until next card"))
        header.addSubview(close)
        label = ClickTextView(frame: NSRect(x: 22, y: 30, width: 346, height: 10000))
        label.play = { [weak self] in self?.playAudio() }
        label.toolTip = tr("クリックで音声を再生", "Click to play audio")
        label.isEditable = false
        label.isSelectable = false
        label.drawsBackground = false
        label.textContainerInset = .zero
        label.textContainer?.lineFragmentPadding = 0
        label.textContainer?.widthTracksTextView = false
        label.textContainer?.containerSize = NSSize(width: 346, height: 10000)
        scroll = NSScrollView()
        scroll.drawsBackground = false
        scroll.borderType = .noBorder
        scroll.hasVerticalScroller = true
        scroll.autohidesScrollers = true
        scroll.scrollerStyle = .overlay
        scroll.documentView = label
        view.addSubview(scroll)
        progress = NSProgressIndicator(frame: NSRect(x: 22, y: 12, width: 346, height: 4))
        progress.style = .bar
        progress.isIndeterminate = false
        progress.maxValue = 30
        view.addSubview(progress)
        if CommandLine.arguments.contains("--demo") {
            cards = [StudyCard(id: "demo", front: "serendipity", back: "偶然の幸運・思いがけない発見", extras: [("Example", "A chance meeting led to a new idea."), ("Translation", "偶然の出会いから、新しいアイデアが生まれた。")], audio: [])]
            next()
        } else if !preferences.collectionPath.isEmpty {
            do { try loadCards() } catch { report(error.localizedDescription); showSettings() }
        } else { showSettings() }
        timer = Timer(timeInterval: 0.2, target: self, selector: #selector(tick), userInfo: nil, repeats: true)
        RunLoop.main.add(timer!, forMode: .common)
    }

    func report(_ message: String) {
        let alert = NSAlert()
        alert.messageText = "Anki Popup"
        alert.informativeText = message
        alert.runModal()
    }

    @objc func next() {
        guard !cards.isEmpty else { return }
        stopAudio()
        cycle.advance(now: ProcessInfo.processInfo.systemUptime)
        if remaining.isEmpty {
            remaining = Array(cards.indices).shuffled()
            if remaining.count > 1, remaining.last == lastIndex { remaining.swapAt(0, remaining.count - 1) }
        }
        let index = remaining.removeLast()
        lastIndex = index
        let card = cards[index]
        heading.stringValue = "Anki Popup  ·  \(cards.count - remaining.count)/\(cards.count)  ·  \(Int(preferences.interval))s"
        audioButton.isEnabled = !card.audio.isEmpty
        let front = plain(card.front), back = plain(card.back)

        let screen = panel.screen ?? NSScreen.main ?? NSScreen.screens[0]
        let maxHeight = screen.visibleFrame.height * 0.72
        var rendered = NSAttributedString()
        var textHeight: CGFloat = 0
        var scale: CGFloat = 1
        repeat {
            let text = NSMutableAttributedString()
            func append(_ string: String, size: CGFloat, color: NSColor, bold: Bool = false) {
                let paragraph = NSMutableParagraphStyle()
                paragraph.lineSpacing = 4 * scale
                paragraph.paragraphSpacing = 5 * scale
                text.append(NSAttributedString(string: string, attributes: [.font: NSFont.systemFont(ofSize: size * scale, weight: bold ? .semibold : .regular), .foregroundColor: color, .paragraphStyle: paragraph]))
            }
            append(front + "\n\n", size: 28, color: .white, bold: true)
            append(back, size: 20, color: NSColor(calibratedRed: 0.57, green: 0.87, blue: 0.76, alpha: 1), bold: true)
            for (_, raw) in card.extras {
                let extra = plain(raw)
                if !extra.isEmpty { append("\n\n" + extra, size: 14, color: .white.withAlphaComponent(0.85)) }
            }
            rendered = text
            label.textStorage?.setAttributedString(text)
            label.layoutManager?.ensureLayout(for: label.textContainer!)
            textHeight = ceil(label.layoutManager!.usedRect(for: label.textContainer!).height)
            scale -= 0.05
        } while textHeight + 84 > maxHeight && scale > 0.75
        let height = min(textHeight + 84, maxHeight)
        panel.setContentSize(NSSize(width: 390, height: height))
        scroll.frame = NSRect(x: 22, y: 30, width: 346, height: height - 80)
        label.frame = NSRect(x: 0, y: 0, width: 346, height: max(textHeight + 4, height - 80))
        label.textStorage?.setAttributedString(rendered)
        scroll.contentView.scroll(to: .zero)
        position()
        progress.maxValue = preferences.interval
        progress.doubleValue = preferences.interval
        if !paused { panel.orderFrontRegardless() }
    }

    func position() {
        if let point = customTopLeft {
            let desired = NSRect(x: point.x, y: point.y - panel.frame.height, width: panel.frame.width, height: panel.frame.height)
            let screen = NSScreen.screens.first(where: { $0.visibleFrame.intersects(desired) }) ?? NSScreen.main ?? NSScreen.screens[0]
            let frame = screen.visibleFrame
            let x = min(max(desired.minX, frame.minX), frame.maxX - desired.width)
            let y = min(max(desired.minY, frame.minY), frame.maxY - desired.height)
            panel.setFrameOrigin(NSPoint(x: x, y: y))
            return
        }
        let frame = (NSScreen.main ?? NSScreen.screens[0]).visibleFrame
        let x = corner < 2 ? frame.maxX - panel.frame.width - 20 : frame.minX + 20
        let y = corner % 2 == 0 ? frame.maxY - panel.frame.height - 20 : frame.minY + 20
        panel.setFrameOrigin(NSPoint(x: x, y: y))
    }
    @objc func tick() {
        guard !paused, !cards.isEmpty else { return }
        let now = ProcessInfo.processInfo.systemUptime
        if cycle.isDue(now: now) { next() } else { progress.doubleValue = cycle.remaining(now: now) }
    }
    @objc func togglePause() {
        paused.toggle()
        pauseItem.title = paused ? tr("再開", "Resume") : tr("一時停止（非表示）", "Pause and hide")
        if paused { stopAudio(); panel.orderOut(nil) }
        else if !cards.isEmpty { cycle.advance(now: ProcessInfo.processInfo.systemUptime); panel.orderFrontRegardless() }
    }
    func savePreferences() {
        if let data = try? JSONEncoder().encode(preferences) { UserDefaults.standard.set(data, forKey: "preferences") }
    }
    func rememberPosition() {
        customTopLeft = NSPoint(x: panel.frame.minX, y: panel.frame.maxY)
        preferences.x = customTopLeft!.x
        preferences.top = customTopLeft!.y
        savePreferences()
    }
    @objc func move(_ sender: NSMenuItem) {
        corner = sender.tag
        preferences.corner = corner
        customTopLeft = nil
        preferences.x = nil
        preferences.top = nil
        savePreferences()
        position()
    }
    @objc func dismissCard() {
        cycle.dismiss()
        stopAudio()
        panel.orderOut(nil)
        // Keep the current deadline: dismissing never restarts the interval.
    }
    @objc func showSettings() {
        if settingsWindow == nil { settingsWindow = SettingsWindow(owner: self) }
        settingsWindow?.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }
    func loadCards() throws {
        let snapshot = try readCollection(URL(fileURLWithPath: preferences.collectionPath))
        let loaded = makeCards(snapshot, preferences: preferences)
        guard !loaded.isEmpty else { throw CollectionError(message: tr("表示できるカードがありません。デッキと表・裏のフィールドを確認してください。", "No matching notes. Check your deck and front/back fields.")) }
        cards = loaded
        paused = false
        pauseItem.title = tr("一時停止（非表示）", "Pause and hide")
        remaining = []
        lastIndex = nil
        corner = preferences.corner
        if let x = preferences.x, let top = preferences.top { customTopLeft = NSPoint(x: x, y: top) } else { customTopLeft = nil }
        cycle = PopupCycle(interval: preferences.interval, now: ProcessInfo.processInfo.systemUptime)
        panel.ignoresMouseEvents = preferences.clickThrough
        summaryItem.title = "\(cards.count) " + tr("枚", "notes") + " · \(Int(preferences.interval))s"
        next()
    }
    func stopAudio() {
        activeSound?.delegate = nil
        activeSound?.stop()
        activeSound = nil
        audioQueue.removeAll()
        audioButton?.title = "▶︎"
    }
    @objc func playAudio() {
        guard !paused, !cycle.dismissed, let index = lastIndex else { return }
        stopAudio()
        audioQueue = cards[index].audio
        playNextSound()
    }
    func playNextSound() {
        while !audioQueue.isEmpty {
            let url = audioQueue.removeFirst()
            if let sound = NSSound(contentsOf: url, byReference: true) {
                activeSound = sound
                sound.delegate = self
                if sound.play() { audioButton.title = "♪"; return }
            }
        }
        activeSound = nil
        audioButton.title = "▶︎"
    }
    func sound(_ sound: NSSound, didFinishPlaying finishedPlaying: Bool) {
        guard sound === activeSound else { return }
        playNextSound()
    }
    @objc func quit() { NSApp.terminate(nil) }
}
