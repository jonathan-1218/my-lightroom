import SwiftUI
import AppKit
import UniformTypeIdentifiers

@main
enum Entry {
    static func main() {
        if CommandLine.arguments.contains("--selftest") { exit(selfTest() ? 0 : 1) }
        if let i = CommandLine.arguments.firstIndex(of: "--check"), CommandLine.arguments.count > i + 1 {
            exit(checkFolder(URL(fileURLWithPath: CommandLine.arguments[i + 1])) ? 0 : 1)
        }
        if let i = CommandLine.arguments.firstIndex(of: "--bench"), CommandLine.arguments.count > i + 1 {
            exit(bench(URL(fileURLWithPath: CommandLine.arguments[i + 1])) ? 0 : 1)
        }
        LumenApp.main()
    }
}

/// Design tokens: the same assets/tokens.json the Android app reads (copied into the bundle by build.sh).
enum Tokens {
    static let json: [String: Any] = {
        let url = Bundle.main.url(forResource: "tokens", withExtension: "json")
            ?? URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../../src/main/assets/tokens.json")
        return (try? JSONSerialization.jsonObject(with: Data(contentsOf: url))) as? [String: Any] ?? [:]
    }()
    static func rgba(_ role: String) -> (Double, Double, Double, Double) {
        var hex = ((json["color"] as? [String: String])?[role] ?? "#e3b45f").dropFirst().description
        var a = 1.0
        if hex.count == 8 { a = Double(Int(hex.prefix(2), radix: 16)!) / 255; hex = String(hex.dropFirst(2)) } // #AARRGGBB
        let v = Int(hex, radix: 16) ?? 0
        return (Double((v >> 16) & 255) / 255, Double((v >> 8) & 255) / 255, Double(v & 255) / 255, a)
    }
    static func color(_ role: String) -> Color { let c = rgba(role); return Color(.sRGB, red: c.0, green: c.1, blue: c.2, opacity: c.3) }
}

struct LumenApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup("Lumen") {
            ContentView()
                .environmentObject(model)
                .frame(minWidth: 1000, minHeight: 640)
                .preferredColorScheme(.dark)
                .tint(Tokens.color("accent"))
        }
        .commands {
            CommandGroup(replacing: .undoRedo) {
                Button("Undo") { model.step(back: true) }.keyboardShortcut("z").disabled(model.undoStack.isEmpty)
                Button("Redo") { model.step(back: false) }.keyboardShortcut("z", modifiers: [.command, .shift]).disabled(model.redoStack.isEmpty)
            }
            CommandGroup(after: .pasteboard) {
                Divider()
                Button("Copy Edits") { model.copyEdits() }.keyboardShortcut("c", modifiers: [.command, .shift]).disabled(model.current == nil)
                Button("Paste Edits") { model.pasteEdits() }.keyboardShortcut("v", modifiers: [.command, .shift]).disabled(model.clip == nil)
            }
            CommandMenu("Photo") {
                Button("Back to Library") { model.close() }.keyboardShortcut("l", modifiers: .command).disabled(model.current == nil)
                Button("Previous Photo") { model.next(-1) }.keyboardShortcut(.leftArrow, modifiers: .command)
                Button("Next Photo") { model.next(1) }.keyboardShortcut(.rightArrow, modifiers: .command)
                Divider()
                Button("Auto Tone") { model.auto() }.keyboardShortcut("u", modifiers: [.command, .shift]).disabled(model.current == nil)
                Button("Reset All Edits") { model.reset() }.keyboardShortcut("r", modifiers: [.command, .shift]).disabled(model.current == nil)
                Divider()
                Button("Export…") { model.showExport = true }.keyboardShortcut("e", modifiers: [.command, .shift])
                    .disabled(model.current == nil && model.selection.isEmpty)
            }
        }
    }
}

/// App state: library, the open photo, its edit, history and rendering.
final class AppModel: ObservableObject {
    let db = Db()

    // library
    @Published var folders: [URL] = (UserDefaults.standard.stringArray(forKey: "folders") ?? []).map { URL(fileURLWithPath: $0) }
    @Published var photos: [Photo] = []
    @Published var rows: [String: Row] = [:]
    var updated: [String: Int64] = [:]
    @Published var albums: [Album] = []
    @Published var albumKeys: [Int64: Set<String>] = [:]
    @Published var filter = "All"
    @Published var selection = Set<String>()
    @Published var scanning = false

    // editor
    @Published var current: Photo?
    @Published var look: Look?
    @Published var row: Row?
    @Published var edit = Edit() { didSet { if edit != oldValue { changed() } } }
    @Published var preview: CGImage?
    @Published var previewMode = "normal" // what `preview` shows: the cropped output, or the whole frame in crop mode
    @Published var hist: [[Int]]?
    @Published var mode = "normal" { didSet { if mode != oldValue { render() } } }
    @Published var before = false
    @Published var selMask = -1 { didSet { if selMask != oldValue { render() } } }
    @Published var selSpot = -1
    @Published var showOverlay = true { didSet { render() } }
    @Published var aspect: Double?
    @Published var brushSize: Float = 0.05
    @Published var erase = false
    @Published var heal = true
    @Published var spotSize: Float = 0.03
    @Published var presets: [Preset] = []
    @Published var versions: [Version] = []
    @Published var profiles = Profiles.names()
    @Published var undoStack: [Edit] = []
    @Published var redoStack: [Edit] = []
    @Published var clip: String?
    @Published var toast: String?
    @Published var showExport = false
    @Published var loading = false

    var viewLong: Double = 1600
    private let renderQ = DispatchQueue(label: "lumen.render", qos: .userInteractive)
    private var gen = 0
    private var saveWork: DispatchWorkItem?
    private var histWork: DispatchWorkItem?

    init() {
        presets = db.presets()
        refresh()
        // Quit within the autosave delay: save now, or the last change is lost
        NotificationCenter.default.addObserver(forName: NSApplication.willTerminateNotification, object: nil, queue: .main) { [weak self] _ in
            self?.saveNow()
        }
    }

    // MARK: library

    func addFolder(_ url: URL) {
        guard !folders.contains(url) else { return }
        folders.append(url); UserDefaults.standard.set(folders.map(\.path), forKey: "folders"); refresh()
    }

    func removeFolder(_ url: URL) {
        folders.removeAll { $0 == url }; UserDefaults.standard.set(folders.map(\.path), forKey: "folders"); refresh()
    }

    func refresh() {
        scanning = true
        let fs = folders
        DispatchQueue.global(qos: .userInitiated).async {
            let list = scan(fs, split: self.db.split())
            DispatchQueue.main.async {
                // twins get rows of their own before the grid reads ratings and edits
                self.db.tx { list.allSatisfy { p in p.shared.map { self.db.open(key: p.key, uri: p.url.path, w: 1, h: 1, shared: $0) }; return true } }
                self.photos = list; self.scanning = false; self.reloadRows()
            }
        }
    }

    func reloadRows() {
        let all = db.rows()
        rows = all.mapValues(\.0); updated = all.mapValues(\.1)
        albums = db.albums()
        albumKeys = Dictionary(uniqueKeysWithValues: albums.map { ($0.id, db.albumKeys($0.id)) })
        presets = db.presets()
    }

    func isEdited(_ key: String) -> Bool {
        guard let r = rows[key] else { return false }
        var e = Edit(); e.merge(r.edit); return !e.isBlank
    }

    var filters: [String] {
        let labels = Set(rows.values.compactMap(\.label))
        return ["All", "Picks", "3★ +", "Rejects", "Edited"] + ["red", "yellow", "green", "blue", "purple"].filter(labels.contains).map(\.capitalized)
            + albums.map { "Album: \($0.name)" }
    }

    var shown: [Photo] {
        photos.filter { p in
            let r = rows[p.key]
            switch filter {
            case "All": return true
            case "Picks": return r?.flag == 1
            case "3★ +": return (r?.rating ?? 0) >= 3
            case "Rejects": return r?.flag == -1
            case "Edited": return isEdited(p.key)
            case let f where f.hasPrefix("Album: "):
                guard let a = albums.first(where: { "Album: \($0.name)" == f }) else { return false }
                return albumKeys[a.id]?.contains(p.key) == true
            default: return r?.label == filter.lowercased()
            }
        }
    }

    func say(_ s: String) {
        toast = s
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) { if self.toast == s { self.toast = nil } }
    }

    // MARK: open / close

    func open(_ p: Photo) {
        saveNow()
        current = p; loading = true; preview = nil; look = nil; hist = nil
        selMask = -1; selSpot = -1
        DispatchQueue.global(qos: .userInitiated).async {
            let full = CGImageSourceCreateWithURL(p.url as CFURL, nil).flatMap { CGImageSourceCopyPropertiesAtIndex($0, 0, nil) as? [CFString: Any] }
            let w = full?[kCGImagePropertyPixelWidth] as? Int ?? 1, h = full?[kCGImagePropertyPixelHeight] as? Int ?? 1
            let cg = decode(p.url, maxPixel: 2048) // preview: the kernel runs per screen pixel, 2048 is plenty
            DispatchQueue.main.async {
                guard self.current == p else { return }
                self.loading = false
                guard let cg else { self.say("Can't open this file"); self.current = nil; return }
                let l = Look(cg); let o = Tokens.rgba("overlay"); l.overlay = (o.0, o.1, o.2)
                let r = self.db.open(key: p.key, uri: p.url.path, w: w, h: h, shared: p.shared)
                self.row = r; self.look = l
                var e = Edit(); e.merge(r.edit)
                self.undoStack = []; self.redoStack = []
                self.edit = e
                self.selMask = e.masks.isEmpty ? -1 : 0 // the Masks tab opens on the first mask's controls
                self.versions = self.db.versions(r.id)
                self.render(); self.updateHistogram()
            }
        }
    }

    func close() { saveNow(); current = nil; look = nil; preview = nil; mode = "normal"; reloadRows() }

    func next(_ d: Int) {
        let list = shown
        guard let c = current, let i = list.firstIndex(of: c), list.indices.contains(i + d) else { return }
        open(list[i + d])
    }

    // MARK: history

    func checkpoint() {
        undoStack.append(edit)
        if undoStack.count > 100 { undoStack.removeFirst() }
        redoStack = []
    }

    func step(back: Bool) {
        if back { guard let e = undoStack.popLast() else { return }; redoStack.append(edit); edit = e }
        else { guard let e = redoStack.popLast() else { return }; undoStack.append(edit); edit = e }
    }

    /// One undo step, then the change.
    func apply(_ f: (inout Edit) -> Void) { checkpoint(); var e = edit; f(&e); edit = e }

    func reset() { apply { e in let keep = e; e = Edit(); e.crop = keep.crop; e.rot90 = keep.rot90; e.flipH = keep.flipH
        for k in ["straighten", "vertical", "horizontal"] { e[k] = keep[k] } } }

    func auto() { guard let l = look else { return }; let a = autoTone(l.base); apply { e in for (k, v) in a { e[k] = v } } }

    func copyEdits() { clip = edit.json(whole: false); say("Edits copied") }
    func pasteEdits() {
        guard let c = clip else { return }
        if current != nil { apply { $0.merge(c, whole: false) } } else { batch("Pasted onto %d photos") { id in var e = Edit(); e.merge(self.db.editOf(id)); e.merge(c, whole: false); self.db.saveEdit(id, e.json()) } }
    }

    // MARK: render, save

    private func changed() {
        render()
        saveWork?.cancel()
        let w = DispatchWorkItem { [weak self] in self?.saveNow() }
        saveWork = w; DispatchQueue.main.asyncAfter(deadline: .now() + 0.3, execute: w) // autosave, debounced
        histWork?.cancel()
        let h = DispatchWorkItem { [weak self] in self?.updateHistogram() }
        histWork = h; DispatchQueue.main.asyncAfter(deadline: .now() + 0.25, execute: h)
    }

    func saveNow() {
        saveWork?.cancel(); saveWork = nil
        guard let r = row else { return }
        let j = edit.json()
        guard j != r.edit else { return }
        // only remember it as saved when it really is: a failed write is retried on the next change
        if db.saveEdit(r.id, j) { row?.edit = j; rows[current?.key ?? ""] = row } else { say("Couldn't save your edits") }
    }

    func render() {
        guard let l = look else { return }
        gen += 1
        let g = gen, e = edit, m = mode, show = mode == "mask" && showOverlay ? selMask : -1, long = viewLong
        renderQ.async {
            if g != self.gen { return } // a newer edit is already queued
            let img = l.render(e, maxLong: long, withCrop: m != "crop", showMask: show)
            DispatchQueue.main.async { if g == self.gen { self.preview = img; self.previewMode = m } }
        }
    }

    func updateHistogram() {
        guard let l = look else { return }
        let e = edit
        renderQ.async {
            let h = histogram(l.render(e, maxLong: 200))
            DispatchQueue.main.async { self.hist = h }
        }
    }

    // MARK: marks, presets, versions, albums

    func mark(_ column: String, _ value: Any?) {
        guard var r = row else { return }
        guard db.mark(r.id, column, value) else { return say("Couldn't save that") }
        switch column { case "rating": r.rating = value as! Int; case "flag": r.flag = value as! Int; default: r.label = value as? String }
        row = r; if let c = current { rows[c.key] = r }
    }

    /// Marks the selected photos (library) or the open one.
    func markAll(_ column: String, _ value: Any?) {
        if current != nil { mark(column, value) } else { batch("Updated %d photos") { self.db.mark($0, column, value) } }
    }

    func applyPreset(_ p: Preset) {
        if current != nil { apply { $0.merge(p.edit, whole: false) } }
        else { batch("Applied \(p.name) to %d photos") { id in var e = Edit(); e.merge(self.db.editOf(id)); e.merge(p.edit, whole: false); self.db.saveEdit(id, e.json()) } }
    }

    func savePreset(_ name: String) { db.savePreset(name, edit.json(whole: false)); presets = db.presets() }
    func deletePreset(_ p: Preset) { db.deletePreset(p.id); presets = db.presets() }

    func saveVersion(_ name: String) { guard let r = row else { return }; saveNow(); db.saveVersion(r.id, name, edit.json()); versions = db.versions(r.id) }
    func restore(_ v: Version) { apply { e in e = Edit(); e.merge(v.edit) } }
    func deleteVersion(_ v: Version) { guard let r = row else { return }; db.deleteVersion(v.id); versions = db.versions(r.id) }

    func setAlbum(_ a: Album, _ inside: Bool) {
        if let r = row { db.setInAlbum(a.id, r.id, inside) } else { batch("Updated %d photos") { self.db.setInAlbum(a.id, $0, inside) } }
        albumKeys[a.id] = db.albumKeys(a.id)
    }
    func newAlbum(_ name: String) {
        let id = db.createAlbum(name)
        albums = db.albums()
        if let a = albums.first(where: { $0.id == id }) { setAlbum(a, true) }
    }

    /// Runs `each(row id)` for every selected photo (creating rows as needed), then refreshes the grid.
    func batch(_ done: String, _ each: @escaping (Int64) -> Void) {
        let list = photos.filter { selection.contains($0.key) }
        db.tx { for p in list { each(db.open(key: p.key, uri: p.url.path, w: 1, h: 1).id) }; return true } // one transaction: fast, all or nothing
        reloadRows(); say(String(format: done, list.count))
    }

    // MARK: import

    /// At most 8 MB of a file as text (plenty for a LUT or a preset), or nil.
    private func readCapped(_ url: URL) -> String? {
        guard let h = try? FileHandle(forReadingFrom: url), let d = try? h.read(upToCount: 8 << 20) else { return nil }
        try? h.close()
        return String(decoding: d, as: UTF8.self)
    }

    func importLUT(_ url: URL) {
        say("Importing…")
        DispatchQueue.global(qos: .userInitiated).async { // parsing never blocks the window
            let result = Result { () throws -> String in
                guard let text = self.readCapped(url) else { throw LumenError("Couldn't read that file") }
                return try Profiles.importCube(name: url.deletingPathExtension().lastPathComponent, text: text)
            }
            DispatchQueue.main.async {
                switch result {
                case .success(let saved): self.profiles = Profiles.names(); self.say("Profile \"\(saved)\" added")
                case .failure(let error): self.say("Not a 3D .cube LUT: \(error.localizedDescription)")
                }
            }
        }
    }

    func importPreset(_ url: URL) {
        say("Importing…")
        DispatchQueue.global(qos: .userInitiated).async {
            let parsed = self.readCapped(url).flatMap { xmpPreset($0, fallbackName: url.deletingPathExtension().lastPathComponent) }
            DispatchQueue.main.async {
                guard let (n, json) = parsed else { return self.say("No settings Lumen understands in that file") }
                let saved = unique(n, self.db.presets().map(\.name)) // never replace a preset you already have
                self.db.savePreset(saved, json); self.presets = self.db.presets(); self.say("Preset \"\(saved)\" added")
            }
        }
    }

    // MARK: export

    func export(_ o: ExportOptions) {
        ExportOptions.saved = o
        saveNow()
        let items: [(URL, Edit)] = current.map { [($0.url, edit)] } ?? photos.filter { selection.contains($0.key) }.map { p in
            var e = Edit(); if let r = rows[p.key] { e.merge(r.edit) }; return (p.url, e)
        }
        say("Exporting \(items.count) photo\(items.count == 1 ? "" : "s")…")
        DispatchQueue.global(qos: .userInitiated).async {
            let done = items.compactMap { exportPhoto($0.0, $0.1, o) }
            DispatchQueue.main.async {
                if let last = done.last {
                    self.say(items.count == 1 ? "Exported \(last.1)×\(last.2) \(o.format)" : "Exported \(done.count) of \(items.count)")
                    NSWorkspace.shared.activateFileViewerSelecting(done.map(\.0))
                } else { self.say("Export failed") }
            }
        }
    }
}
