import Foundation
import AppKit
import CoreGraphics
import ImageIO
import SQLite3
import UniformTypeIdentifiers
import CryptoKit

func sha256(_ s: String) -> String { SHA256.hash(data: Data(s.utf8)).map { String(format: "%02x", $0) }.joined() }

struct Row: Equatable { var id: Int64; var edit: String; var rating: Int; var flag: Int; var label: String? }
struct Preset: Identifiable, Equatable { var id: Int64; var name: String; var edit: String }
struct Version: Identifiable, Equatable { var id: Int64; var name: String; var edit: String }
struct Album: Identifiable, Hashable { var id: Int64; var name: String }

private let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

/// A SQL file the Android app ships (src/main/assets): copied into the app bundle by build.sh, read from the repo when running
/// from source. Statements end at a ";" that ends a line (trigger bodies keep theirs), comment lines are dropped, as on Android.
func sqlFile(_ name: String) -> [String] {
    let text = Bundle.main.url(forResource: name, withExtension: "sql").flatMap { try? String(contentsOf: $0) }
        ?? (try? String(contentsOf: URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../../src/main/assets/\(name).sql")))
        ?? ""
    let lines = text.split(separator: "\n").map { $0.replacingOccurrences(of: "\\s+$", with: "", options: .regularExpression) }
    return (lines.filter { !$0.trimmingCharacters(in: .whitespaces).hasPrefix("--") }.joined(separator: "\n") + "\n")
        .components(separatedBy: ";\n").map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
}

/// App-private SQLite, same tables and edit JSON as the Android app. `path = nil` gives an in-memory DB (self-test).
final class Db {
    private var db: OpaquePointer?

    static let version = 2 // schema.sql is v1; migrate_<n>.sql takes it to n (the same files and number as Android)
    static let told = "*|" + String(repeating: "[0-9a-f]", count: 16) // a key from told()

    init(path: String? = Db.defaultPath) {
        // serialized: one handle shared by the main thread and background work
        guard sqlite3_open_v2(path ?? ":memory:", &db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX, nil) == SQLITE_OK else {
            fatalError("Lumen can't open its database at \(path ?? "memory")")
        }
        exec("pragma foreign_keys = on")
        var v = Int(query("pragma user_version") { $0.int(0) }.first ?? 0)
        let exists = (query("select count(*) from sqlite_master where name = 'photos'") { $0.int(0) }.first ?? 0) > 0
        if v == 0 && exists { v = 1 } // made before user_version was set
        guard v < Db.version else { return }
        // first run: the whole schema, then every migration; later: the migrations it hasn't had. All of it or nothing.
        let files = (v == 0 ? ["schema"] : []) + (max(v, 1) + 1...Db.version).map { "migrate_\($0)" }
        let steps = files.map(sqlFile)
        guard steps.allSatisfy({ !$0.isEmpty }) else { fatalError("\(files.joined(separator: ", ")).sql missing from the app; rebuild with build.sh") }
        guard tx({ steps.joined().allSatisfy { exec($0) } && exec("pragma user_version = \(Db.version)") }) else {
            fatalError("Lumen couldn't update its database")
        }
    }

    /// All-or-nothing: on failure nothing is written. Batch work also runs much faster inside one.
    @discardableResult
    func tx(_ body: () -> Bool) -> Bool {
        exec("begin immediate")
        let ok = body()
        exec(ok ? "commit" : "rollback")
        return ok
    }

    static var defaultPath: String {
        let d = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Lumen")
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d.appendingPathComponent("lumen.db").path
    }

    struct Cursor {
        let s: OpaquePointer
        func int(_ i: Int32) -> Int64 { sqlite3_column_int64(s, i) }
        func text(_ i: Int32) -> String? { sqlite3_column_text(s, i).map { String(cString: $0) } }
    }

    @discardableResult
    func exec(_ sql: String, _ args: [Any?] = []) -> Bool {
        var s: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &s, nil) == SQLITE_OK else { return false }
        defer { sqlite3_finalize(s) }
        bind(s!, args)
        let ok = sqlite3_step(s) == SQLITE_DONE
        if !ok { FileHandle.standardError.write(Data("Lumen db: \(String(cString: sqlite3_errmsg(db)))\n".utf8)) } // reason only, no user data
        return ok
    }

    func query<T>(_ sql: String, _ args: [Any?] = [], _ row: (Cursor) -> T) -> [T] {
        var s: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &s, nil) == SQLITE_OK else { return [] }
        defer { sqlite3_finalize(s) }
        bind(s!, args)
        var out = [T]()
        while sqlite3_step(s) == SQLITE_ROW { out.append(row(Cursor(s: s!))) }
        return out
    }

    private func bind(_ s: OpaquePointer, _ args: [Any?]) {
        for (i, a) in args.enumerated() {
            let n = Int32(i + 1)
            switch a {
            case nil: sqlite3_bind_null(s, n)
            case let v as Int: sqlite3_bind_int64(s, n, Int64(v))
            case let v as Int64: sqlite3_bind_int64(s, n, v)
            case let v as Bool: sqlite3_bind_int64(s, n, v ? 1 : 0)
            case let v as String: sqlite3_bind_text(s, n, v, -1, SQLITE_TRANSIENT)
            default: sqlite3_bind_text(s, n, "\(a!)", -1, SQLITE_TRANSIENT)
            }
        }
    }

    private var now: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    private func has(_ key: String) -> Bool { !query("select 1 from photos where key = ?", [key], { $0.int(0) }).isEmpty }

    /// Plain name|size keys whose files were told apart: they keep hashed keys even after one of the twins is gone.
    func split() -> Set<String> { Set(query("select substr(key, 1, length(key) - 17) from photos where key glob ?", [Db.told]) { $0.text(0)! }) }

    /// The photo's row, created on first open. `shared` is the plain name|size key when `key` tells apart files sharing it.
    func open(key: String, uri: String, w: Int, h: Int, shared: String? = nil) -> Row {
        // Same file under a new key (its size changed, e.g. another app rewrote the metadata): keep its edits.
        if !has(key) { exec("update photos set key = ? where uri = ? and not exists (select 1 from photos where key = ?)", [key, uri, key]) }
        // Two files with one name and size used to share a row. The first told apart takes it (versions, albums);
        // the others start from a copy of it, so each keeps what it showed.
        if !has(key), let shared, shared != key {
            if has(shared) { exec("update photos set key = ? where key = ?", [key, shared]) }
            else {
                exec("""
                    insert into photos (key, uri, added_at, updated_at, width, height, rating, flag, label, edit) select ?, ?, ?, ?, ?, ?, rating, flag, label, edit
                    from photos where substr(key, 1, length(?) + 1) = ? || '|' limit 1
                    """, [key, uri, now, now, max(1, w), max(1, h), shared, shared])
            }
        }
        exec("""
            insert into photos (key, uri, added_at, updated_at, width, height) values (?,?,?,?,?,?) on conflict (key) do update set uri = excluded.uri,
            width = case when excluded.width > 1 then excluded.width else width end, height = case when excluded.height > 1 then excluded.height else height end
            """, [key, uri, now, now, max(1, w), max(1, h)])
        return query("select id, edit, rating, flag, label from photos where key = ?", [key]) {
            Row(id: $0.int(0), edit: $0.text(1) ?? "{\"v\":1}", rating: Int($0.int(2)), flag: Int($0.int(3)), label: $0.text(4))
        }.first!
    }

    @discardableResult
    func saveEdit(_ id: Int64, _ edit: String) -> Bool { exec("update photos set edit = ?, updated_at = ? where id = ?", [edit, now, id]) }

    /// rating 0...5, flag -1/0/1, label colour or nil. The schema's check constraints reject anything else.
    @discardableResult
    func mark(_ id: Int64, _ column: String, _ value: Any?) -> Bool {
        precondition(["rating", "flag", "label"].contains(column))
        return exec("update photos set \(column) = ?, updated_at = ? where id = ?", [value, now, id])
    }

    /// key → row for every photo the database knows (marks and edits for the library grid).
    func rows() -> [String: (Row, Int64)] {
        Dictionary(query("select key, id, edit, rating, flag, label, updated_at from photos") {
            ($0.text(0)!, (Row(id: $0.int(1), edit: $0.text(2) ?? "{\"v\":1}", rating: Int($0.int(3)), flag: Int($0.int(4)), label: $0.text(5)), $0.int(6)))
        }, uniquingKeysWith: { a, _ in a })
    }

    func editOf(_ id: Int64) -> String { query("select edit from photos where id = ?", [id]) { $0.text(0) ?? "{\"v\":1}" }.first ?? "{\"v\":1}" }

    func presets() -> [Preset] { query("select id, name, edit from presets order by created_at, name, id") { Preset(id: $0.int(0), name: $0.text(1)!, edit: $0.text(2)!) } }
    func savePreset(_ name: String, _ edit: String) {
        exec("insert into presets (name, edit, created_at, updated_at) values (?,?,?,?) on conflict (name) do update set edit = excluded.edit, updated_at = excluded.updated_at",
             [name, edit, now, now])
    }
    func deletePreset(_ id: Int64) { exec("delete from presets where id = ?", [id]) }

    func versions(_ photo: Int64) -> [Version] {
        query("select id, name, edit from versions where photo_id = ? order by created_at, id", [photo]) { Version(id: $0.int(0), name: $0.text(1)!, edit: $0.text(2)!) }
    }
    func saveVersion(_ photo: Int64, _ name: String, _ edit: String) { exec("insert into versions (photo_id, name, edit, created_at) values (?,?,?,?)", [photo, name, edit, now]) }
    func deleteVersion(_ id: Int64) { exec("delete from versions where id = ?", [id]) }

    func albums() -> [Album] { query("select id, name from albums order by name") { Album(id: $0.int(0), name: $0.text(1)!) } }
    func createAlbum(_ name: String) -> Int64 {
        exec("insert or ignore into albums (name, created_at, updated_at) values (?,?,?)", [name, now, now])
        return query("select id from albums where name = ?", [name]) { $0.int(0) }.first!
    }
    func deleteAlbum(_ id: Int64) { exec("delete from albums where id = ?", [id]) }
    func setInAlbum(_ album: Int64, _ photo: Int64, _ inside: Bool) {
        if inside { exec("insert or ignore into album_photos (album_id, photo_id, added_at) values (?,?,?)", [album, photo, now]) }
        else { exec("delete from album_photos where album_id = ? and photo_id = ?", [album, photo]) }
    }
    func albumsOf(_ photo: Int64) -> Set<Int64> { Set(query("select album_id from album_photos where photo_id = ?", [photo]) { $0.int(0) }) }
    func albumKeys(_ album: Int64) -> Set<String> {
        Set(query("select p.key from album_photos a join photos p on p.id = a.photo_id where a.album_id = ?", [album]) { $0.text(0)! })
    }
}

// MARK: library

struct Photo: Identifiable, Hashable {
    let url: URL
    var key: String
    let date: Date
    var shared: String? // the plain name|size key, when another file has it too and `key` tells this one apart
    var id: String { url.path }
}

/// `key|` + a hash of the file's first 64 KB (its EXIF): different photos with one name and size part, copies still match.
/// Same rule and hash as Android's told().
func told(_ key: String, _ url: URL) -> String {
    let head = (try? FileHandle(forReadingFrom: url)).flatMap { f in defer { try? f.close() }; return try? f.read(upToCount: 64 << 10) }
    return key + "|" + String((head.map { SHA256.hash(data: $0).map { String(format: "%02x", $0) }.joined() } ?? sha256(url.path)).prefix(16))
}

let imageTypes: Set<String> = ["jpg", "jpeg", "png", "heic", "heif", "tif", "tiff", "webp", "dng", "cr2", "cr3", "nef", "nrw", "arw", "srf", "sr2",
                               "raf", "orf", "rw2", "pef", "srw", "x3f", "3fr", "iiq", "erf", "mos", "mrw", "dcr", "kdc"]

/// Every photo under the folders, newest first. Files sharing a name and size (or told apart before: `split`) get keys of their own.
func scan(_ folders: [URL], split: Set<String> = []) -> [Photo] {
    var out = [Photo](), seen = Set<String>() // file paths
    let keys: [URLResourceKey] = [.fileSizeKey, .creationDateKey, .contentModificationDateKey, .isRegularFileKey]
    for folder in folders {
        guard let it = FileManager.default.enumerator(at: folder, includingPropertiesForKeys: keys, options: [.skipsHiddenFiles, .skipsPackageDescendants]) else { continue }
        for case let url as URL in it where imageTypes.contains(url.pathExtension.lowercased()) {
            let v = try? url.resourceValues(forKeys: Set(keys))
            let key = "\(url.lastPathComponent)|\(v?.fileSize ?? 0)"
            if seen.insert(url.standardizedFileURL.path).inserted { out.append(Photo(url: url, key: key, date: v?.creationDate ?? v?.contentModificationDate ?? .distantPast)) }
        }
    }
    let twins = Set(Dictionary(grouping: out, by: \.key).filter { $0.value.count > 1 }.keys).union(split)
    for i in out.indices where twins.contains(out[i].key) { out[i].shared = out[i].key; out[i].key = told(out[i].key, out[i].url) }
    return out.sorted { $0.date > $1.date }
}

/// Library thumbnails: the camera's embedded preview when unedited, the edit rendered (and cached on disk) when edited.
enum Thumbs {
    static let cache = NSCache<NSString, CGImage>()
    static let dir: URL = {
        let d = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("Lumen/thumbs")
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }()

    static func get(_ p: Photo, edit: String?) -> CGImage? {
        let key = "\(p.url.path)|\(edit.map(sha256) ?? "")" as NSString // keyed by file + edit: a rating change doesn't re-render
        if let c = cache.object(forKey: key) { return c }
        var img: CGImage?
        if let edit {
            let file = dir.appendingPathComponent("\(sha256(key as String)).jpg")
            if let s = CGImageSourceCreateWithURL(file as CFURL, nil) { img = CGImageSourceCreateImageAtIndex(s, 0, nil) }
            if img == nil, let src = decode(p.url, maxPixel: 600) {
                var e = Edit(); e.merge(edit)
                img = Look(src).render(e, maxLong: 400)
                if let img, let d = CGImageDestinationCreateWithURL(file as CFURL, UTType.jpeg.identifier as CFString, 1, nil) {
                    CGImageDestinationAddImage(d, img, [kCGImageDestinationLossyCompressionQuality: 0.85] as CFDictionary); CGImageDestinationFinalize(d)
                }
            }
        } else {
            img = decode(p.url, maxPixel: 320)
        }
        if let img { cache.setObject(img, forKey: key) }
        return img
    }
}

// MARK: export

struct ExportOptions: Codable, Equatable {
    var format = "JPEG"   // JPEG, PNG, HEIC, TIFF
    var long = 0          // long edge, 0 = full size
    var quality = 92
    var meta = true
    var location = false
    var watermark = ""
    var folder = FileManager.default.urls(for: .picturesDirectory, in: .userDomainMask)[0].appendingPathComponent("Lumen").path

    static var saved: ExportOptions {
        get { var o = (UserDefaults.standard.data(forKey: "export")).flatMap { try? JSONDecoder().decode(ExportOptions.self, from: $0) } ?? ExportOptions()
            o.location = false; return o }
        set { var o = newValue; o.location = false // GPS goes out only when asked for this export, never by default later
            UserDefaults.standard.set(try? JSONEncoder().encode(o), forKey: "export") }
    }
}

/// Watermark text, bottom right, sized to the photo, white with a soft shadow.
private func watermark(_ cg: CGImage, _ text: String) -> CGImage {
    if text.trimmingCharacters(in: .whitespaces).isEmpty { return cg }
    let ctx = CGContext(data: nil, width: cg.width, height: cg.height, bitsPerComponent: 8, bytesPerRow: 0, space: sRGB,
                        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    ctx.draw(cg, in: CGRect(x: 0, y: 0, width: cg.width, height: cg.height))
    let size = CGFloat(max(cg.width, cg.height)) * 0.025
    let shadow = NSShadow(); shadow.shadowBlurRadius = size * 0.15; shadow.shadowColor = .black.withAlphaComponent(0.6)
    let s = NSAttributedString(string: text, attributes: [.font: NSFont.systemFont(ofSize: size, weight: .medium),
                                                          .foregroundColor: NSColor.white.withAlphaComponent(0.9), .shadow: shadow])
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(cgContext: ctx, flipped: false)
    s.draw(at: CGPoint(x: CGFloat(cg.width) - size - s.size().width, y: size))
    NSGraphicsContext.restoreGraphicsState()
    return ctx.makeImage()!
}

/// Renders `e` on the original file at the requested size and writes it to the export folder. Returns the file, or nil.
func exportPhoto(_ url: URL, _ e: Edit, _ o: ExportOptions) -> (URL, Int, Int)? {
    // requested size first, 4096 px if the full image can't be decoded or rendered (memory), as on Android
    let attempts = o.long > 0 ? [o.long] : [0, 4096]
    guard var img = attempts.lazy.compactMap({ long -> CGImage? in
        guard let src = decode(url, maxPixel: long == 0 ? 0 : max(long, 4096)) else { return nil }
        return Look(src).renderOrNil(e, maxLong: Double(long))
    }).first else { return nil }
    img = watermark(img, o.watermark)
    let (type, ext): (UTType, String) = switch o.format {
        case "PNG": (.png, "png")
        case "HEIC": (.heic, "heic")
        case "TIFF": (.tiff, "tif")
        default: (.jpeg, "jpg")
    }
    let folder = URL(fileURLWithPath: o.folder)
    try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    let name = url.deletingPathExtension().lastPathComponent
    var out = folder.appendingPathComponent("\(name)_Lumen.\(ext)")
    var n = 2
    while FileManager.default.fileExists(atPath: out.path) { out = folder.appendingPathComponent("\(name)_Lumen_\(n).\(ext)"); n += 1 }
    guard let dest = CGImageDestinationCreateWithURL(out as CFURL, type.identifier as CFString, 1, nil) else { return nil }
    var props: [CFString: Any] = [kCGImageDestinationLossyCompressionQuality: Double(o.quality) / 100]
    if o.meta, let src = CGImageSourceCreateWithURL(url as CFURL, nil), let p = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any] {
        // camera, lens, exposure and dates; location only when asked
        for k in [kCGImagePropertyExifDictionary, kCGImagePropertyExifAuxDictionary] { if let v = p[k] { props[k] = v } }
        if var tiff = p[kCGImagePropertyTIFFDictionary] as? [CFString: Any] {
            tiff[kCGImagePropertyTIFFOrientation] = 1; tiff[kCGImagePropertyTIFFSoftware] = "Lumen"; props[kCGImagePropertyTIFFDictionary] = tiff
        }
        if o.location, let gps = p[kCGImagePropertyGPSDictionary] { props[kCGImagePropertyGPSDictionary] = gps }
    } else {
        props[kCGImagePropertyTIFFDictionary] = [kCGImagePropertyTIFFSoftware: "Lumen"]
    }
    props[kCGImagePropertyOrientation] = 1 // orientation is baked into the pixels
    CGImageDestinationAddImage(dest, img, props as CFDictionary)
    guard CGImageDestinationFinalize(dest) else { try? FileManager.default.removeItem(at: out); return nil } // no half-written file
    return (out, img.width, img.height)
}
