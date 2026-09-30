import AppKit
import UniformTypeIdentifiers

final class SettingsWindow: NSWindow {
    weak var owner: Controller?
    var collectionURLs: [URL] = []
    var snapshot: CollectionSnapshot?
    var deckIDs: [String] = []
    var fieldNames: [String] = []
    let profile = NSPopUpButton()
    let deck = NSPopUpButton()
    let front = NSPopUpButton()
    let back = NSPopUpButton()
    let interval = NSTextField(string: "30")
    let extras = NSButton(checkboxWithTitle: tr("例文・メモも表示", "Show example/extra fields"), target: nil, action: nil)
    let clickThrough = NSButton(checkboxWithTitle: tr("マウス操作を通す（カード上の操作は無効）", "Pass clicks through (disables card controls)"), target: nil, action: nil)
    let info = NSTextField(wrappingLabelWithString: "")

    init(owner: Controller) {
        self.owner = owner
        super.init(contentRect: NSRect(x: 0, y: 0, width: 560, height: 450), styleMask: [.titled, .closable], backing: .buffered, defer: false)
        self.title = tr("Anki Popup 設定", "Anki Popup Settings")
        isReleasedWhenClosed = false
        center()
        let view = contentView!
        func title(_ text: String, y: CGFloat) {
            let label = NSTextField(labelWithString: text)
            label.frame = NSRect(x: 24, y: y + 4, width: 122, height: 20)
            view.addSubview(label)
        }
        title(tr("プロファイル", "Profile"), y: 388)
        profile.frame = NSRect(x: 148, y: 388, width: 292, height: 28)
        profile.target = self; profile.action = #selector(profileChanged)
        view.addSubview(profile)
        let browse = NSButton(title: tr("参照…", "Browse…"), target: self, action: #selector(browseFile))
        browse.frame = NSRect(x: 444, y: 388, width: 92, height: 28)
        view.addSubview(browse)
        title(tr("デッキ", "Deck"), y: 343)
        deck.frame = NSRect(x: 148, y: 343, width: 388, height: 28)
        deck.target = self; deck.action = #selector(deckChanged)
        view.addSubview(deck)
        title(tr("表のフィールド", "Front field"), y: 298)
        front.frame = NSRect(x: 148, y: 298, width: 388, height: 28)
        view.addSubview(front)
        title(tr("裏のフィールド", "Back field"), y: 253)
        back.frame = NSRect(x: 148, y: 253, width: 388, height: 28)
        view.addSubview(back)
        title(tr("表示間隔（秒）", "Interval (seconds)"), y: 208)
        interval.frame = NSRect(x: 148, y: 208, width: 90, height: 26)
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal; formatter.allowsFloats = false
        formatter.minimum = 5; formatter.maximum = 3600
        interval.formatter = formatter
        interval.stringValue = String(Int(owner.preferences.interval))
        view.addSubview(interval)
        extras.frame = NSRect(x: 148, y: 166, width: 388, height: 24)
        extras.state = owner.preferences.showExtras ? .on : .off
        view.addSubview(extras)
        clickThrough.frame = NSRect(x: 148, y: 134, width: 388, height: 24)
        clickThrough.state = owner.preferences.clickThrough ? .on : .off
        view.addSubview(clickThrough)
        info.frame = NSRect(x: 24, y: 63, width: 512, height: 57)
        info.font = .systemFont(ofSize: 12)
        info.textColor = .secondaryLabelColor
        view.addSubview(info)
        let cancel = NSButton(title: tr("キャンセル", "Cancel"), target: self, action: #selector(cancelSettings))
        cancel.frame = NSRect(x: 312, y: 20, width: 105, height: 30)
        view.addSubview(cancel)
        let save = NSButton(title: tr("保存して開始", "Save & start"), target: self, action: #selector(applySettings))
        save.frame = NSRect(x: 423, y: 20, width: 113, height: 30)
        save.keyEquivalent = "\r"
        view.addSubview(save)
        collectionURLs = discoverCollections()
        if !owner.preferences.collectionPath.isEmpty {
            let saved = URL(fileURLWithPath: owner.preferences.collectionPath)
            if !collectionURLs.contains(saved) { collectionURLs.append(saved) }
        }
        refreshProfiles()
        if let selected = collectionURLs.firstIndex(where: { $0.path == owner.preferences.collectionPath }) { profile.selectItem(at: selected) }
        loadProfile(preferredDeck: owner.preferences.deckID)
        selectField(front, name: owner.preferences.frontField)
        selectField(back, name: owner.preferences.backField)
    }

    func refreshProfiles() {
        profile.removeAllItems()
        for url in collectionURLs { profile.addItem(withTitle: url.deletingLastPathComponent().lastPathComponent) }
    }
    @objc func profileChanged() { loadProfile(preferredDeck: "") }
    func loadProfile(preferredDeck: String) {
        snapshot = nil
        deck.removeAllItems(); front.removeAllItems(); back.removeAllItems()
        guard collectionURLs.indices.contains(profile.indexOfSelectedItem) else {
            info.stringValue = tr("Ankiが見つかりません。Ankiでカードを作成するか、「参照」でcollection.anki2を選んでください。", "No Anki profiles found. Add cards in Anki, or browse to a collection.anki2 file.")
            return
        }
        do {
            let loaded = try readCollection(collectionURLs[profile.indexOfSelectedItem])
            snapshot = loaded
            deck.addItem(withTitle: tr("すべてのデッキ", "All decks"))
            deckIDs = [""]
            for item in loaded.decks { deck.addItem(withTitle: item.name); deckIDs.append(item.id) }
            if let i = deckIDs.firstIndex(of: preferredDeck) { deck.selectItem(at: i) }
            deckChanged()
        } catch { info.stringValue = tr("読み込めません: ", "Cannot read: ") + error.localizedDescription }
    }
    @objc func deckChanged() {
        guard let snapshot, deckIDs.indices.contains(deck.indexOfSelectedItem) else { return }
        let models = Set(snapshot.notes(in: deckIDs[deck.indexOfSelectedItem]).map(\.modelID))
        fieldNames = Array(Set(models.flatMap { snapshot.fields[$0] ?? [] })).sorted()
        for popup in [front, back] {
            let oldName = popup.indexOfSelectedItem > 0 ? popup.titleOfSelectedItem ?? "" : ""
            popup.removeAllItems()
            popup.addItem(withTitle: tr("自動（フィールド名・順序から判定）", "Automatic (field names/order)"))
            popup.addItems(withTitles: fieldNames)
            selectField(popup, name: oldName)
        }
        info.stringValue = tr("子デッキも対象です。表・裏のフィールドを直接表示します（カードテンプレートや穴埋め形式の再現は未対応）。", "Includes subdecks. Displays note fields directly; custom templates and cloze rendering are not supported.")
    }
    func selectField(_ popup: NSPopUpButton, name: String) {
        popup.selectItem(at: fieldNames.firstIndex(of: name).map { $0 + 1 } ?? 0)
    }
    @objc func browseFile() {
        let picker = NSOpenPanel()
        picker.title = tr("collection.anki2を選択", "Choose collection.anki2")
        picker.canChooseDirectories = false
        picker.allowsMultipleSelection = false
        if picker.runModal() == .OK, let url = picker.url {
            if !collectionURLs.contains(url) { collectionURLs.append(url) }
            refreshProfiles()
            profile.selectItem(at: collectionURLs.firstIndex(of: url)!)
            loadProfile(preferredDeck: "")
        }
    }
    @objc func cancelSettings() { orderOut(nil) }
    @objc func applySettings() {
        guard let owner, snapshot != nil, collectionURLs.indices.contains(profile.indexOfSelectedItem), deckIDs.indices.contains(deck.indexOfSelectedItem) else { return }
        guard let seconds = Double(interval.stringValue), seconds >= 5, seconds <= 3600, seconds.rounded() == seconds else {
            owner.report(tr("表示間隔は5〜3600秒の整数で指定してください。", "Choose a whole-number interval from 5 to 3600 seconds.")); return
        }
        let previous = owner.preferences
        owner.preferences.collectionPath = collectionURLs[profile.indexOfSelectedItem].path
        owner.preferences.deckID = deckIDs[deck.indexOfSelectedItem]
        owner.preferences.frontField = front.indexOfSelectedItem > 0 ? front.titleOfSelectedItem ?? "" : ""
        owner.preferences.backField = back.indexOfSelectedItem > 0 ? back.titleOfSelectedItem ?? "" : ""
        owner.preferences.interval = seconds
        owner.preferences.showExtras = extras.state == .on
        owner.preferences.clickThrough = clickThrough.state == .on
        do {
            try owner.loadCards()
            owner.savePreferences()
            orderOut(nil)
        } catch {
            owner.preferences = previous
            owner.report(error.localizedDescription)
        }
    }
}
