import Foundation
import SQLite3

struct Note {
    let id: String
    let deckID: String
    let modelID: String
    let values: [String]
}
struct Deck { let id: String; let name: String }
struct CollectionSnapshot {
    let decks: [Deck]
    let fields: [String: [String]]
    let notes: [Note]
    let media: URL

    func notes(in deckID: String) -> [Note] {
        guard !deckID.isEmpty else { return notes }
        guard let deck = decks.first(where: { $0.id == deckID }) else { return [] }
        let included = Set(decks.filter { $0.id == deckID || $0.name.hasPrefix(deck.name + "::") }.map(\.id))
        return notes.filter { included.contains($0.deckID) }
    }
}
struct StudyCard {
    let id: String
    let front: String
    let back: String
    let extras: [(String, String)]
    let audio: [URL]
}
struct Preferences: Codable {
    var collectionPath = ""
    var deckID = ""
    var frontField = ""
    var backField = ""
    var interval: Double = 30
    var showExtras = true
    var clickThrough = false
    var corner = 0
    var x: Double?
    var top: Double?
}

struct PopupCycle {
    let interval: Double
    private(set) var deadline: Double
    private(set) var dismissed = false
    init(interval: Double, now: Double) {
        self.interval = interval
        self.deadline = now + interval
    }
    mutating func dismiss() { dismissed = true }
    mutating func advance(now: Double) { dismissed = false; deadline = now + interval }
    func isDue(now: Double) -> Bool { now >= deadline }
    func remaining(now: Double) -> Double { max(0, deadline - now) }
}

struct CollectionError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

final class Database {
    private var handle: OpaquePointer?
    init(url: URL) throws {
        let result = sqlite3_open_v2(url.path, &handle, SQLITE_OPEN_READONLY, nil)
        guard result == SQLITE_OK else {
            let reason = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "Cannot open file"
            if handle != nil { sqlite3_close(handle); handle = nil }
            throw CollectionError(message: reason)
        }
        sqlite3_busy_timeout(handle, 3000)
        // Anki declares this collation in its schema. System SQLite needs it even
        // for some read-only scans. We do not use it for indexed text searches.
        sqlite3_create_collation(handle, "unicase", SQLITE_UTF8, nil) { _, leftLength, left, rightLength, right in
            let a = String(decoding: UnsafeRawBufferPointer(start: left, count: Int(leftLength)), as: UTF8.self).lowercased()
            let b = String(decoding: UnsafeRawBufferPointer(start: right, count: Int(rightLength)), as: UTF8.self).lowercased()
            return Int32(a.compare(b, options: .literal).rawValue)
        }
    }
    deinit { sqlite3_close(handle) }
    func query(_ sql: String) throws -> [[String]] {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &statement, nil) == SQLITE_OK else {
            throw CollectionError(message: String(cString: sqlite3_errmsg(handle)))
        }
        defer { sqlite3_finalize(statement) }
        var rows: [[String]] = []
        var status = sqlite3_step(statement)
        while status == SQLITE_ROW {
            rows.append((0..<sqlite3_column_count(statement)).map {
                sqlite3_column_text(statement, $0).map { String(cString: $0) } ?? ""
            })
            status = sqlite3_step(statement)
        }
        guard status == SQLITE_DONE else { throw CollectionError(message: String(cString: sqlite3_errmsg(handle))) }
        return rows
    }
}

func readCollection(_ url: URL) throws -> CollectionSnapshot {
    let db = try Database(url: url)
    _ = try db.query("BEGIN")
    defer { _ = try? db.query("ROLLBACK") }
    let tables = Set(try db.query("SELECT name FROM sqlite_master WHERE type='table'").map { $0[0] })
    var decks: [Deck] = []
    var fields: [String: [String]] = [:]
    if tables.contains("decks") && tables.contains("fields") {
        decks = try db.query("SELECT id,name FROM decks").map { Deck(id: $0[0], name: $0[1].replacingOccurrences(of: "\u{1f}", with: "::")) }
        for row in try db.query("SELECT ntid,ord,name FROM fields ORDER BY ntid,ord") {
            fields[row[0], default: []].append(row[2])
        }
    } else {
        guard let row = try db.query("SELECT decks,models FROM col LIMIT 1").first else { throw CollectionError(message: "Unsupported Anki collection") }
        let deckObjects = try JSONSerialization.jsonObject(with: Data(row[0].utf8)) as? [String: [String: Any]] ?? [:]
        decks = deckObjects.compactMap { id, value in (value["name"] as? String).map { Deck(id: id, name: $0) } }
        let models = try JSONSerialization.jsonObject(with: Data(row[1].utf8)) as? [String: [String: Any]] ?? [:]
        for (id, model) in models {
            let raw = model["flds"] as? [[String: Any]] ?? []
            fields[id] = raw.sorted { ($0["ord"] as? Int ?? 0) < ($1["ord"] as? Int ?? 0) }.compactMap { $0["name"] as? String }
        }
    }
    // Deduplicate reverse cards; filtered decks keep the card's original deck.
    let rows = try db.query("SELECT DISTINCT n.id,n.mid,n.flds,CASE WHEN c.odid != 0 THEN c.odid ELSE c.did END FROM notes n JOIN cards c ON c.nid=n.id ORDER BY n.id")
    let notes = rows.map { Note(id: $0[0], deckID: $0[3], modelID: $0[1], values: $0[2].components(separatedBy: "\u{1f}")) }
    return CollectionSnapshot(decks: decks.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }, fields: fields, notes: notes, media: url.deletingLastPathComponent().appendingPathComponent("collection.media"))
}

func discoverCollections() -> [URL] {
    let root = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support/Anki2")
    let profiles = (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? []
    return profiles.map { $0.appendingPathComponent("collection.anki2") }
        .filter { FileManager.default.fileExists(atPath: $0.path) }
        .sorted { $0.path < $1.path }
}

func soundFiles(_ raw: String, media: URL) -> [URL] {
    let regex = try! NSRegularExpression(pattern: #"\[sound:([^\]]+)\]"#)
    let text = raw as NSString
    let root = media.resolvingSymlinksInPath().standardizedFileURL
    return regex.matches(in: raw, range: NSRange(location: 0, length: text.length)).compactMap {
        let url = media.appendingPathComponent(text.substring(with: $0.range(at: 1))).resolvingSymlinksInPath().standardizedFileURL
        guard url.path.hasPrefix(root.path + "/"), FileManager.default.fileExists(atPath: url.path) else { return nil }
        return url
    }
}

func makeCards(_ snapshot: CollectionSnapshot, preferences: Preferences) -> [StudyCard] {
    var seen = Set<String>()
    let autoFront = ["front", "question", "expression", "word", "表面", "単語"]
    let autoBack = ["back", "answer", "meaning", "definition", "裏面", "意味"]
    return snapshot.notes(in: preferences.deckID).compactMap { note in
        guard !seen.contains(note.id), let names = snapshot.fields[note.modelID], !names.isEmpty else { return nil }
        func index(_ requested: String, aliases: [String], fallback: Int) -> Int? {
            if !requested.isEmpty { return names.firstIndex(of: requested) }
            return names.firstIndex(where: { aliases.contains($0.lowercased()) }) ?? (names.count > fallback ? fallback : nil)
        }
        guard let front = index(preferences.frontField, aliases: autoFront, fallback: 0), let back = index(preferences.backField, aliases: autoBack, fallback: 1), front < note.values.count, back < note.values.count else { return nil }
        let extraNames = Set(["例文英語", "例文日本語", "メモ", "example", "example sentence", "translation", "notes", "extra", "sentence"])
        let extras = preferences.showExtras ? names.enumerated().compactMap { i, name -> (String, String)? in
            guard i != front, i != back, i < note.values.count, extraNames.contains(name.lowercased()), !note.values[i].isEmpty else { return nil }
            return (name, note.values[i])
        } : []
        let audio = soundFiles(note.values[front] + note.values[back], media: snapshot.media)
        seen.insert(note.id)
        return StudyCard(id: note.id, front: note.values[front], back: note.values[back], extras: extras, audio: Array(NSOrderedSet(array: audio).array.compactMap { $0 as? URL }))
    }
}
