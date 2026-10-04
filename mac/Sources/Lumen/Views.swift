import SwiftUI
import AppKit
import UniformTypeIdentifiers

private let accent = Tokens.color("accent")
private let surface = Tokens.color("surface")
private let bg = Tokens.color("bg")
private let muted = Tokens.color("text-muted")
private let LABELS: [String?] = [nil, "red", "yellow", "green", "blue", "purple"]
let TITLES = ["grainSize": "Grain size", "noise": "Noise reduction", "colorNoise": "Color noise", "chroma": "Chromatic aberration",
              "lensVignette": "Lens vignetting", "temp": "Temperature"]
func title(_ k: String) -> String { TITLES[k] ?? k.prefix(1).uppercased() + k.dropFirst() }

struct ContentView: View {
    @EnvironmentObject var m: AppModel
    @FocusState private var focused: Bool
    @State private var columns = NavigationSplitViewVisibility.all

    var body: some View {
        NavigationSplitView(columnVisibility: $columns) {
            Sidebar()
        } detail: {
            ZStack(alignment: .bottom) {
                if m.current == nil { LibraryView() } else { EditorView() }
                if let t = m.toast {
                    Text(t).padding(.horizontal, 16).padding(.vertical, 8).background(.ultraThinMaterial, in: Capsule()).padding(.bottom, 20)
                }
            }
            .background(bg)
        }
        .focusable().focused($focused).focusEffectDisabled()
        .onAppear { focused = true }
        .onKeyPress(phases: .down) { press in keys(press) }
        .sheet(isPresented: $m.showExport) { ExportSheet() }
        .onChange(of: m.current == nil) { _, library in withAnimation { columns = library ? .all : .detailOnly } } // more room for the photo
    }

    /// Bare keys (no modifier) as in Lightroom: 0–5 rate, P pick, X reject, U unflag, \ before/after, arrows step through photos.
    private func keys(_ press: KeyPress) -> KeyPress.Result {
        guard press.modifiers.isEmpty || press.modifiers == .numericPad else { return .ignored }
        switch press.characters {
        case "0", "1", "2", "3", "4", "5": m.markAll("rating", Int(press.characters)!)
        case "p": m.markAll("flag", 1)
        case "x": m.markAll("flag", -1)
        case "u": m.markAll("flag", 0)
        case "\\": m.before.toggle()
        default:
            switch press.key {
            case .leftArrow where m.current != nil: m.next(-1)
            case .rightArrow where m.current != nil: m.next(1)
            case .escape where m.current != nil: m.close()
            default: return .ignored
            }
        }
        return .handled
    }
}

// MARK: sidebar

struct Sidebar: View {
    @EnvironmentObject var m: AppModel
    @State private var pickFolder = false

    var body: some View {
        List(selection: Binding(get: { m.filter }, set: { if let f = $0 { m.filter = f; m.selection = []; if m.current != nil { m.close() } } })) {
            Section("Library") {
                ForEach(m.filters, id: \.self) { f in
                    Label(f.replacingOccurrences(of: "Album: ", with: ""), systemImage: icon(f)).tag(f)
                }
            }
            Section("Folders") {
                ForEach(m.folders, id: \.self) { f in
                    Label(f.lastPathComponent, systemImage: "folder").contextMenu { Button("Remove from Lumen") { m.removeFolder(f) } }
                }
                Button { pickFolder = true } label: { Label("Add Folder…", systemImage: "plus") }.buttonStyle(.plain).foregroundStyle(accent)
            }
        }
        .navigationSplitViewColumnWidth(min: 180, ideal: 210)
        .fileImporter(isPresented: $pickFolder, allowedContentTypes: [.folder]) { r in if case .success(let u) = r { m.addFolder(u) } }
    }

    private func icon(_ f: String) -> String {
        switch f {
        case "All": "photo.on.rectangle"; case "Picks": "flag.fill"; case "3★ +": "star.fill"; case "Rejects": "xmark.circle"
        case "Edited": "slider.horizontal.3"; case let a where a.hasPrefix("Album: "): "rectangle.stack"; default: "circle.fill"
        }
    }
}

// MARK: library

struct LibraryView: View {
    @EnvironmentObject var m: AppModel
    @State private var newAlbum = false
    @State private var albumName = ""

    var body: some View {
        let shown = m.shown
        VStack(spacing: 0) {
            if m.folders.isEmpty {
                Spacer()
                Text("Add a folder of photos to start.").foregroundStyle(muted)
                Text("Lumen never changes your originals. Edits are saved separately and exported as new files.").font(.caption).foregroundStyle(muted)
                Spacer()
            } else if shown.isEmpty {
                Spacer()
                Text(m.scanning ? "Looking for photos…" : m.photos.isEmpty ? "No photos in these folders yet." : "Nothing matches this filter.").foregroundStyle(muted)
                Spacer()
            } else {
                ScrollView {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 170), spacing: 4)], spacing: 4) {
                        ForEach(shown) { p in
                            Thumb(p: p, row: m.rows[p.key], edited: m.isEdited(p.key), selected: m.selection.contains(p.key))
                                .onTapGesture(count: 2) { m.open(p) }
                                .onTapGesture {
                                    let mods = NSEvent.modifierFlags
                                    if mods.contains(.command) { if !m.selection.insert(p.key).inserted { m.selection.remove(p.key) } }
                                    else if mods.contains(.shift), let last = m.selection.first, let a = shown.firstIndex(where: { $0.key == last }), let b = shown.firstIndex(of: p) {
                                        for q in shown[min(a, b)...max(a, b)] { m.selection.insert(q.key) }
                                    } else { m.selection = [p.key] }
                                }
                                .contextMenu { batchMenu() }
                        }
                    }.padding(4)
                }
            }
        }
        .navigationTitle(m.selection.count > 1 ? "\(m.selection.count) selected" : m.filter.replacingOccurrences(of: "Album: ", with: ""))
        .toolbar {
            ToolbarItemGroup {
                if !m.selection.isEmpty {
                    Menu("Selected") { batchMenu() }
                    Button("Edit") { if let p = shown.first(where: { m.selection.contains($0.key) }) { m.open(p) } }
                }
                Button { m.refresh() } label: { Image(systemName: "arrow.clockwise") }.help("Rescan folders")
            }
        }
        .alert("New album", isPresented: $newAlbum) {
            TextField("Name", text: $albumName)
            Button("Create") { if !albumName.isEmpty { m.newAlbum(albumName) } }
            Button("Cancel", role: .cancel) {}
        }
    }

    @ViewBuilder private func batchMenu() -> some View {
        Menu("Rate") { ForEach(0...5, id: \.self) { n in Button(n == 0 ? "No stars" : String(repeating: "★", count: n)) { m.markAll("rating", n) } } }
        Menu("Flag") { Button("Pick") { m.markAll("flag", 1) }; Button("Reject") { m.markAll("flag", -1) }; Button("Unflag") { m.markAll("flag", 0) } }
        Menu("Label") { ForEach(LABELS, id: \.self) { l in Button(l?.capitalized ?? "None") { m.markAll("label", l) } } }
        Menu("Apply preset") { ForEach(m.presets) { p in Button(p.name) { m.applyPreset(p) } } }
        Button("Paste edits") { m.pasteEdits() }.disabled(m.clip == nil)
        Menu("Add to album") {
            ForEach(m.albums) { a in Button(a.name) { m.setAlbum(a, true) } }
            Button("New album…") { albumName = "Album \(m.albums.count + 1)"; newAlbum = true }
        }
        Divider()
        Button("Export…") { m.showExport = true }
    }
}

struct Thumb: View {
    let p: Photo; let row: Row?; let edited: Bool; let selected: Bool
    @State private var img: CGImage?

    var body: some View {
        Color.clear.aspectRatio(1, contentMode: .fit) // a square cell; the photo fills it and is clipped, whatever its shape
        .overlay { ZStack {
            Rectangle().fill(surface)
            if let img { Image(decorative: img, scale: 1).resizable().scaledToFill() }
            if row?.flag == -1 { Rectangle().fill(Tokens.color("scrim")) }
        } }
        .clipped()
        .overlay(alignment: .topLeading) { if row?.flag == 1 { Image(systemName: "flag.fill").padding(6).shadow(radius: 2) } }
        .overlay(alignment: .topTrailing) { if let l = row?.label { Circle().fill(Tokens.color("label-\(l)")).frame(width: 12, height: 12).padding(8) } }
        .overlay(alignment: .bottomLeading) { if let r = row?.rating, r > 0 { Text(String(repeating: "★", count: r)).font(.caption).padding(6).shadow(radius: 2) } }
        .overlay(alignment: .bottomTrailing) { if edited { Text("±").font(.caption.bold()).padding(6).shadow(radius: 2) } }
        .overlay { if selected { Rectangle().strokeBorder(accent, lineWidth: 3) } }
        .contentShape(Rectangle())
        .accessibilityElement().accessibilityLabel(p.url.lastPathComponent + (row.map { $0.rating > 0 ? ", \($0.rating) stars" : "" } ?? "") + (edited ? ", edited" : ""))
        .task(id: "\(p.id)|\(edited ? row?.edit ?? "" : "")") {
            let e = edited ? row?.edit : nil
            img = await Task.detached(priority: .utility) { Thumbs.get(p, edit: e) }.value
        }
    }
}

// MARK: editor

struct EditorView: View {
    @EnvironmentObject var m: AppModel
    var body: some View {
        HStack(spacing: 0) {
            PhotoCanvas().frame(maxWidth: .infinity, maxHeight: .infinity).background(bg)
            Divider()
            Inspector().frame(width: 330).background(surface)
        }
        .navigationTitle(m.current?.url.lastPathComponent ?? "")
        .toolbar {
            ToolbarItemGroup(placement: .navigation) {
                Button { m.close() } label: { Label("Library", systemImage: "square.grid.2x2") }.help("Back to the library (Esc)")
            }
            ToolbarItemGroup {
                Button { m.step(back: true) } label: { Image(systemName: "arrow.uturn.backward") }.disabled(m.undoStack.isEmpty).help("Undo (⌘Z)")
                Button { m.step(back: false) } label: { Image(systemName: "arrow.uturn.forward") }.disabled(m.redoStack.isEmpty).help("Redo (⇧⌘Z)")
                Toggle(isOn: $m.before) { Image(systemName: "square.split.2x1") }.help("Before / after (\\)")
                Button("Export") { m.showExport = true }.help("Export (⇧⌘E)")
            }
        }
    }
}

/// Fit of the output frame in the view, and the mapping between view points and source coordinates (0...1, top-left).
struct Fit {
    let w, h, ow, oh, k, ox, oy: Double
    let m: Mat3
    init(look: Look, edit: Edit, crop: Bool, size: CGSize) {
        w = look.w; h = look.h
        let f: (Double, Double) = crop ? edit.oriented(look.w, look.h) : { let s = edit.outSize(look.w, look.h); return (Double(s.0), Double(s.1)) }()
        ow = f.0; oh = f.1
        let inset = crop ? 28.0 : 0 // room to grab the crop handles
        k = min((size.width - 2 * inset) / ow, (size.height - 2 * inset) / oh)
        ox = (size.width - ow * k) / 2; oy = (size.height - oh * k) / 2
        m = edit.matrix(look.w, look.h, withCrop: !crop)
    }
    var rect: CGRect { CGRect(x: ox, y: oy, width: ow * k, height: oh * k) }
    func view(_ u: Double, _ v: Double) -> CGPoint { let p = m.apply(CGPoint(x: u * w, y: v * h)); return CGPoint(x: ox + p.x * k, y: oy + p.y * k) }
    func source(_ pt: CGPoint) -> (Double, Double) { let p = m.inverse.apply(CGPoint(x: (pt.x - ox) / k, y: (pt.y - oy) / k)); return (p.x / w, p.y / h) }
    func box(_ c: CGRect) -> CGRect { CGRect(x: ox + c.minX * ow * k, y: oy + c.minY * oh * k, width: c.width * ow * k, height: c.height * oh * k) }
}

struct PhotoCanvas: View {
    @EnvironmentObject var m: AppModel
    @State private var part = -1
    @State private var started = false
    @State private var startCrop = CGRect.zero
    @State private var down: (Double, Double) = (0, 0)
    @State private var downG: [Float] = []
    @State private var downSpot: Spot?
    @State private var dragging: Spot?

    var body: some View {
        GeometryReader { geo in
            ZStack {
                if let look = m.look {
                    let crop = m.mode == "crop"
                    let fit = Fit(look: look, edit: m.edit, crop: crop, size: geo.size)
                    if m.before {
                        let k = min(geo.size.width / look.w, geo.size.height / look.h)
                        Image(decorative: look.base, scale: 1).resizable().frame(width: look.w * k, height: look.h * k)
                    } else if let p = m.preview, (m.previewMode == "crop") == crop {
                        Image(decorative: p, scale: 1).resizable().interpolation(.high).frame(width: fit.rect.width, height: fit.rect.height)
                            .position(x: fit.rect.midX, y: fit.rect.midY)
                    }
                    if !m.before { Canvas { ctx, _ in overlay(ctx, fit) }.allowsHitTesting(false) }
                    Color.clear.contentShape(Rectangle()).gesture(DragGesture(minimumDistance: 0).onChanged { g in
                        if !started && part == -1 && g.translation == .zero { begin(g.startLocation, fit) }
                        move(g.location, fit)
                    }.onEnded { _ in end() })
                } else if m.loading {
                    ProgressView()
                }
            }
            .onAppear { m.viewLong = Double(max(geo.size.width, geo.size.height)) * (NSScreen.main?.backingScaleFactor ?? 2) }
            .onChange(of: geo.size) { _, s in m.viewLong = min(2048, Double(max(s.width, s.height)) * (NSScreen.main?.backingScaleFactor ?? 2)); m.render() }
        }
    }

    // MARK: drawing

    private func overlay(_ ctx: GraphicsContext, _ f: Fit) {
        let line = GraphicsContext.Shading.color(.white), acc = GraphicsContext.Shading.color(accent)
        switch m.mode {
        case "crop":
            let r = f.box(m.edit.crop)
            var outside = Path(CGRect(x: -5000, y: -5000, width: 10000, height: 10000)); outside.addRect(r)
            ctx.fill(outside, with: .color(Tokens.color("scrim")), style: FillStyle(eoFill: true))
            ctx.stroke(Path(r), with: line, lineWidth: 1)
            for i in 1...2 {
                let x = r.minX + r.width * CGFloat(i) / 3, y = r.minY + r.height * CGFloat(i) / 3
                ctx.stroke(Path { $0.move(to: CGPoint(x: x, y: r.minY)); $0.addLine(to: CGPoint(x: x, y: r.maxY)) }, with: line, lineWidth: 0.5)
                ctx.stroke(Path { $0.move(to: CGPoint(x: r.minX, y: y)); $0.addLine(to: CGPoint(x: r.maxX, y: y)) }, with: line, lineWidth: 0.5)
            }
            for c in corners(r) { ctx.fill(Path(ellipseIn: CGRect(x: c.x - 6, y: c.y - 6, width: 12, height: 12)), with: acc) }
        case "mask":
            guard m.edit.masks.indices.contains(m.selMask) else { return }
            let mk = m.edit.masks[m.selMask], g = mk.g.map(Double.init)
            if mk.type == "linear" {
                let a = f.view(g[0], g[1]), b = f.view(g[2], g[3])
                let dx = b.x - a.x, dy = b.y - a.y, len = max(1, hypot(dx, dy)), px = -dy / len * 3000, py = dx / len * 3000
                ctx.stroke(Path { $0.move(to: CGPoint(x: a.x - px, y: a.y - py)); $0.addLine(to: CGPoint(x: a.x + px, y: a.y + py)) }, with: line, lineWidth: 1)
                ctx.stroke(Path { $0.move(to: CGPoint(x: b.x - px, y: b.y - py)); $0.addLine(to: CGPoint(x: b.x + px, y: b.y + py)) }, with: line, style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
                ctx.stroke(Path { $0.move(to: a); $0.addLine(to: b) }, with: line, style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
                ctx.fill(Path(ellipseIn: CGRect(x: a.x - 7, y: a.y - 7, width: 14, height: 14)), with: acc)
                ctx.stroke(Path(ellipseIn: CGRect(x: b.x - 7, y: b.y - 7, width: 14, height: 14)), with: acc, lineWidth: 2)
            } else if mk.type == "radial" {
                ctx.stroke(Path { p in
                    for i in 0...64 {
                        let t = Double(i) / 64 * 2 * .pi
                        let q = f.view(g[0] + g[2] * cos(t), g[1] + g[3] * sin(t))
                        if i == 0 { p.move(to: q) } else { p.addLine(to: q) }
                    }
                }, with: line, lineWidth: 1)
                let c = f.view(g[0], g[1]), rx = f.view(g[0] + g[2], g[1]), ry = f.view(g[0], g[1] + g[3])
                ctx.fill(Path(ellipseIn: CGRect(x: c.x - 7, y: c.y - 7, width: 14, height: 14)), with: acc)
                for q in [rx, ry] { ctx.stroke(Path(ellipseIn: CGRect(x: q.x - 6, y: q.y - 6, width: 12, height: 12)), with: acc, lineWidth: 2) }
            } else if mk.type == "color", g[3] > 0 {
                let sw = CGRect(x: f.rect.minX + 12, y: f.rect.minY + 12, width: 24, height: 24)
                ctx.fill(Path(ellipseIn: sw), with: .color(Color(.sRGB, red: g[0], green: g[1], blue: g[2])))
                ctx.stroke(Path(ellipseIn: sw), with: line, lineWidth: 1)
            }
        case "retouch":
            guard let look = m.look else { return }
            for (i, saved) in m.edit.retouch.enumerated() {
                let s = i == m.selSpot ? dragging ?? saved : saved
                let d = f.view(Double(s.dx), Double(s.dy)), src = f.view(Double(s.sx), Double(s.sy))
                let r = Double(s.r) * max(look.w, look.h) * f.k
                ctx.stroke(Path(ellipseIn: CGRect(x: d.x - r, y: d.y - r, width: 2 * r, height: 2 * r)), with: i == m.selSpot ? acc : line, lineWidth: i == m.selSpot ? 2 : 1)
                if i == m.selSpot {
                    ctx.stroke(Path(ellipseIn: CGRect(x: src.x - r, y: src.y - r, width: 2 * r, height: 2 * r)), with: line, style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
                    ctx.stroke(Path { $0.move(to: src); $0.addLine(to: d) }, with: line, style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
                }
            }
        default: break
        }
    }

    private func corners(_ r: CGRect) -> [CGPoint] {
        [CGPoint(x: r.minX, y: r.minY), CGPoint(x: r.maxX, y: r.minY), CGPoint(x: r.maxX, y: r.maxY), CGPoint(x: r.minX, y: r.maxY)]
    }
    private func near(_ a: CGPoint, _ b: CGPoint) -> Bool { hypot(a.x - b.x, a.y - b.y) < 16 }

    /// One undo step per gesture, none for a plain click.
    private func once() { if !started { started = true; m.checkpoint() } }

    // MARK: gestures (port of the Android EditView)

    private func begin(_ pt: CGPoint, _ f: Fit) {
        started = false
        let (u, v) = f.source(pt)
        down = (u, v)
        switch m.mode {
        case "crop":
            let r = f.box(m.edit.crop)
            part = corners(r).firstIndex { near($0, pt) } ?? (r.contains(pt) ? 4 : -2)
            startCrop = m.edit.crop
            down = ((pt.x - f.ox) / (f.k * f.ow), (pt.y - f.oy) / (f.k * f.oh))
        case "mask":
            guard m.edit.masks.indices.contains(m.selMask) else { part = -2; return }
            let mk = m.edit.masks[m.selMask], g = mk.g.map(Double.init)
            switch mk.type {
            case "brush":
                guard mk.strokes.reduce(0, { $0 + $1.pts.count }) < MAX_MASK_POINTS * 2 else { part = -2; return m.say("This mask is full: add another") }
                once(); m.edit.masks[m.selMask].strokes.append(Stroke(pts: [Float(u), Float(v)], size: m.brushSize, erase: m.erase)); part = 9
            case "color":
                part = -2
                guard (0...1).contains(u), (0...1).contains(v), let look = m.look,
                      let one = look.base.cropping(to: CGRect(x: Int(u * (look.w - 1)), y: Int(v * (look.h - 1)), width: 1, height: 1)) else { return }
                let p = Pixels(one).data
                m.apply { $0.masks[m.selMask].g = [Float(p[0]) / 255, Float(p[1]) / 255, Float(p[2]) / 255, 1] }
            default:
                let pts = mk.type == "linear" ? [f.view(g[0], g[1]), f.view(g[2], g[3]), f.view((g[0] + g[2]) / 2, (g[1] + g[3]) / 2)]
                    : [f.view(g[0], g[1]), f.view(g[0] + g[2], g[1]), f.view(g[0], g[1] + g[3])]
                part = pts.firstIndex { near($0, pt) } ?? -2
                if mk.type == "linear" && part == 2 { part = 3 }
                if part == -2 && mk.type == "radial" && hypot((u - g[0]) / g[2], (v - g[1]) / g[3]) < 1 { part = 0 }
                downG = mk.g
            }
        case "retouch":
            let sel = m.edit.retouch.indices.contains(m.selSpot) ? m.edit.retouch[m.selSpot] : nil
            if let sel, near(f.view(Double(sel.sx), Double(sel.sy)), pt) { part = 1 }
            else if let i = m.edit.retouch.lastIndex(where: { near(f.view(Double($0.dx), Double($0.dy)), pt) }) { m.selSpot = i; part = 0 }
            else { part = -2; addSpot(u, v) }
            downSpot = m.edit.retouch.indices.contains(m.selSpot) ? m.edit.retouch[m.selSpot] : nil
        default:
            part = -2
        }
    }

    private func move(_ pt: CGPoint, _ f: Fit) {
        guard part >= 0 else { return }
        let (u, v) = f.source(pt)
        switch m.mode {
        case "crop":
            let cu = (pt.x - f.ox) / (f.k * f.ow), cv = (pt.y - f.oy) / (f.k * f.oh)
            let want = part == 4 ? startCrop.offsetBy(dx: cu - down.0, dy: cv - down.1) : resize(cu, cv, f)
            once(); m.edit.crop = clampTo(startCrop, want, f)
        case "mask":
            let i = m.selMask
            guard m.edit.masks.indices.contains(i) else { return }
            if m.edit.masks[i].type == "brush" {
                guard let last = m.edit.masks[i].strokes.last?.pts, last.count >= 2 else { return }
                // keep a point only when the pointer has really moved, and stop at the cap: the edit stays small
                guard hypot(Float(u) - last[last.count - 2], Float(v) - last[last.count - 1]) >= 0.002,
                      m.edit.masks[i].strokes.reduce(0, { $0 + $1.pts.count }) < MAX_MASK_POINTS * 2 else { return }
                m.edit.masks[i].strokes[m.edit.masks[i].strokes.count - 1].pts += [Float(u), Float(v)]
                return
            }
            once()
            let du = Float(u - down.0), dv = Float(v - down.1)
            var g = downG
            switch (m.edit.masks[i].type, part) {
            case ("linear", 0): g[0] += du; g[1] += dv
            case ("linear", 1): g[2] += du; g[3] += dv
            case ("linear", _): g[0] += du; g[1] += dv; g[2] += du; g[3] += dv
            case (_, 0): g[0] += du; g[1] += dv
            case (_, 1): g[2] = max(0.01, abs(Float(u) - g[0]))
            default: g[3] = max(0.01, abs(Float(v) - g[1]))
            }
            m.edit.masks[i].g = g
        case "retouch":
            guard let s = downSpot else { return }
            let du = Float(u - down.0), dv = Float(v - down.1)
            dragging = part == 0 ? { var t = s; t.dx += du; t.dy += dv; return t }() : { var t = s; t.sx += du; t.sy += dv; return t }()
            m.objectWillChange.send() // only the circles move while dragging; the photo updates on release
        default: break
        }
    }

    private func end() {
        if let d = dragging, m.edit.retouch.indices.contains(m.selSpot) { m.apply { $0.retouch[m.selSpot] = d } }
        dragging = nil; part = -1; started = false
    }

    private func addSpot(_ x: Double, _ y: Double) {
        guard let look = m.look, (0...1).contains(x), (0...1).contains(y) else { return }
        let r = Double(m.spotSize), rx = r * max(look.w, look.h) / look.w, ry = r * max(look.w, look.h) / look.h
        guard x - rx >= 0, x + rx <= 1, y - ry >= 0, y + ry <= 1 else { return }
        func inside(_ p: (Double, Double)) -> Bool { p.0 - rx >= 0 && p.0 + rx <= 1 && p.1 - ry >= 0 && p.1 + ry <= 1 }
        let beside: [(Double, Double)] = [(x + 2.5 * rx, y), (x - 2.5 * rx, y), (x, y + 2.5 * ry), (x, y - 2.5 * ry)]
        var src: (Double, Double)? = beside.first(where: inside)
        if let b = bestSource(look.base, Float(x), Float(y), m.spotSize) { src = (Double(b.0), Double(b.1)) } // the patch whose surroundings match best
        guard let (sx, sy) = src else { return }
        m.apply { $0.retouch.append(Spot(dx: Float(x), dy: Float(y), sx: Float(sx), sy: Float(sy), r: m.spotSize, heal: m.heal)) }
        m.selSpot = m.edit.retouch.count - 1
    }

    /// Drag corner `part` to (u, v), keeping the opposite corner fixed and the aspect if locked.
    private func resize(_ u: Double, _ v: Double, _ f: Fit) -> CGRect {
        let a = corners(startCrop)[(part + 2) % 4]
        let mn = 0.05
        var w = max(mn, abs(u - a.x)), h = max(mn, abs(v - a.y))
        if let asp = m.aspect { h = w * f.ow / (asp * f.oh); if h < mn { h = mn; w = h * asp * f.oh / f.ow } }
        return CGRect(x: u < a.x ? a.x - w : a.x, y: v < a.y ? a.y - h : a.y, width: w, height: h)
    }

    /// Furthest point from `from` toward `to` that still fits inside the photo.
    private func clampTo(_ from: CGRect, _ to: CGRect, _ f: Fit) -> CGRect {
        func at(_ t: Double) -> CGRect {
            CGRect(x: from.minX + (to.minX - from.minX) * t, y: from.minY + (to.minY - from.minY) * t,
                   width: from.width + (to.width - from.width) * t, height: from.height + (to.height - from.height) * t)
        }
        if m.edit.fits(to, f.ow, f.oh) { return to }
        var lo = 0.0, hi = 1.0
        for _ in 0..<16 { let mid = (lo + hi) / 2; if m.edit.fits(at(mid), f.ow, f.oh) { lo = mid } else { hi = mid } }
        return at(lo)
    }
}

// MARK: inspector

struct SliderRow: View {
    let title: String
    @Binding var value: Float
    var range: ClosedRange<Float> = -1...1
    var format: (Float) -> String = { String(format: "%+d", Int(($0 * 100).rounded())) }
    let onBegin: () -> Void

    var body: some View {
        VStack(spacing: 2) {
            HStack {
                Text(title).onTapGesture(count: 2) { onBegin(); value = range.contains(0) ? 0 : range.lowerBound }
                    .help("Double-click to reset")
                Spacer()
                Text(format(value)).monospacedDigit().foregroundStyle(value == 0 ? muted : .primary)
            }.font(.callout)
            Slider(value: $value, in: range, onEditingChanged: { if $0 { onBegin() } }).controlSize(.small)
                .accessibilityLabel(title).accessibilityValue(format(value))
        }
    }
}

struct Inspector: View {
    @EnvironmentObject var m: AppModel
    @State private var tab = "Edit"
    @State private var hue = 0
    @State private var range = "shadow"
    @State private var channel = 0
    @State private var prompt: (String, String, (String) -> Void)?
    @State private var promptText = ""
    @State private var importing: [UTType]?

    var body: some View {
        VStack(spacing: 0) {
            Picker("", selection: $tab) { ForEach(["Edit", "Crop", "Masks", "Retouch", "Presets", "Info"], id: \.self) { Text($0) } }
                .pickerStyle(.segmented).labelsHidden().padding(10)
                .onChange(of: tab) { _, t in m.mode = ["Crop": "crop", "Masks": "mask", "Retouch": "retouch"][t] ?? "normal" }
            Divider()
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    switch tab {
                    case "Crop": cropPanel
                    case "Masks": masksPanel
                    case "Retouch": retouchPanel
                    case "Presets": presetsPanel
                    case "Info": infoPanel
                    default: editPanel
                    }
                }.padding(14)
            }
        }
        .onAppear { tab = ["crop": "Crop", "mask": "Masks", "retouch": "Retouch"][m.mode] ?? "Edit" }
        .alert(prompt?.0 ?? "", isPresented: Binding(get: { prompt != nil }, set: { if !$0 { prompt = nil } })) {
            TextField("Name", text: $promptText)
            Button("Save") { let t = promptText.trimmingCharacters(in: .whitespaces); if !t.isEmpty { prompt?.2(t) }; prompt = nil }
            Button("Cancel", role: .cancel) { prompt = nil }
        }
        .fileImporter(isPresented: Binding(get: { importing != nil }, set: { if !$0 { importing = nil } }),
                      allowedContentTypes: importing ?? [.data]) { r in
            guard case .success(let u) = r else { return }
            _ = u.startAccessingSecurityScopedResource()
            if u.pathExtension.lowercased() == "cube" { m.importLUT(u) } else { m.importPreset(u) }
        }
    }

    private func ask(_ t: String, _ d: String, _ done: @escaping (String) -> Void) { promptText = d; prompt = (t, d, done) }

    private func bind(_ k: String) -> Binding<Float> { Binding(get: { m.edit[k] }, set: { m.edit[k] = $0 }) }
    private func slider(_ k: String, _ t: String? = nil, range: ClosedRange<Float> = -1...1, format: ((Float) -> String)? = nil) -> some View {
        SliderRow(title: t ?? title(k), value: bind(k), range: range, format: format ?? { String(format: "%+d", Int(($0 * 100).rounded())) }, onBegin: m.checkpoint)
    }
    private func header(_ s: String) -> some View { Text(s.uppercased()).font(.caption.weight(.semibold)).foregroundStyle(muted).padding(.top, 6) }
    private func sliders(_ section: String) -> some View {
        ForEach(SECTIONS.first { $0.0 == section }!.1, id: \.self) { slider($0) }
    }

    // MARK: Edit

    @ViewBuilder private var editPanel: some View {
        if let h = m.hist { HistogramView(data: h).frame(height: 70) }
        HStack {
            Button("Auto") { m.auto() }
            Spacer()
            Menu("Profile: \(m.edit.profile)") {
                ForEach(m.profiles, id: \.self) { p in Button(p) { m.apply { $0.profile = p } } }
                Divider()
                Button("Import .cube LUT…") { importing = [UTType(filenameExtension: "cube") ?? .data, .data] }
            }.fixedSize()
        }
        if m.edit.profile != "Color" {
            SliderRow(title: "Profile amount", value: Binding(get: { m.edit.profileAmount }, set: { m.edit.profileAmount = $0 }), range: 0...1,
                      format: { "\(Int(($0 * 100).rounded()))" }, onBegin: m.checkpoint)
        }
        header("Light"); sliders("light")
        header("Curve")
        Picker("", selection: $channel) { ForEach(0..<4) { Text(["RGB", "Red", "Green", "Blue"][$0]).tag($0) } }.pickerStyle(.segmented).labelsHidden()
        CurveEditor(channel: channel).frame(height: 220)
        Button("Reset curve") { m.apply { $0.curves[channel] = LINE } }.buttonStyle(.link)
        header("Color"); sliders("color")
        header("Color mix")
        Picker("", selection: $hue) { ForEach(HUES.indices, id: \.self) { Circle().fill(swatch($0)).frame(width: 12).tag($0) } }
            .pickerStyle(.segmented).labelsHidden().help(HUES[hue].capitalized)
        slider("\(HUES[hue])Hue", "Hue"); slider("\(HUES[hue])Sat", "Saturation"); slider("\(HUES[hue])Lum", "Luminance")
        header("Color grading")
        Picker("", selection: $range) { Text("Shadows").tag("shadow"); Text("Midtones").tag("mid"); Text("Highlights").tag("high") }.pickerStyle(.segmented).labelsHidden()
        slider("\(range)Hue", "Hue", range: 0...1) { "\(Int(($0 * 360).rounded()))°" }
        slider("\(range)Sat", "Saturation", range: 0...1) { "\(Int(($0 * 100).rounded()))" }
        slider("\(range)Lum", "Luminance")
        slider("blending"); slider("balance")
        header("Effects"); sliders("effects")
        header("Detail"); sliders("detail")
        header("Optics"); sliders("optics")
    }

    private func swatch(_ i: Int) -> Color { Color(hue: [0, 30, 55, 120, 180, 225, 270, 300][i] / 360.0, saturation: 0.8, brightness: 0.9) }

    // MARK: Crop

    @ViewBuilder private var cropPanel: some View {
        let aspects: [(String, Double)] = [("Free", 0), ("Original", -1), ("1:1", 1), ("4:5", 0.8), ("16:9", 16.0 / 9)]
        Picker("Aspect", selection: Binding(get: { aspects.first { a in a.1 == (m.aspect ?? 0) || (a.1 == -1 && m.aspect != nil && !aspects.contains { $0.1 == m.aspect }) }?.0 ?? "Free" },
                                             set: { n in setAspect(aspects.first { $0.0 == n }!.1) })) {
            ForEach(aspects, id: \.0) { Text($0.0).tag($0.0) }
        }.pickerStyle(.segmented)
        SliderRow(title: "Straighten", value: Binding(get: { m.edit["straighten"] }, set: { m.edit["straighten"] = $0; refit() }),
                  format: { String(format: "%+.1f°", Double($0) * 45) }, onBegin: m.checkpoint)
        SliderRow(title: "Vertical", value: Binding(get: { m.edit["vertical"] }, set: { m.edit["vertical"] = $0; refit() }), onBegin: m.checkpoint)
        SliderRow(title: "Horizontal", value: Binding(get: { m.edit["horizontal"] }, set: { m.edit["horizontal"] = $0; refit() }), onBegin: m.checkpoint)
        HStack {
            Button("Level") { guard let l = m.look else { return }; m.apply { $0["straighten"] = autoLevel(l.base) }; refit() }
                .help("Straighten from the photo's own lines")
            Button("Rotate") { m.apply { $0.rotateRight() }; m.aspect = m.aspect.map { 1 / $0 } }
            Button("Flip") { m.apply { $0.flip() } }
            Button("Reset") { m.apply { e in e.crop = CGRect(x: 0, y: 0, width: 1, height: 1); e.rot90 = 0; e.flipH = false
                for k in ["straighten", "vertical", "horizontal"] { e[k] = 0 } } }
        }
        Text("Drag the corners to crop, or inside the frame to move it.").font(.caption).foregroundStyle(muted)
    }

    private func refit() {
        guard let l = m.look else { return }
        let (ow, oh) = m.edit.oriented(l.w, l.h)
        let c = m.edit.shrinkToFit(m.edit.crop, ow, oh)
        if c != m.edit.crop { m.edit.crop = c }
    }

    /// Lock the crop to an aspect (0 = free, -1 = the photo's own), the largest centred frame that fits.
    private func setAspect(_ ratio: Double) {
        guard let l = m.look, ratio != 0 else { m.aspect = nil; return }
        let (ow, oh) = m.edit.oriented(l.w, l.h)
        let wide = m.edit.crop.width * ow >= m.edit.crop.height * oh
        let a = ratio < 0 ? ow / oh : wide ? max(ratio, 1 / ratio) : min(ratio, 1 / ratio) // 4:5 on a landscape crop is 5:4
        m.aspect = a
        var w = 1.0, h = ow / (a * oh)
        if h > 1 { h = 1; w = a * oh / ow }
        m.apply { $0.crop = $0.shrinkToFit(CGRect(x: 0.5 - w / 2, y: 0.5 - h / 2, width: w, height: h), ow, oh) }
    }

    // MARK: Masks

    private static let maskNames = ["linear": "Gradient", "radial": "Radial", "brush": "Brush", "color": "Color", "luma": "Light"]

    @ViewBuilder private var masksPanel: some View {
        HStack { ForEach(MASK_TYPES, id: \.self) { t in Button("+ \(Self.maskNames[t]!)") { addMask(t) }.controlSize(.small) } }
        if m.edit.masks.isEmpty {
            Text("Add a mask to adjust part of the photo. Up to \(MAX_MASKS).").font(.callout).foregroundStyle(muted)
        } else {
            Picker("", selection: $m.selMask) {
                ForEach(m.edit.masks.indices, id: \.self) { i in Text("\(i + 1) \(Self.maskNames[m.edit.masks[i].type]!)").tag(i) }
            }.pickerStyle(.segmented).labelsHidden()
            if m.edit.masks.indices.contains(m.selMask) {
                let i = m.selMask, mk = m.edit.masks[i]
                HStack {
                    Toggle("Invert", isOn: Binding(get: { mk.invert }, set: { v in if m.edit.masks.indices.contains(i) { m.apply { $0.masks[i].invert = v } } }))
                    Toggle("Overlay", isOn: $m.showOverlay)
                    Spacer()
                    Button("Delete", role: .destructive) {
                        m.selMask = -1 // controls bound to this mask go away first
                        m.apply { $0.masks.remove(at: i) }
                        m.selMask = min(i, m.edit.masks.count - 1)
                    }
                }
                switch mk.type {
                case "linear": Text("Full effect at the solid dot, fading out by the ring. Drag the middle to move both.").font(.caption).foregroundStyle(muted)
                case "radial":
                    Text("Drag the centre to move, the rings to resize.").font(.caption).foregroundStyle(muted)
                    maskUnit(i, "Feather", \.feather)
                case "brush":
                    Picker("", selection: $m.erase) { Text("Paint").tag(false); Text("Erase").tag(true) }.pickerStyle(.segmented).labelsHidden()
                    SliderRow(title: "Size", value: $m.brushSize, range: 0.01...0.2, format: { "\(Int(($0 * 1000).rounded()))" }, onBegin: {})
                    maskUnit(i, "Softness", \.feather)
                case "color":
                    Text("Click the photo on the colour to select.").font(.caption).foregroundStyle(muted)
                    SliderRow(title: "Range", value: maskBinding(i, 0.3) { $0.range } set: { $0.range = $1 }, range: 0.05...0.6,
                              format: { "\(Int(($0 * 100).rounded()))" }, onBegin: m.checkpoint)
                default:
                    SliderRow(title: "Darkest", value: maskBinding(i, 0) { $0.g[0] } set: { $0.g[0] = $1 }, range: 0...1, format: { "\(Int(($0 * 100).rounded()))" }, onBegin: m.checkpoint)
                    SliderRow(title: "Brightest", value: maskBinding(i, 1) { $0.g[1] } set: { $0.g[1] = $1 }, range: 0...1, format: { "\(Int(($0 * 100).rounded()))" }, onBegin: m.checkpoint)
                    maskUnit(i, "Feather", \.feather)
                }
                header("Adjust this mask")
                ForEach(MASK_ADJ.indices, id: \.self) { j in
                    SliderRow(title: title(MASK_ADJ[j]), value: maskBinding(i, 0) { $0.adj[j] } set: { $0.adj[j] = $1 }, onBegin: m.checkpoint)
                }
            }
        }
    }

    private func maskUnit(_ i: Int, _ t: String, _ kp: WritableKeyPath<Mask, Float>) -> some View {
        SliderRow(title: t, value: maskBinding(i, 0.5) { $0[keyPath: kp] } set: { $0[keyPath: kp] = $1 }, range: 0...1,
                  format: { "\(Int(($0 * 100).rounded()))" }, onBegin: m.checkpoint)
    }

    /// A control bound to mask `i` that stays safe if the mask is deleted while the control is still on screen.
    private func maskBinding(_ i: Int, _ fallback: Float, _ get: @escaping (Mask) -> Float, set: @escaping (inout Mask, Float) -> Void) -> Binding<Float> {
        Binding(get: { m.edit.masks.indices.contains(i) ? get(m.edit.masks[i]) : fallback },
                set: { v in if m.edit.masks.indices.contains(i) { set(&m.edit.masks[i], v) } })
    }

    private func addMask(_ t: String) {
        guard let l = m.look else { return }
        guard m.edit.masks.count < MAX_MASKS else { return m.say("Up to \(MAX_MASKS) masks") }
        var mk = Mask(type: t)
        switch t {
        case "linear": mk.g = [0.5, 0.15, 0.5, 0.5]                                  // the sky, usually
        case "radial": mk.g = [0.5, 0.5, 0.25, 0.25 * Float(l.w / l.h)]              // a circle on screen
        case "luma": mk.g = [0.6, 1, 0, 0]                                           // the highlights
        default: break
        }
        m.apply { $0.masks.append(mk) }
        m.selMask = m.edit.masks.count - 1
    }

    // MARK: Retouch

    @ViewBuilder private var retouchPanel: some View {
        Text("Click a spot to fix it. Drag the dashed circle to choose what it copies from.").font(.callout).foregroundStyle(muted)
        Picker("", selection: Binding(get: { m.heal }, set: { v in
            m.heal = v
            if m.edit.retouch.indices.contains(m.selSpot), m.edit.retouch[m.selSpot].heal != v { m.apply { $0.retouch[m.selSpot].heal = v } }
        })) { Text("Heal").tag(true); Text("Clone").tag(false) }.pickerStyle(.segmented).labelsHidden()
        SliderRow(title: "Size", value: Binding(get: { m.spotSize }, set: { v in
            m.spotSize = v
            if m.edit.retouch.indices.contains(m.selSpot) { m.edit.retouch[m.selSpot].r = v }
        }), range: 0.005...0.1, format: { "\(Int(($0 * 1000).rounded()))" }, onBegin: { if m.selSpot >= 0 { m.checkpoint() } })
        HStack {
            Button("Find spots") {
                guard let l = m.look else { return }
                m.say("Looking for dust and blemishes…")
                DispatchQueue.global(qos: .userInitiated).async {
                    let found = findSpots(l.base)
                    DispatchQueue.main.async {
                        if found.isEmpty { return m.say("No spots found") }
                        m.apply { $0.retouch += found }; m.say("Fixed \(found.count) spot\(found.count > 1 ? "s" : "")")
                    }
                }
            }
            Button("Delete spot") { let i = m.selSpot; m.selSpot = -1; m.apply { $0.retouch.remove(at: i) } }.disabled(!m.edit.retouch.indices.contains(m.selSpot))
            Button("Clear all") { m.apply { $0.retouch = [] }; m.selSpot = -1 }.disabled(m.edit.retouch.isEmpty)
        }
    }

    // MARK: Presets, versions

    @ViewBuilder private var presetsPanel: some View {
        HStack {
            Button("Save as preset") { ask("Preset name", "My look \(m.presets.count + 1)") { m.savePreset($0) } }
            Button("Copy edits") { m.copyEdits() }
            Button("Paste edits") { m.pasteEdits() }.disabled(m.clip == nil)
        }
        Button("Import Lightroom preset (.xmp)…") { importing = [UTType(filenameExtension: "xmp") ?? .data, .xml, .data] }.buttonStyle(.link)
        header("Presets")
        ForEach(m.presets) { p in
            Button(p.name) { m.applyPreset(p) }.buttonStyle(.plain).frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 3)
                .contextMenu { Button("Delete \"\(p.name)\"", role: .destructive) { m.deletePreset(p) } }
        }
        header("Versions")
        Button("Save version") { ask("Version name", "Version \(m.versions.count + 1)") { m.saveVersion($0) } }
        ForEach(m.versions) { v in
            Button(v.name) { m.restore(v) }.buttonStyle(.plain).frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 3)
                .contextMenu { Button("Delete \"\(v.name)\"", role: .destructive) { m.deleteVersion(v) } }
        }
    }

    // MARK: Info: rating, flag, label, albums

    @ViewBuilder private var infoPanel: some View {
        let r = m.row
        header("Rating")
        HStack(spacing: 4) {
            ForEach(1...5, id: \.self) { n in
                Button { m.mark("rating", r?.rating == n ? 0 : n) } label: {
                    Image(systemName: (r?.rating ?? 0) >= n ? "star.fill" : "star").foregroundStyle((r?.rating ?? 0) >= n ? accent : muted)
                }.buttonStyle(.plain).accessibilityLabel("\(n) star\(n > 1 ? "s" : "")")
            }
        }.font(.title3)
        header("Flag")
        Picker("", selection: Binding(get: { r?.flag ?? 0 }, set: { m.mark("flag", $0) })) {
            Text("Unflagged").tag(0); Text("Pick").tag(1); Text("Reject").tag(-1)
        }.pickerStyle(.segmented).labelsHidden()
        header("Label")
        Picker("", selection: Binding(get: { r?.label ?? "" }, set: { m.mark("label", $0.isEmpty ? nil : $0) })) {
            ForEach(LABELS, id: \.self) { l in Text(l?.capitalized ?? "None").tag(l ?? "") }
        }.pickerStyle(.segmented).labelsHidden()
        header("Albums")
        if let r {
            let mine = m.albumKeys.filter { $0.value.contains(m.current?.key ?? "") }.keys
            ForEach(m.albums) { a in Toggle(a.name, isOn: Binding(get: { mine.contains(a.id) }, set: { m.setAlbum(a, $0) })) }
            Button("New album") { ask("Album name", "Album \(m.albums.count + 1)") { m.newAlbum($0) } }.buttonStyle(.link)
            Text("Keys: 0–5 rate · P pick · X reject · U unflag · \\ before/after · ←/→ previous/next").font(.caption).foregroundStyle(muted).padding(.top, 8)
            let _ = r
        }
    }
}

struct HistogramView: View {
    let data: [[Int]]
    var body: some View {
        Canvas { ctx, size in
            let top = Double(data.map { ch in (1...254).map { ch[$0] }.max() ?? 1 }.max() ?? 1)
            for (i, ch) in data.enumerated() {
                var p = Path(); p.move(to: CGPoint(x: 0, y: size.height))
                for b in 0..<256 { p.addLine(to: CGPoint(x: Double(b) / 255 * size.width, y: size.height - min(1, Double(ch[b]) / max(top, 1)) * size.height)) }
                p.addLine(to: CGPoint(x: size.width, y: size.height)); p.closeSubpath()
                ctx.fill(p, with: .color(Tokens.color(["label-red", "label-green", "label-blue"][i]).opacity(0.45)))
            }
        }.accessibilityHidden(true)
    }
}

/// Tone curve: click empty space to add a point, drag to move, double-click a point to remove it.
struct CurveEditor: View {
    @EnvironmentObject var m: AppModel
    let channel: Int
    @State private var drag = -1
    @State private var started = false
    @State private var lastTap = Date.distantPast
    @State private var lastIdx = -1

    var body: some View {
        GeometryReader { geo in
            let s = geo.size, pad = 8.0
            let sx = { (x: Double) in pad + x * (s.width - 2 * pad) }, sy = { (y: Double) in s.height - pad - y * (s.height - 2 * pad) }
            let pts = m.edit.curves[channel]
            Canvas { ctx, _ in
                for i in 0...4 {
                    let t = Double(i) / 4
                    ctx.stroke(Path { $0.move(to: CGPoint(x: sx(t), y: sy(0))); $0.addLine(to: CGPoint(x: sx(t), y: sy(1))) }, with: .color(Tokens.color("border")))
                    ctx.stroke(Path { $0.move(to: CGPoint(x: sx(0), y: sy(t))); $0.addLine(to: CGPoint(x: sx(1), y: sy(t))) }, with: .color(Tokens.color("border")))
                }
                let col = [Color.white, Tokens.color("label-red"), Tokens.color("label-green"), Tokens.color("label-blue")][channel]
                let f = curveFn(pts)
                ctx.stroke(Path { p in p.move(to: CGPoint(x: sx(0), y: sy(f(0)))); for i in 1...64 { p.addLine(to: CGPoint(x: sx(Double(i) / 64), y: sy(f(Double(i) / 64)))) } },
                           with: .color(col), lineWidth: 2)
                for p in pts { ctx.fill(Path(ellipseIn: CGRect(x: sx(p.x) - 5, y: sy(p.y) - 5, width: 10, height: 10)), with: .color(col)) }
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { g in
                let x = min(1, max(0, (g.location.x - pad) / (s.width - 2 * pad))), y = min(1, max(0, (s.height - pad - g.location.y) / (s.height - 2 * pad)))
                var p = m.edit.curves[channel]
                if drag < 0 && g.translation == .zero {
                    started = false
                    drag = p.indices.min { hypot(sx(p[$0].x) - g.location.x, sy(p[$0].y) - g.location.y) < hypot(sx(p[$1].x) - g.location.x, sy(p[$1].y) - g.location.y) }
                        .flatMap { hypot(sx(p[$0].x) - g.location.x, sy(p[$0].y) - g.location.y) < 14 ? $0 : nil } ?? -1
                    if drag > 0 && drag < p.count - 1 && drag == lastIdx && Date().timeIntervalSince(lastTap) < 0.3 { // double-click removes
                        p.remove(at: drag); set(p); drag = -1; lastIdx = -1; return
                    }
                    lastTap = Date(); lastIdx = drag
                    if drag < 0, let i = p.firstIndex(where: { $0.x > x }), i > 0 { // add a point on the curve
                        p.insert(CGPoint(x: x, y: curveFn(p)(x)), at: i); drag = i; set(p)
                    }
                    return
                }
                guard drag >= 0 else { return }
                let nx = drag == 0 ? 0 : drag == p.count - 1 ? 1 : min(p[drag + 1].x - 0.01, max(p[drag - 1].x + 0.01, x))
                p[drag] = CGPoint(x: nx, y: y); set(p)
            }.onEnded { _ in drag = -1; started = false })
            .accessibilityLabel("Tone curve. Use Reset curve to undo all points.")
        }
    }

    private func set(_ p: [CGPoint]) {
        if !started { started = true; m.checkpoint() } // one undo step per drag
        m.edit.curves[channel] = p
    }
}

// MARK: export

struct ExportSheet: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var o = ExportOptions.saved
    @State private var pickFolder = false

    var body: some View {
        Form {
            Picker("Format", selection: $o.format) { ForEach(["JPEG", "HEIC", "PNG", "TIFF"], id: \.self) { Text($0) } }.pickerStyle(.segmented)
            Picker("Long edge", selection: $o.long) {
                Text("Full size").tag(0); Text("4096 px").tag(4096); Text("2048 px").tag(2048); Text("1080 px").tag(1080)
            }.pickerStyle(.segmented)
            HStack {
                Slider(value: Binding(get: { Double(o.quality) }, set: { o.quality = Int($0) }), in: 50...100) { Text("Quality") }
                Text("\(o.quality)").monospacedDigit().frame(width: 32)
            }.disabled(o.format == "PNG" || o.format == "TIFF")
            Toggle("Keep date, camera and lens info", isOn: $o.meta)
            Toggle("Keep location", isOn: $o.location).disabled(!o.meta)
            TextField("Watermark", text: $o.watermark, prompt: Text("Optional text, bottom right"))
            HStack {
                Text("Save to"); Text(URL(fileURLWithPath: o.folder).lastPathComponent).foregroundStyle(muted)
                Spacer(); Button("Choose…") { pickFolder = true }
            }
        }
        .formStyle(.grouped).frame(width: 480)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            ToolbarItem(placement: .confirmationAction) {
                Button(m.current == nil ? "Export \(m.selection.count)" : "Export") { m.export(o); dismiss() }
            }
        }
        .fileImporter(isPresented: $pickFolder, allowedContentTypes: [.folder]) { r in if case .success(let u) = r { o.folder = u.path } }
    }
}
