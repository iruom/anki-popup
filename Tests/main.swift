import Foundation
import SQLite3

var checks = 0
func expect(_ value: @autoclosure () -> Bool, _ message: String) {
    guard value() else { fputs("FAIL: \(message)\n", stderr); exit(1) }
    checks += 1
}
let root = FileManager.default.temporaryDirectory.appendingPathComponent("AnkiPopupTests-" + UUID().uuidString)
try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
defer { try? FileManager.default.removeItem(at: root) }
let media = root.appendingPathComponent("collection.media")
try FileManager.default.createDirectory(at: media, withIntermediateDirectories: true)
try Data("synthetic audio placeholder".utf8).write(to: media.appendingPathComponent("sample.mp3"))
try Data("outside".utf8).write(to: root.appendingPathComponent("outside.mp3"))
try FileManager.default.createSymbolicLink(at: media.appendingPathComponent("escape.mp3"), withDestinationURL: root.appendingPathComponent("outside.mp3"))

func fixture(_ name: String, modern: Bool) throws -> URL {
    let url = root.appendingPathComponent(name)
    var db: OpaquePointer?
    expect(sqlite3_open(url.path, &db) == SQLITE_OK, "Create fixture")
    sqlite3_create_collation(db, "unicase", SQLITE_UTF8, nil) { _, la, a, lb, b in
        let left = String(decoding: UnsafeRawBufferPointer(start: a, count: Int(la)), as: UTF8.self).lowercased()
        let right = String(decoding: UnsafeRawBufferPointer(start: b, count: Int(lb)), as: UTF8.self).lowercased()
        return Int32(left.compare(right, options: .literal).rawValue)
    }
    defer { sqlite3_close(db) }
    func exec(_ sql: String) {
        expect(sqlite3_exec(db, sql, nil, nil, nil) == SQLITE_OK, "Fixture SQL")
    }
    exec("CREATE TABLE notes(id INTEGER,mid INTEGER,flds TEXT); CREATE TABLE cards(nid INTEGER,did INTEGER,odid INTEGER);")
    if modern {
        exec("CREATE TABLE decks(id INTEGER PRIMARY KEY,name TEXT COLLATE unicase); CREATE TABLE fields(ntid INTEGER NOT NULL,ord INTEGER NOT NULL,name TEXT COLLATE unicase,PRIMARY KEY(ntid,ord)) WITHOUT ROWID;")
        exec("INSERT INTO decks VALUES(1,'Deck_%'),(2,'Deck_%' || char(31) || 'Child'),(3,'Deck_XX'),(4,'Filtered');")
        exec("INSERT INTO fields VALUES(10,0,'Expression'),(10,1,'Meaning'),(10,2,'Example'),(20,0,'Front'),(20,1,'Back');")
    } else {
        exec("CREATE TABLE col(decks TEXT,models TEXT);")
        exec(#"INSERT INTO col VALUES('{"1":{"name":"Deck_%"},"2":{"name":"Deck_%::Child"},"3":{"name":"Deck_XX"},"4":{"name":"Filtered"}}','{"10":{"flds":[{"ord":0,"name":"Expression"},{"ord":1,"name":"Meaning"},{"ord":2,"name":"Example"}]},"20":{"flds":[{"ord":0,"name":"Front"},{"ord":1,"name":"Back"}]}}');"#)
    }
    exec("INSERT INTO notes VALUES(100,10,'hello [sound:sample.mp3]' || char(31) || 'greeting' || char(31) || 'Hello there.'),(101,10,'child' || char(31) || 'child answer'),(102,20,'other' || char(31) || 'other answer');")
    exec("INSERT INTO cards VALUES(100,1,0),(100,1,0),(101,4,2),(102,3,0);")
    return url
}
for modern in [true, false] {
    let url = try fixture(modern ? "modern.anki2" : "legacy.anki2", modern: modern)
    let before = try Data(contentsOf: url)
    let collection = try readCollection(url)
    var preferences = Preferences()
    preferences.deckID = "1"
    let cards = makeCards(collection, preferences: preferences)
    expect(cards.count == 2, "Includes children, excludes similar deck names, deduplicates reverse cards")
    expect(cards.contains { $0.id == "101" }, "Filtered cards use original deck")
    expect(cards.first?.front.contains("hello") == true, "Auto front mapping")
    expect(cards.first?.back == "greeting", "Auto back mapping")
    expect(cards.first?.extras.first?.1 == "Hello there.", "Extra fields")
    expect(cards.first?.audio.count == 1, "Local sound tag")
    preferences.showExtras = false
    preferences.frontField = "Meaning"
    preferences.backField = "Expression"
    expect(makeCards(collection, preferences: preferences).first?.front == "greeting", "Explicit field mapping")
    expect(makeCards(collection, preferences: preferences).first?.extras.isEmpty == true, "Hide extras")
    preferences.frontField = "Missing field"
    expect(makeCards(collection, preferences: preferences).isEmpty, "Missing fields do not silently fall back")
    expect(collection.notes(in: "missing").isEmpty, "Missing deck returns no notes")
    let after = try Data(contentsOf: url)
    expect(after == before, "Collection file remains unchanged")
}
expect(soundFiles("[sound:../outside.mp3] [sound:escape.mp3] [sound:missing.mp3]", media: media).isEmpty, "Reject traversal, symlink escape, missing audio")
var cycle = PopupCycle(interval: 30, now: 100)
cycle.dismiss()
expect(cycle.dismissed, "Dismissed flag")
expect(cycle.deadline == 130, "Dismiss does not restart interval")
expect(cycle.remaining(now: 120) == 10, "Existing deadline remains")
expect(!cycle.isDue(now: 129.9), "Stays hidden before boundary")
expect(cycle.isDue(now: 130), "Next card due at original boundary")
cycle.advance(now: 130)
expect(!cycle.dismissed && cycle.deadline == 160, "Next card becomes visible on its own interval")
expect(cycle.remaining(now: 200) == 0, "Sleep/wake cannot produce negative progress")
print("Passed \(checks) checks (synthetic fixtures only).")
