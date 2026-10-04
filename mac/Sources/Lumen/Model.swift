import Foundation
import CoreGraphics

// The edit document, the same JSON the Android app stores (replica/architecture.md). Slider values are -1...1, 0 = untouched.
let HUES = ["red", "orange", "yellow", "green", "aqua", "blue", "purple", "magenta"]
let SECTIONS: [(String, [String])] = [
    ("light", ["exposure", "contrast", "highlights", "shadows", "whites", "blacks"]),
    ("color", ["temp", "tint", "vibrance", "saturation"]),
    ("mixer", HUES.flatMap { ["\($0)Hue", "\($0)Sat", "\($0)Lum"] }),
    ("grading", ["shadowHue", "shadowSat", "shadowLum", "midHue", "midSat", "midLum",
                 "highHue", "highSat", "highLum", "blending", "balance"]), // hue and sat are 0...1, the rest -1...1
    ("effects", ["texture", "clarity", "dehaze", "vignette", "grain", "grainSize"]),
    ("detail", ["sharpen", "noise", "colorNoise"]),
    ("optics", ["distortion", "chroma", "lensVignette"]),
    ("geometry", ["straighten", "vertical", "horizontal"]), // straighten ±1 = ±45°
]
let SLIDERS = SECTIONS.flatMap { $0.1 }
let SCALARS = SECTIONS.filter { $0.0 != "mixer" && $0.0 != "geometry" }.flatMap { $0.1 }
let CHANNELS = ["rgb", "r", "g", "b"]
let LINE: [CGPoint] = [CGPoint(x: 0, y: 0), CGPoint(x: 1, y: 1)]

let MAX_MASKS = 4 // shader slots, same as Android
// Limits for anything read back from JSON (the database, presets, paste), the same as Android's.
let MAX_CURVE_POINTS = 64
let MAX_MASK_POINTS = 20000 // x,y pairs across all strokes of one brush mask
let MAX_SPOTS = 500
let MAX_PRESET_JSON = 64 * 1024
private let UNIT_KEYS: Set<String> = ["shadowHue", "shadowSat", "midHue", "midSat", "highHue", "highSat"] // 0...1, the rest -1...1
func sliderRange(_ k: String) -> ClosedRange<Float> { UNIT_KEYS.contains(k) ? 0...1 : -1...1 }
extension Comparable { func clamped(_ r: ClosedRange<Self>) -> Self { min(r.upperBound, max(r.lowerBound, self)) } }
/// A finite Float from a JSON value, or nil (strings, infinities and NaN are dropped).
func fin(_ v: Any?) -> Float? { (v as? NSNumber).map { Float(truncating: $0) }.flatMap { $0.isFinite ? $0 : nil } } // JSON 0/1 also bridge as Bool: keep them
private func floats(_ v: Any?) -> [Float]? {
    guard let a = v as? [Any] else { return nil }
    let f = a.compactMap(fin)
    return f.count == a.count ? f : nil
}

/// Points sorted, inside 0...1, distinct x, at most MAX_CURVE_POINTS; nil if fewer than 2 survive.
func cleanCurve(_ pts: [CGPoint]) -> [CGPoint]? {
    var seen = Set<Double>()
    let c = pts.filter { $0.x.isFinite && $0.y.isFinite }.map { CGPoint(x: $0.x.clamped(0...1), y: $0.y.clamped(0...1)) }
        .sorted { $0.x < $1.x }.filter { seen.insert(Double($0.x)).inserted }.prefix(MAX_CURVE_POINTS)
    return c.count >= 2 ? Array(c) : nil
}
let MASK_TYPES = ["linear", "radial", "brush", "color", "luma"]
let MASK_ADJ = ["exposure", "contrast", "highlights", "shadows", "temp", "tint", "saturation", "clarity"]

struct Stroke: Hashable { var pts: [Float]; var size: Float; var erase: Bool } // pts x,y pairs; size = fraction of the long edge

/// g by type: linear (ax, ay, bx, by) full effect at a, none at b · radial (cx, cy, rx, ry) · color (r, g, b, picked>0) · luma (low, high).
/// Coordinates are 0...1 of the source photo, top-left origin.
struct Mask: Equatable {
    var type: String
    var g: [Float] = [0, 0, 0, 0]
    var feather: Float = 0.5
    var invert = false
    var range: Float = 0.3
    var adj = [Float](repeating: 0, count: MASK_ADJ.count)
    var strokes: [Stroke] = []

    func json() -> [String: Any] {
        ["type": type, "g": g.map(Double.init), "feather": Double(feather), "invert": invert, "range": Double(range),
         "adjust": Dictionary(uniqueKeysWithValues: MASK_ADJ.enumerated().map { ($1, Double(adj[$0])) }),
         "strokes": strokes.map { ["size": Double($0.size), "erase": $0.erase, "p": $0.pts.map(Double.init)] }]
    }

    /// A mask from JSON, or nil if it isn't one. Every number is checked and clamped.
    static func from(_ o: [String: Any]) -> Mask? {
        guard let t = o["type"] as? String, MASK_TYPES.contains(t) else { return nil }
        var m = Mask(type: t)
        if let a = floats(o["g"]), a.count == 4 { m.g = a.map { $0.clamped(-2...2) } }
        if t == "radial" { m.g[2] = max(0.01, m.g[2]); m.g[3] = max(0.01, m.g[3]) }
        m.feather = fin(o["feather"])?.clamped(0...1) ?? 0.5
        m.invert = o["invert"] as? Bool ?? false
        m.range = fin(o["range"])?.clamped(0.05...0.6) ?? 0.3
        if let a = o["adjust"] as? [String: Any] { m.adj = MASK_ADJ.map { fin(a[$0])?.clamped(-1...1) ?? 0 } }
        var budget = MAX_MASK_POINTS * 2
        m.strokes = (o["strokes"] as? [[String: Any]] ?? []).compactMap { s in
            guard let p = floats(s["p"]) else { return nil }
            let n = min(p.count - p.count % 2, budget)
            guard n >= 2 else { return nil }
            budget -= n
            return Stroke(pts: p.prefix(n).map { $0.clamped(-1...2) }, size: fin(s["size"])?.clamped(0.001...0.5) ?? 0.05, erase: s["erase"] as? Bool ?? false)
        }
        return m
    }
}

/// Heal / clone spot: copy a circle from (sx, sy) onto (dx, dy). 0...1 of the source, r of its long edge.
struct Spot: Equatable { var dx, dy, sx, sy, r: Float; var heal: Bool }

struct Edit: Equatable {
    var v: [String: Float] = [:]
    var curves: [[CGPoint]] = Array(repeating: LINE, count: 4) // rgb, r, g, b
    var crop = CGRect(x: 0, y: 0, width: 1, height: 1)        // normalised to the oriented frame, top-left origin
    var rot90 = 0
    var flipH = false
    var masks: [Mask] = []
    var retouch: [Spot] = []
    var profile = "Color"
    var profileAmount: Float = 1

    subscript(_ k: String) -> Float {
        get { v[k] ?? 0 }
        set { v[k] = newValue == 0 ? nil : newValue }
    }

    var isBlank: Bool { self == Edit() }

    // MARK: JSON (same keys as Android's Edit.toJson / merge)

    /// `whole = false`: just the look (presets, copy/paste), no crop, masks or spots, which belong to one photo.
    func json(whole: Bool = true) -> String {
        var o: [String: Any] = ["v": 1]
        for (s, names) in SECTIONS where s != "geometry" {
            o[s] = Dictionary(uniqueKeysWithValues: names.map { ($0, Double(self[$0])) })
        }
        o["profile"] = ["name": profile, "amount": Double(profileAmount)]
        o["curve"] = Dictionary(uniqueKeysWithValues: CHANNELS.enumerated().map { i, ch in (ch, curves[i].map { [Double($0.x), Double($0.y)] }) })
        if whole {
            o["geometry"] = ["straighten": Double(self["straighten"]), "vertical": Double(self["vertical"]), "horizontal": Double(self["horizontal"]),
                             "crop": [crop.minX, crop.minY, crop.maxX, crop.maxY].map(Double.init), "rot90": rot90, "flipH": flipH]
            o["masks"] = masks.map { $0.json() }
            o["retouch"] = retouch.map { [$0.dx, $0.dy, $0.sx, $0.sy, $0.r, $0.heal ? 1 : 0].map(Double.init) }
        }
        let data = try! JSONSerialization.data(withJSONObject: o, options: [.sortedKeys])
        return String(decoding: data, as: UTF8.self)
    }

    /// Overlay whatever keys `json` has (a preset sets only what it names). Crop, masks and spots only when `whole`.
    @discardableResult
    mutating func merge(_ json: String, whole: Bool = true) -> Edit {
        guard let o = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any] else { return self } // not JSON: unchanged
        for (s, names) in SECTIONS where s != "geometry" {
            guard let sec = o[s] as? [String: Any] else { continue }
            for n in names { if let x = fin(sec[n]) { self[n] = x.clamped(sliderRange(n)) } }
        }
        if let pr = o["profile"] as? [String: Any] {
            if let n = pr["name"] as? String, Profiles.names().contains(n) { profile = n } // only profiles that exist: no paths
            profileAmount = fin(pr["amount"])?.clamped(0...1) ?? 1
        }
        if let c = o["curve"] as? [String: Any] {
            curves = CHANNELS.enumerated().map { i, ch in
                guard let a = c[ch] as? [Any] else { return curves[i] }
                // curves keep full double precision, like the model (Float here would change them on every save)
                let pts = a.compactMap { $0 as? [Any] }.filter { $0.count == 2 }.compactMap { p -> CGPoint? in
                    guard let x = (p[0] as? NSNumber)?.doubleValue, let y = (p[1] as? NSNumber)?.doubleValue else { return nil }
                    return CGPoint(x: x, y: y)
                }
                return cleanCurve(pts) ?? curves[i]
            }
        }
        guard whole else { return self }
        if let a = o["masks"] as? [[String: Any]] { masks = Array(a.compactMap(Mask.from).prefix(MAX_MASKS)) }
        if let a = o["retouch"] as? [Any] {
            retouch = a.compactMap { floats($0) }.filter { $0.count == 6 }.prefix(MAX_SPOTS).map {
                Spot(dx: $0[0].clamped(0...1), dy: $0[1].clamped(0...1), sx: $0[2].clamped(0...1), sy: $0[3].clamped(0...1), r: $0[4].clamped(0.001...0.2), heal: $0[5] > 0.5)
            }
        }
        if let g = o["geometry"] as? [String: Any] {
            for k in ["straighten", "vertical", "horizontal"] { if let x = fin(g[k]) { self[k] = x.clamped(-1...1) } }
            // crop in doubles, like the model: no rounding on every save
            if let c = (g["crop"] as? [Any])?.compactMap({ ($0 as? NSNumber)?.doubleValue }).filter(\.isFinite), c.count == 4 {
                let l = c[0].clamped(0...0.95), t = c[1].clamped(0...0.95)
                crop = CGRect(x: l, y: t, width: c[2].clamped((l + 0.05)...1) - l, height: c[3].clamped((t + 0.05)...1) - t)
            }
            if let r = fin(g["rot90"]) { rot90 = ((Int(r) % 4) + 4) % 4 }
            flipH = g["flipH"] as? Bool ?? flipH
        }
        return self
    }

    // MARK: geometry: rotate 90s, then mirror, then keystone + straighten, then crop. Top-left pixel coordinates.

    func oriented(_ w: Double, _ h: Double) -> (Double, Double) { rot90 % 2 == 1 ? (h, w) : (w, h) }
    var degrees: Double { Double(self["straighten"]) * 45 }

    /// Oriented frame → straightened frame: perspective (keystone) first, then the straighten rotation.
    func shape(_ ow: Double, _ oh: Double) -> Mat3 {
        let kv = 0.25 * Double(self["vertical"]), kh = 0.25 * Double(self["horizontal"])
        let w = ow, h = oh
        // vertical > 0 widens the top (fixes buildings leaning back); horizontal > 0 widens the left
        let dst = [CGPoint(x: -max(kv, 0) * w / 2, y: -max(kh, 0) * h / 2), CGPoint(x: w + max(kv, 0) * w / 2, y: -max(-kh, 0) * h / 2),
                   CGPoint(x: w + max(-kv, 0) * w / 2, y: h + max(-kh, 0) * h / 2), CGPoint(x: -max(-kv, 0) * w / 2, y: h + max(kh, 0) * h / 2)]
        let src = [CGPoint(x: 0, y: 0), CGPoint(x: w, y: 0), CGPoint(x: w, y: h), CGPoint(x: 0, y: h)]
        return Mat3.poly(src, dst).then(.rotate(degrees, w / 2, h / 2))
    }

    /// Source pixels → output pixels. Without crop: the whole straightened frame (crop mode).
    func matrix(_ w: Double, _ h: Double, withCrop: Bool = true) -> Mat3 {
        let (ow, oh) = oriented(w, h)
        var m = Mat3.rotate(90 * Double(rot90), w / 2, h / 2).then(.translate((ow - w) / 2, (oh - h) / 2))
        if flipH { m = m.then(.scale(-1, 1, ow / 2, oh / 2)) }
        m = m.then(shape(ow, oh))
        if withCrop { m = m.then(.translate(-crop.minX * ow, -crop.minY * oh)) }
        return m
    }

    func outSize(_ w: Double, _ h: Double) -> (Int, Int) {
        let (ow, oh) = oriented(w, h)
        return (max(1, Int((crop.width * ow).rounded())), max(1, Int((crop.height * oh).rounded())))
    }

    /// Is this crop (normalised, in an ow×oh oriented frame) fully inside the straightened, keystoned photo?
    func fits(_ r: CGRect, _ ow: Double, _ oh: Double) -> Bool {
        let inv = shape(ow, oh).inverse
        let e = 1e-3 * max(ow, oh)
        return [CGPoint(x: r.minX * ow, y: r.minY * oh), CGPoint(x: r.maxX * ow, y: r.minY * oh),
                CGPoint(x: r.maxX * ow, y: r.maxY * oh), CGPoint(x: r.minX * ow, y: r.maxY * oh)].allSatisfy {
            let p = inv.apply($0)
            return p.x >= -e && p.x <= ow + e && p.y >= -e && p.y <= oh + e
        }
    }

    /// Largest scale of `r` about its centre that fits. Straightening and keystone shrink the crop like this.
    func shrinkToFit(_ r: CGRect, _ ow: Double, _ oh: Double) -> CGRect {
        if fits(r, ow, oh) { return r }
        var lo = 0.0, hi = 1.0
        for _ in 0..<20 { let m = (lo + hi) / 2; if fits(Edit.scaled(r, m), ow, oh) { lo = m } else { hi = m } }
        return Edit.scaled(r, lo)
    }

    static func scaled(_ r: CGRect, _ s: Double) -> CGRect {
        CGRect(x: r.midX - r.width * s / 2, y: r.midY - r.height * s / 2, width: r.width * s, height: r.height * s)
    }

    mutating func rotateRight() {
        rot90 = (rot90 + (flipH ? 3 : 1)) % 4 // mirrored: turning the picture right is a left turn of the source
        crop = CGRect(x: 1 - crop.maxY, y: crop.minX, width: crop.height, height: crop.width)
    }

    mutating func flip() {
        flipH.toggle()
        crop = CGRect(x: 1 - crop.maxX, y: crop.minY, width: crop.width, height: crop.height)
        self["straighten"] = -self["straighten"]
    }
}

/// 3×3 projective matrix, row-major, column vectors (p' = M · p), like android.graphics.Matrix.
struct Mat3: Equatable {
    var m: [Double]
    static let identity = Mat3(m: [1, 0, 0, 0, 1, 0, 0, 0, 1])

    static func * (a: Mat3, b: Mat3) -> Mat3 {
        var r = [Double](repeating: 0, count: 9)
        for i in 0..<3 { for j in 0..<3 { for k in 0..<3 { r[i * 3 + j] += a.m[i * 3 + k] * b.m[k * 3 + j] } } }
        return Mat3(m: r)
    }
    /// Apply self, then `next` (Android's postConcat).
    func then(_ next: Mat3) -> Mat3 { next * self }

    static func translate(_ x: Double, _ y: Double) -> Mat3 { Mat3(m: [1, 0, x, 0, 1, y, 0, 0, 1]) }
    static func scale(_ sx: Double, _ sy: Double, _ px: Double = 0, _ py: Double = 0) -> Mat3 {
        Mat3(m: [sx, 0, px - sx * px, 0, sy, py - sy * py, 0, 0, 1])
    }
    /// Degrees, about (px, py), y pointing down: positive turns clockwise on screen.
    static func rotate(_ deg: Double, _ px: Double, _ py: Double) -> Mat3 {
        let r = deg * .pi / 180, c = cos(r), s = sin(r)
        return Mat3(m: [c, -s, px - c * px + s * py, s, c, py - s * px - c * py, 0, 0, 1])
    }

    func apply(_ p: CGPoint) -> CGPoint {
        let x = m[0] * p.x + m[1] * p.y + m[2], y = m[3] * p.x + m[4] * p.y + m[5], w = m[6] * p.x + m[7] * p.y + m[8]
        return CGPoint(x: x / w, y: y / w)
    }

    var inverse: Mat3 {
        let a = m
        let c00 = a[4] * a[8] - a[5] * a[7], c01 = a[5] * a[6] - a[3] * a[8], c02 = a[3] * a[7] - a[4] * a[6]
        let det = a[0] * c00 + a[1] * c01 + a[2] * c02
        let inv = [c00, a[2] * a[7] - a[1] * a[8], a[1] * a[5] - a[2] * a[4],
                   c01, a[0] * a[8] - a[2] * a[6], a[2] * a[3] - a[0] * a[5],
                   c02, a[1] * a[6] - a[0] * a[7], a[0] * a[4] - a[1] * a[3]]
        return Mat3(m: inv.map { $0 / det })
    }

    /// The projective map taking 4 points onto 4 points (Android's setPolyToPoly with 4 points).
    static func poly(_ s: [CGPoint], _ d: [CGPoint]) -> Mat3 {
        var a = [[Double]]()
        for i in 0..<4 {
            let (x, y, u, v) = (Double(s[i].x), Double(s[i].y), Double(d[i].x), Double(d[i].y))
            a.append([x, y, 1, 0, 0, 0, -u * x, -u * y, u])
            a.append([0, 0, 0, x, y, 1, -v * x, -v * y, v])
        }
        for c in 0..<8 { // Gauss–Jordan with partial pivoting
            let p = (c..<8).max { abs(a[$0][c]) < abs(a[$1][c]) }!
            a.swapAt(c, p)
            let d = a[c][c]
            for k in c...8 { a[c][k] /= d }
            for r in 0..<8 where r != c {
                let f = a[r][c]
                if f != 0 { for k in c...8 { a[r][k] -= f * a[c][k] } }
            }
        }
        return Mat3(m: (0..<8).map { a[$0][8] } + [1])
    }
}

// MARK: tone curve: monotone cubic (Fritsch–Carlson), so dragging a point never makes the curve overshoot

func curveFn(_ points: [CGPoint]) -> (Double) -> Double {
    let p = points.sorted { $0.x < $1.x }
    let n = p.count
    let m = (0..<(n - 1)).map { (p[$0 + 1].y - p[$0].y) / max(p[$0 + 1].x - p[$0].x, 1e-6) }
    var t = (0..<n).map { i -> Double in i == 0 ? m[0] : i == n - 1 ? m[n - 2] : (m[i - 1] * m[i] <= 0 ? 0 : (m[i - 1] + m[i]) / 2) }
    for i in 0..<(n - 1) {
        if m[i] == 0 { t[i] = 0; t[i + 1] = 0; continue }
        let a = t[i] / m[i], b = t[i + 1] / m[i], s = a * a + b * b
        if s > 9 { let k = 3 / s.squareRoot(); t[i] = k * a * m[i]; t[i + 1] = k * b * m[i] }
    }
    return { x in
        if x <= p[0].x { return p[0].y }
        if x >= p[n - 1].x { return p[n - 1].y }
        let i = (0..<(n - 1)).first { x <= p[$0 + 1].x }!
        let h = p[i + 1].x - p[i].x, u = (x - p[i].x) / h
        let h00 = (1 + 2 * u) * (1 - u) * (1 - u), h10 = u * (1 - u) * (1 - u), h01 = u * u * (3 - 2 * u), h11 = u * u * (u - 1)
        return min(1, max(0, h00 * p[i].y + h10 * h * t[i] + h01 * p[i + 1].y + h11 * h * t[i + 1]))
    }
}
