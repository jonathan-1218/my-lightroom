import Foundation
import CoreGraphics

/// Luminance (0...1) of a small copy, long edge at most `max`. Same weights as the shader.
private func luma(_ cg: CGImage, _ maxLong: Int) -> ([Float], Int, Int) {
    let k = min(1, Double(maxLong) / Double(max(cg.width, cg.height)))
    let w = max(1, Int(Double(cg.width) * k)), h = max(1, Int(Double(cg.height) * k))
    let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4, space: sRGB,
                        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    ctx.interpolationQuality = .medium
    ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
    let p = ctx.data!.bindMemory(to: UInt8.self, capacity: w * h * 4)
    var l = [Float](repeating: 0, count: w * h)
    for i in 0..<(w * h) { l[i] = (0.2126 * Float(p[i * 4]) + 0.7152 * Float(p[i * 4 + 1]) + 0.0722 * Float(p[i * 4 + 2])) / 255 }
    return (l, w, h)
}

/// Auto: slider values from the photo's brightness spread (port of Android's autoTone).
func autoTone(_ cg: CGImage) -> [String: Float] {
    let (l, _, _) = luma(cg, 256)
    let sorted = l.sorted()
    func pct(_ p: Float) -> Float { sorted[Int((Float(sorted.count - 1) * p).rounded())] }
    let lo = pct(0.005), hi = pct(0.995)
    let mean = max(0.02, l.reduce(0, +) / Float(l.count)) // mean, not median: a mostly-black frame shouldn't be dragged to grey
    // ponytail: one global target; a scene-aware target (night, snow) is the upgrade if Auto feels flat
    let ev = min(1.2, max(-1.2, 0.6 * log2(pow(0.42, 2.2) / pow(mean, 2.2)))) // go 60% of the way: Auto is a starting point
    let gain = pow(2, ev / 2.2)
    let clipped = Float(l.filter { $0 > 0.97 }.count) / Float(l.count)
    let dark = Float(l.filter { $0 < 0.05 }.count) / Float(l.count)
    func c(_ x: Float, _ a: Float = -1, _ b: Float = 1) -> Float { min(b, max(a, x)) }
    return ["exposure": ev / 2, "whites": c((1 - min(hi * gain, 1.2) / 0.98) / 0.15, -0.5, 0.5), "blacks": c(-((lo * gain) - 0.01) / 0.15, -0.5, 0.5),
            "contrast": c(0.3 * (0.8 - (hi - lo)), -0.3, 0.3), "highlights": clipped > 0.02 ? -0.3 : 0, "shadows": dark > 0.1 ? 0.3 : 0,
            "vibrance": 0.15]
}

/// Level: the straighten value that makes the photo's dominant near-horizontal or near-vertical lines straight.
/// For each candidate angle, edge points are projected across the lines; the right angle stacks them into the sharpest peaks.
func autoLevel(_ cg: CGImage) -> Float {
    let (l, w, h) = luma(cg, 400)
    var hx = [Float](), hy = [Float](), vx = [Float](), vy = [Float]()
    for y in 1..<(h - 1) { for x in 1..<(w - 1) {
        let gx = l[y * w + x + 1] - l[y * w + x - 1], gy = l[(y + 1) * w + x] - l[(y - 1) * w + x]
        if hypot(gx, gy) < 0.15 { continue }
        if abs(gy) > 2 * abs(gx) { hx.append(Float(x)); hy.append(Float(y)) } else if abs(gx) > 2 * abs(gy) { vx.append(Float(x)); vy.append(Float(y)) }
    } }
    if hx.count + vx.count < 50 { return 0 } // nothing line-like to level on
    let span = (w + h) * 2
    func score(_ xs: [Float], _ ys: [Float], _ nx: Float, _ ny: Float) -> Double {
        var bins = [Int](repeating: 0, count: span)
        for i in xs.indices { bins[min(span - 1, max(0, Int(xs[i] * nx + ys[i] * ny) + span / 2))] += 1 }
        return bins.reduce(0) { $0 + Double($1) * Double($1) }
    }
    var best: Float = 0, bestScore = -1.0
    for t in stride(from: Float(-20), through: 20, by: 0.25) {
        let r = t * .pi / 180, c = cos(r), s = sin(r)
        let sc = score(hx, hy, -s, c) + score(vx, vy, c, s)
        if sc > bestScore { bestScore = sc; best = t }
    }
    return min(1, max(-1, -best / 45)) // straighten turns the opposite way
}

private func ringCost(_ l: [Float], _ w: Int, _ h: Int, _ dx: Float, _ dy: Float, _ sx: Float, _ sy: Float, _ r: Float) -> Float {
    var cost: Float = 0, n = 0
    for i in 0..<48 {
        let t = Float(i) / 48 * 2 * .pi
        for rr in [Float(1.1), 1.3] {
            let ox = cos(t) * r * rr, oy = sin(t) * r * rr
            let ax = Int((dx + ox).rounded()), ay = Int((dy + oy).rounded()), bx = Int((sx + ox).rounded()), by = Int((sy + oy).rounded())
            if !(0..<w).contains(ax) || !(0..<h).contains(ay) || !(0..<w).contains(bx) || !(0..<h).contains(by) { return .greatestFiniteMagnitude }
            let d = l[ay * w + ax] - l[by * w + bx]; cost += d * d; n += 1
        }
    }
    return cost / Float(n)
}

/// Source for a heal spot: the nearby circle whose surroundings best match the spot's (0...1 coordinates).
func bestSource(_ cg: CGImage, _ x: Float, _ y: Float, _ r: Float) -> (Float, Float)? {
    let (l, w, h) = luma(cg, 512)
    let rp = r * Float(max(w, h)), dx = x * Float(w), dy = y * Float(h)
    var best: (Float, Float)?, bestCost = Float.greatestFiniteMagnitude
    for dist in [Float(2.5), 3.5, 5] { for i in 0..<16 {
        let t = Float(i) / 16 * 2 * .pi
        let sx = dx + cos(t) * rp * dist, sy = dy + sin(t) * rp * dist
        if sx - rp < 0 || sy - rp < 0 || sx + rp >= Float(w) || sy + rp >= Float(h) { continue }
        let c = ringCost(l, w, h, dx, dy, sx, sy, rp)
        if c < bestCost { bestCost = c; best = (sx / Float(w), sy / Float(h)) }
    } }
    return best
}

/// Small dark specks on smooth areas (dust, blemishes), as heal spots with matched sources. At most `limit`.
func findSpots(_ cg: CGImage, limit: Int = 20) -> [Spot] {
    let (l, w, h) = luma(cg, 800)
    var ii = [Double](repeating: 0, count: (w + 1) * (h + 1)) // integral image for the local mean
    for y in 0..<h { var row = 0.0; for x in 0..<w { row += Double(l[y * w + x]); ii[(y + 1) * (w + 1) + x + 1] = ii[y * (w + 1) + x + 1] + row } }
    func box(_ x0: Int, _ y0: Int, _ x1: Int, _ y1: Int) -> Double { ii[y1 * (w + 1) + x1] - ii[y0 * (w + 1) + x1] - ii[y1 * (w + 1) + x0] + ii[y0 * (w + 1) + x0] }
    let R = 7
    var dark = [Bool](repeating: false, count: w * h)
    if w > 2 * R && h > 2 * R {
        for y in R..<(h - R) { for x in R..<(w - R) {
            let mean = box(x - R, y - R, x + R + 1, y + R + 1) / Double((2 * R + 1) * (2 * R + 1))
            dark[y * w + x] = Double(l[y * w + x]) < mean - 0.08
        } }
    }
    var seen = [Bool](repeating: false, count: w * h), spots = [Spot]()
    for start in dark.indices where dark[start] && !seen[start] {
        var blob = [Int](), stack = [start]; seen[start] = true
        while let i = stack.popLast(), blob.count < 200 {
            blob.append(i)
            for n in [i - 1, i + 1, i - w, i + w] where n >= 0 && n < dark.count && dark[n] && !seen[n] { seen[n] = true; stack.append(n) }
        }
        if !(2...60).contains(blob.count) { continue } // specks, not edges or shadows
        let cx = Float(blob.reduce(0) { $0 + $1 % w }) / Float(blob.count), cy = Float(blob.reduce(0) { $0 + $1 / w }) / Float(blob.count)
        let r = ((Float(blob.count) / .pi).squareRoot() * 2 + 1) / Float(max(w, h))
        guard let (sx, sy) = bestSource(cg, cx / Float(w), cy / Float(h), r) else { continue }
        spots.append(Spot(dx: cx / Float(w), dy: cy / Float(h), sx: sx, sy: sy, r: r, heal: true))
        if spots.count >= limit { break }
    }
    return spots
}

/// 256-bin histograms of red, green and blue.
func histogram(_ cg: CGImage) -> [[Int]] {
    let px = Pixels(cg)
    var h = [[Int]](repeating: [Int](repeating: 0, count: 256), count: 3)
    for i in 0..<(px.w * px.h) { for c in 0..<3 { h[c][Int(px.data[i * 4 + c])] += 1 } }
    return h
}

/// Reads a Lightroom / Camera Raw .xmp preset (the user's own file) into Lumen's edit JSON, only the keys it sets.
func xmpPreset(_ text: String, fallbackName: String) -> (String, String)? {
    func first(_ pattern: String) -> String? {
        guard let re = try? NSRegularExpression(pattern: pattern, options: [.dotMatchesLineSeparators]),
              let m = re.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)), let r = Range(m.range(at: 1), in: text) else { return nil }
        return String(text[r])
    }
    func num(_ k: String) -> Float? { (first("crs:\(k)=\"([-+0-9.]+)\"") ?? first("<crs:\(k)>([-+0-9.]+)</crs:\(k)>")).flatMap { Float($0) } }
    var e = Edit(), any = false
    func put(_ k: String, _ v: Float?) { if let v { e[k] = min(1, max(-1, v)); any = true } }
    put("exposure", num("Exposure2012").map { $0 / 2 })
    for (k, x) in [("contrast", "Contrast2012"), ("highlights", "Highlights2012"), ("shadows", "Shadows2012"), ("whites", "Whites2012"),
                   ("blacks", "Blacks2012"), ("vibrance", "Vibrance"), ("saturation", "Saturation"), ("clarity", "Clarity2012"),
                   ("texture", "Texture"), ("dehaze", "Dehaze"), ("vignette", "PostCropVignetteAmount"), ("temp", "IncrementalTemperature"),
                   ("tint", "IncrementalTint"), ("noise", "LuminanceSmoothing"), ("colorNoise", "ColorNoiseReduction"), ("grain", "GrainAmount"),
                   ("distortion", "LensManualDistortionAmount"), ("lensVignette", "VignetteAmount"), ("shadowLum", "ColorGradeShadowLum"),
                   ("midLum", "ColorGradeMidtoneLum"), ("highLum", "ColorGradeHighlightLum"), ("balance", "SplitToningBalance")] {
        put(k, num(x).map { $0 / 100 })
    }
    put("sharpen", num("Sharpness").map { $0 / 150 })
    for (k, x) in [("shadow", "Shadow"), ("mid", "Midtone"), ("high", "Highlight")] {
        put("\(k)Hue", num("ColorGrade\(x)Hue").map { $0 / 360 }); put("\(k)Sat", num("ColorGrade\(x)Sat").map { $0 / 100 })
    }
    if let b = num("ColorGradeBlending") { put("blending", b / 50 - 1) }
    let names = ["Red", "Orange", "Yellow", "Green", "Aqua", "Blue", "Purple", "Magenta"]
    for (i, hue) in HUES.enumerated() {
        put("\(hue)Hue", num("HueAdjustment\(names[i])").map { $0 / 100 })
        put("\(hue)Sat", num("SaturationAdjustment\(names[i])").map { $0 / 100 })
        put("\(hue)Lum", num("LuminanceAdjustment\(names[i])").map { $0 / 100 })
    }
    let curves = ["ToneCurvePV2012", "ToneCurvePV2012Red", "ToneCurvePV2012Green", "ToneCurvePV2012Blue"].map { tag -> [CGPoint]? in
        guard let body = first("<crs:\(tag)>(.*?)</crs:\(tag)>"), let re = try? NSRegularExpression(pattern: "<rdf:li>\\s*([0-9.]+)\\s*,\\s*([0-9.]+)\\s*</rdf:li>") else { return nil }
        let pts = re.matches(in: body, range: NSRange(body.startIndex..., in: body)).compactMap { m -> CGPoint? in
            guard let a = Range(m.range(at: 1), in: body), let b = Range(m.range(at: 2), in: body), let x = Double(body[a]), let y = Double(body[b]) else { return nil }
            return CGPoint(x: x / 255, y: y / 255)
        }
        return pts.count >= 2 ? pts : nil
    }
    if curves.contains(where: { $0 != nil }) { e.curves = curves.enumerated().map { $1 ?? e.curves[$0] }; any = true }
    guard any else { return nil }
    let name = first("<crs:Name>.*?<rdf:li[^>]*>(.*?)</rdf:li>")?.trimmingCharacters(in: .whitespaces) ?? first("crs:PresetName=\"([^\"]+)\"") ?? fallbackName
    // Only the keys the preset actually set, so applying it leaves everything else alone (like a built-in preset).
    let full = (try? JSONSerialization.jsonObject(with: Data(e.json(whole: false).utf8))) as? [String: Any] ?? [:]
    let base = (try? JSONSerialization.jsonObject(with: Data(Edit().json(whole: false).utf8))) as? [String: Any] ?? [:]
    var out: [String: Any] = ["v": 1]
    for (sec, a) in full {
        guard let a = a as? [String: Any], let b = base[sec] as? [String: Any] else { continue }
        let diff = a.filter { k, v in "\(v)" != "\(b[k] ?? "")" }
        if !diff.isEmpty { out[sec] = diff }
    }
    return (name, String(decoding: try! JSONSerialization.data(withJSONObject: out, options: [.sortedKeys]), as: UTF8.self))
}
