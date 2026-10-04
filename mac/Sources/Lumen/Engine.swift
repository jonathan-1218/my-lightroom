import Foundation
import CoreGraphics
import CoreImage
import ImageIO
import UniformTypeIdentifiers

/// Everything renders in gamma-encoded sRGB, like the Android shader, so the same edit looks the same on both.
let sRGB = CGColorSpace(name: CGColorSpace.sRGB)!
let ciContext = CIContext(options: [.workingColorSpace: sRGB, .outputColorSpace: sRGB, .workingFormat: CIFormat.RGBAh, .cacheIntermediates: true])

// MARK: the develop kernel: a line-for-line port of the Android AGSL shader (src/main/kotlin/dev/lumen/Develop.kt)

private let NR_TAPS: [(Double, Double)] = [(1, 0), (-1, 0), (0, 1), (0, -1), (0.7, 0.7), (-0.7, 0.7), (0.7, -0.7), (-0.7, -0.7)]
private let PACKS: [[String]] = stride(from: 0, to: SCALARS.count, by: 4).map { Array(SCALARS[$0..<min($0 + 4, SCALARS.count)]) }

private let KERNEL_SOURCE = """
float lum(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
vec3 hsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + 1e-10)), d / (q.x + 1e-10), q.x);
}
vec3 rgb(vec3 c) {
    vec3 p = abs(fract(c.xxx + vec3(1.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
    return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
}
float tri(float h, float l, float c, float r) {
    float t = (r - h) / (r - c);
    if (h < c) { t = (h - l) / (c - l); }
    return clamp(t, 0.0, 1.0);
}
float band(vec4 a, vec4 b, float h) {
    float hr = h;
    if (h > 180.0) { hr = h - 360.0; }
    return a.x * tri(hr, -60.0, 0.0, 30.0) + a.y * tri(h, 0.0, 30.0, 60.0) + a.z * tri(h, 30.0, 60.0, 120.0)
         + a.w * tri(h, 60.0, 120.0, 180.0) + b.x * tri(h, 120.0, 180.0, 240.0) + b.y * tri(h, 180.0, 240.0, 270.0)
         + b.z * tri(h, 240.0, 270.0, 300.0) + b.w * tri(h, 270.0, 300.0, 360.0);
}
float linearW(vec4 g, vec2 u) {
    vec2 d = g.zw - g.xy;
    return 1.0 - smoothstep(0.0, 1.0, dot(u - g.xy, d) / max(dot(d, d), 1e-6));
}
float radialW(vec4 g, float f, vec2 u) {
    float d = length((u - g.xy) / max(g.zw, vec2(1e-4)));
    return 1.0 - smoothstep(1.0 - max(f, 0.01), 1.0, d);
}
float colorW(vec4 g, float range, vec3 oh) {
    if (g.w <= 0.0) { return 0.0; }
    vec3 t = hsv(g.rgb);
    float dh = abs(oh.x - t.x);
    dh = min(dh, 1.0 - dh) * 2.0;
    float dist = length(vec2(dh, 0.5 * (oh.y - t.y))) + (1.0 - smoothstep(0.03, 0.15, oh.y));
    return 1.0 - smoothstep(range * 0.5, range, dist);
}
float lumaW(vec4 g, float f, float L) {
    float e = 0.02 + 0.2 * f;
    return smoothstep(g.x - e, g.x, L) * (1.0 - smoothstep(g.y, g.y + e, L));
}
vec3 local(vec3 x, vec4 a, vec4 b, float w, float dL) {
    if (w <= 0.0) { return x; }
    x *= exp2(2.0 * a.x * w / 2.2);
    x *= vec3(1.0 + 0.3 * b.x * w, 1.0 - 0.3 * b.y * w, 1.0 - 0.3 * b.x * w);
    float L = lum(x);
    float d = 0.3 * a.w * w * (1.0 - smoothstep(0.0, 0.6, L)) + 0.3 * a.z * w * smoothstep(0.4, 1.0, L);
    x *= (L + d) / max(L, 0.001);
    x = (x - 0.5) * (1.0 + 0.6 * a.y * w) + 0.5;
    x += 1.2 * b.w * w * dL;
    L = lum(x);
    return mix(vec3(L), x, 1.0 + b.z * w);
}
vec4 at(sampler s, vec2 p, float H) { return sample(s, samplerTransform(s, vec2(p.x, H - p.y))); }
vec3 curve(sampler lut, vec3 x) {
    x = clamp(x, 0.0, 1.0) * 255.0 + 0.5;
    return vec3(sample(lut, samplerTransform(lut, vec2(x.r, 0.5))).r,
                sample(lut, samplerTransform(lut, vec2(x.g, 0.5))).g,
                sample(lut, samplerTransform(lut, vec2(x.b, 0.5))).b);
}
vec3 lut3(sampler l3, vec3 c, float n) {
    vec3 s = clamp(c, 0.0, 1.0) * (n - 1.0);
    float b0 = floor(s.b);
    float b1 = min(b0 + 1.0, n - 1.0);
    float y = n - (s.g + 0.5);
    vec3 a = sample(l3, samplerTransform(l3, vec2(s.r + 0.5 + b0 * n, y))).rgb;
    vec3 b = sample(l3, samplerTransform(l3, vec2(s.r + 0.5 + b1 * n, y))).rgb;
    return mix(a, b, s.b - b0);
}
vec3 gradeRange(vec3 x, float h, float s, float l, float w) {
    if (w <= 0.0) { return x; }
    vec3 t = rgb(vec3(h, 1.0, 1.0));
    t /= max(lum(t), 1e-3);
    x = mix(x, x * t, 0.5 * s * w);
    return x * (1.0 + 0.5 * l * w);
}
float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float vnoise(vec2 g) {
    vec2 i = floor(g);
    vec2 f = fract(g);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

kernel vec4 develop(sampler img, sampler blurS, sampler blurL, sampler lut, sampler l3d,
                    sampler mk0, sampler mk1, sampler mk2, sampler mk3,
                    \(PACKS.indices.map { "vec4 s\($0)" }.joined(separator: ", ")),
                    vec4 hueA, vec4 hueB, vec4 satA, vec4 satB, vec4 lumA, vec4 lumB,
                    vec4 geo, vec4 info, vec4 overlay, vec4 t0, vec4 t1, vec4 t2,
                    \((0..<MAX_MASKS).map { "vec4 mg\($0), vec4 mx\($0), vec4 ma\($0), vec4 mb\($0)" }.joined(separator: ",\n                    "))) {
\(PACKS.enumerated().flatMap { i, pack in pack.enumerated().map { j, k in "    float u_\(k) = s\(i).\(["x", "y", "z", "w"][j]);" } }.joined(separator: "\n"))
    float H = geo.y;
    vec2 srcSize = geo.xy;
    float pxScale = geo.z;
    float outAspect = geo.w;
    vec2 dc = destCoord();
    vec2 p0 = vec2(dc.x, H - dc.y);

    vec2 ctr = srcSize * 0.5;
    vec2 dd = p0 - ctr;
    float r2 = dot(dd, dd) / dot(ctr, ctr);
    float k = 0.3 * u_distortion;
    vec2 p = ctr + dd * (1.0 + k * r2) / (1.0 + max(k, 0.0));

    vec4 c = at(img, p, H);
    vec3 x = c.rgb;
    if (u_chroma != 0.0) {
        x.r = at(img, ctr + (p - ctr) * (1.0 + 0.003 * u_chroma), H).r;
        x.b = at(img, ctr + (p - ctr) * (1.0 - 0.003 * u_chroma), H).b;
    }
    vec3 bs = at(blurS, p, H).rgb;
    vec3 bl = at(blurL, p, H).rgb;
    if (u_noise > 0.0) {
        float L0 = lum(x);
        float sig = 0.01 + 0.08 * u_noise;
        vec3 acc = x;
        float wsum = 1.0;
\(NR_TAPS.map { dx, dy in "        { vec3 sn = at(img, p + vec2(\(dx), \(dy)) * 1.5 * pxScale, H).rgb; float dn = lum(sn) - L0; float wn = exp(-dn * dn / (sig * sig)); acc += sn * wn; wsum += wn; }" }.joined(separator: "\n"))
        x = mix(x, acc / wsum, u_noise);
    }
    if (u_colorNoise > 0.0) { x = mix(x, bs - lum(bs) + lum(x), u_colorNoise); }
    if (u_sharpen > 0.0) {
        vec3 n = (at(img, p + vec2(1.0, 0.0), H).rgb + at(img, p - vec2(1.0, 0.0), H).rgb
                + at(img, p + vec2(0.0, 1.0), H).rgb + at(img, p - vec2(0.0, 1.0), H).rgb) * 0.25;
        x += 1.5 * u_sharpen * (x - n);
    }
    x *= 1.0 + 0.6 * u_lensVignette * r2;
    float dS = lum(x) - lum(bs);
    float dL = lum(x) - lum(bl);

    float haze = min(bl.r, min(bl.g, bl.b));
    if (u_dehaze > 0.0) { x = (x - 0.95) / max(1.0 - 0.8 * u_dehaze * haze, 0.25) + 0.95; }
    else { x = mix(x, vec3(0.85), -0.4 * u_dehaze); }

    x = pow(max(x, 0.0), vec3(2.2));
    x *= exp2(2.0 * u_exposure) * vec3(1.0 + 0.3 * u_temp, 1.0 - 0.3 * u_tint, 1.0 - 0.3 * u_temp);
    x = pow(max(x, 0.0), vec3(1.0 / 2.2));
    float bp = -0.15 * u_blacks;
    float wp = 1.0 - 0.15 * u_whites;
    x = (x - bp) / (wp - bp);
    float L = lum(x);
    float d = 0.3 * u_shadows * (1.0 - smoothstep(0.0, 0.6, L)) + 0.3 * u_highlights * smoothstep(0.4, 1.0, L);
    x *= (L + d) / max(L, 0.001);

    L = clamp(lum(x), 0.0, 1.0);
    float mid = 1.0 - (2.0 * L - 1.0) * (2.0 * L - 1.0);
    x += 1.5 * u_texture * dS + 1.2 * u_clarity * dL * mid;
    x = (x - 0.5) * (1.0 + 0.6 * u_contrast) + 0.5;

    vec2 u = p / srcSize;
    vec3 oh = hsv(c.rgb);
    float oL = lum(c.rgb);
\((0..<MAX_MASKS).map { i in """
    float w\(i) = 0.0;
    if (mx\(i).x == 1.0) { w\(i) = linearW(mg\(i), u); }
    if (mx\(i).x == 2.0) { w\(i) = radialW(mg\(i), mx\(i).y, u); }
    if (mx\(i).x == 3.0) { w\(i) = at(mk\(i), p, H).r; }
    if (mx\(i).x == 4.0) { w\(i) = colorW(mg\(i), mx\(i).w, oh); }
    if (mx\(i).x == 5.0) { w\(i) = lumaW(mg\(i), mx\(i).y, oL); }
    if (mx\(i).x > 0.0 && mx\(i).z > 0.5) { w\(i) = 1.0 - w\(i); }
    x = local(x, ma\(i), mb\(i), w\(i), dL);
""" }.joined(separator: "\n"))

    if (info.x > 0.0) { x = mix(x, lut3(l3d, x, info.x), info.y); }
    x = curve(lut, x);

    L = lum(x);
    float s = max(x.r, max(x.g, x.b)) - min(x.r, min(x.g, x.b));
    x = mix(vec3(L), x, (1.0 + u_saturation) * (1.0 + u_vibrance * (1.0 - s)));

    vec3 hh = hsv(clamp(x, 0.0, 1.0));
    float deg = hh.x * 360.0;
    float chroma = hh.y;
    hh.x = fract(hh.x + band(hueA, hueB, deg) * 30.0 / 360.0 + 1.0);
    x = rgb(hh);
    x = mix(vec3(lum(x)), x, 1.0 + band(satA, satB, deg));
    x *= 1.0 + 0.5 * band(lumA, lumB, deg) * chroma;

    L = clamp(lum(x), 0.0, 1.0);
    float blend = 0.3 * (u_blending + 1.0) * 0.5;
    float bal = 0.25 * u_balance;
    float ws = 1.0 - smoothstep(0.0, 0.5 + bal + blend, L);
    float wh = smoothstep(0.5 + bal - blend, 1.0, L);
    x = gradeRange(x, u_shadowHue, u_shadowSat, u_shadowLum, ws);
    x = gradeRange(x, u_midHue, u_midSat, u_midLum, clamp(1.0 - ws - wh, 0.0, 1.0));
    x = gradeRange(x, u_highHue, u_highSat, u_highLum, wh);

    vec3 ph = vec3(p0, 1.0);
    vec2 q = vec2(dot(t0.xyz, ph), dot(t1.xyz, ph)) / dot(t2.xyz, ph);
    x *= 1.0 + 0.8 * u_vignette * smoothstep(0.4, 1.1, length(q - 0.5) * 1.414);
    if (u_grain > 0.0) {
        float cells = 900.0 * exp2(-1.5 * u_grainSize);
        float gn = vnoise(q * vec2(cells, cells / outAspect)) - 0.5;
        x += u_grain * 0.3 * gn * (0.3 + 0.7 * (1.0 - abs(2.0 * L - 1.0)));
    }
    if (info.z >= 0.0) {
        float wsel = w3;
        if (info.z == 0.0) { wsel = w0; }
        if (info.z == 1.0) { wsel = w1; }
        if (info.z == 2.0) { wsel = w2; }
        x = mix(x, overlay.rgb, 0.5 * wsel);
    }
    return vec4(clamp(x, 0.0, 1.0), c.a);
}
"""

let developKernel: CIKernel = {
    guard let k = CIKernel(source: KERNEL_SOURCE) else { fatalError("develop kernel failed to compile") }
    return k
}()

// MARK: lookup tables

/// 256×1 strip: per channel, channel curve after the RGB curve.
func curveLUT(_ curves: [[CGPoint]]) -> CIImage {
    let f = curves.map(curveFn)
    var px = [UInt8](repeating: 255, count: 256 * 4)
    for i in 0..<256 {
        let y = f[0](Double(i) / 255)
        for c in 0..<3 { px[i * 4 + c] = UInt8((f[c + 1](y) * 255).rounded()) }
    }
    return image(px, 256, 1)
}

/// RGBA8 pixels (top row first) → CIImage in sRGB.
func image(_ px: [UInt8], _ w: Int, _ h: Int) -> CIImage {
    let data = Data(px)
    return CIImage(bitmapData: data, bytesPerRow: w * 4, size: CGSize(width: w, height: h), format: .RGBA8, colorSpace: sRGB)
        .transformed(by: CGAffineTransform(scaleX: 1, y: -1).translatedBy(x: 0, y: -CGFloat(h))) // bitmap rows are top-down
}

/// Profiles: a base look as a 3D LUT (N slices of N×N side by side, blue picks the slice). Same maths as Android.
enum Profiles {
    static let N = 33
    static var dir: URL = {
        let d = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Lumen/profiles")
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }()
    private static var cache: [String: (CIImage, Int)] = [:]

    private static func lum(_ r: Float, _ g: Float, _ b: Float) -> Float { 0.2126 * r + 0.7152 * g + 0.0722 * b }
    private static func s(_ x: Float) -> Float { x * x * (3 - 2 * x) }
    private static func mixF(_ a: Float, _ b: Float, _ t: Float) -> Float { a + (b - a) * t }

    static let builtIn: [(String, (Float, Float, Float) -> [Float])] = [
        ("Mono", { r, g, b in let l = 0.25 * r + 0.65 * g + 0.1 * b; let y = mixF(l, s(l), 0.4); return [y, y, y] }),
        ("Mono warm", { r, g, b in let l = lum(r, g, b); let y = mixF(l, s(l), 0.3); return [y * 1.04, y, y * 0.9] }),
        ("Film", { r, g, b in
            let l = lum(r, g, b)
            var o = [r, g, b].map { c in 0.04 + 0.92 * pow(mixF(l, c, 0.9), 0.95) }
            o[0] += 0.02 * (1 - l); o[2] -= 0.02 * (1 - l); o[2] += 0.015 * l
            return o
        }),
        ("Teal & amber", { r, g, b in
            let l = lum(r, g, b)
            let t: [Float] = l < 0.5 ? [-0.06, 0.02, 0.05] : [0.06, 0.02, -0.06]
            let k = abs(2 * l - 1)
            return [r + t[0] * k, g + t[1] * k, b + t[2] * k]
        }),
        ("Vivid", { r, g, b in let l = lum(r, g, b); return [r, g, b].map { c in mixF(s(c), mixF(l, c, 1.25), 0.7) } }),
        ("Matte", { r, g, b in let l = lum(r, g, b); return [r, g, b].map { c in 0.08 + 0.86 * mixF(l, c, 0.85) } }),
    ]

    static func imported() -> [String] {
        ((try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []).filter { $0.hasSuffix(".png") }.map { String($0.dropLast(4)) }.sorted()
    }
    static func names() -> [String] { ["Color"] + builtIn.map(\.0) + imported() }

    private static func strip(_ f: (Float, Float, Float) -> [Float], _ n: Int) -> [UInt8] {
        var px = [UInt8](repeating: 255, count: n * n * n * 4)
        for b in 0..<n { for g in 0..<n { for r in 0..<n {
            let o = f(Float(r) / Float(n - 1), Float(g) / Float(n - 1), Float(b) / Float(n - 1))
            let i = (g * n * n + b * n + r) * 4
            for c in 0..<3 { px[i + c] = UInt8((min(1, max(0, o[c])) * 255).rounded()) }
        } } }
        return px
    }

    /// The LUT strip and its size, or nil for "Color" / unknown names.
    static func lut(_ name: String) -> (CIImage, Int)? {
        if name == "Color" { return nil }
        if let c = cache[name] { return c }
        var out: (CIImage, Int)?
        if let f = builtIn.first(where: { $0.0 == name })?.1 {
            out = (image(strip(f, N), N * N, N), N)
        } else if let src = CGImageSourceCreateWithURL(dir.appendingPathComponent("\(name).png") as CFURL, nil),
                  let cg = CGImageSourceCreateImageAtIndex(src, 0, nil) {
            out = (CIImage(cgImage: cg), cg.height)
        }
        cache[name] = out
        return out
    }

    /// Parses a 3D .cube (red changes fastest) into strip pixels. Throws a readable reason.
    static func parseCube(_ text: String) throws -> ([UInt8], Int) {
        var n = 0
        var lo: [Float] = [0, 0, 0], hi: [Float] = [1, 1, 1]
        var rows: [[Float]] = []
        for raw in text.split(whereSeparator: \.isNewline) {
            let line = raw.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init)?.trimmingCharacters(in: .whitespaces) ?? ""
            if line.isEmpty { continue }
            let parts = line.split(whereSeparator: \.isWhitespace).map(String.init)
            switch parts[0].uppercased() {
            case "TITLE": continue
            case "LUT_1D_SIZE": throw LumenError("1D LUTs aren't supported, only 3D")
            case "LUT_3D_SIZE": n = Int(parts[1]) ?? 0
            case "DOMAIN_MIN": lo = parts.dropFirst().compactMap { Float($0) }
            case "DOMAIN_MAX": hi = parts.dropFirst().compactMap { Float($0) }
            default: let v = parts.compactMap { Float($0) }; if v.count == 3 { rows.append(v) }
            }
        }
        guard (2...64).contains(n) else { throw LumenError("LUT size must be 2 to 64, this file says \(n)") }
        guard rows.count == n * n * n else { throw LumenError("Expected \(n * n * n) colour rows, found \(rows.count)") }
        var px = [UInt8](repeating: 255, count: n * n * n * 4)
        for (i, v) in rows.enumerated() {
            let r = i % n, g = (i / n) % n, b = i / (n * n)
            let o = (g * n * n + b * n + r) * 4
            for c in 0..<3 { px[o + c] = UInt8((min(1, max(0, (v[c] - lo[c]) / (hi[c] - lo[c]))) * 255).rounded()) }
        }
        return (px, n)
    }

    static func importCube(name: String, text: String) throws {
        let (px, n) = try parseCube(text)
        let clean = String(name.filter { $0.isLetter || $0.isNumber || " _&().-".contains($0) }).trimmingCharacters(in: .whitespaces)
        let file = dir.appendingPathComponent("\(clean.isEmpty ? "Imported" : clean).png")
        let ctx = CGContext(data: UnsafeMutableRawPointer(mutating: px), width: n * n, height: n, bitsPerComponent: 8, bytesPerRow: n * n * 4,
                            space: sRGB, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        let dest = CGImageDestinationCreateWithURL(file as CFURL, UTType.png.identifier as CFString, 1, nil)!
        CGImageDestinationAddImage(dest, ctx.makeImage()!, nil)
        guard CGImageDestinationFinalize(dest) else { throw LumenError("Couldn't save the profile") }
        cache[clean] = nil
    }
}

struct LumenError: LocalizedError { let message: String; init(_ m: String) { message = m }; var errorDescription: String? { message } }

// MARK: pixels on the CPU (spots, analysis)

/// RGBA8 copy of a CGImage, top row first.
struct Pixels {
    var data: [UInt8]; let w: Int; let h: Int
    init(_ cg: CGImage) {
        w = cg.width; h = cg.height
        data = [UInt8](repeating: 0, count: w * h * 4)
        data.withUnsafeMutableBytes { buf in
            let ctx = CGContext(data: buf.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4, space: sRGB,
                                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        }
    }
    var cgImage: CGImage {
        var copy = data
        return copy.withUnsafeMutableBytes { buf in
            CGContext(data: buf.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4, space: sRGB,
                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!.makeImage()!
        }
    }
}

/// Heal / clone spots copied into a new image before any develop step (so blurs and masks see the clean photo). Port of Android's bake().
func bake(_ base: CGImage, _ spots: [Spot]) -> CGImage {
    if spots.isEmpty { return base }
    var px = Pixels(base)
    let w = px.w, h = px.h
    for s in spots {
        let r = max(1, Int((s.r * Float(max(w, h))).rounded()))
        let size = 2 * r + 1
        let dx = Int((s.dx * Float(w)).rounded()) - r, dy = Int((s.dy * Float(h)).rounded()) - r
        let sx = Int((s.sx * Float(w)).rounded()) - r, sy = Int((s.sy * Float(h)).rounded()) - r
        if dx < 0 || dy < 0 || sx < 0 || sy < 0 || dx + size > w || dy + size > h || sx + size > w || sy + size > h { continue }
        func idx(_ x: Int, _ y: Int) -> Int { (y * w + x) * 4 }
        var shift: [Float] = [0, 0, 0]
        if s.heal { // shift the copied patch by the difference of the two rims, so it takes on the colour around the blemish
            var n = 0
            for j in 0..<size { for i in 0..<size {
                let d = hypot(Float(i - r), Float(j - r)) / Float(r)
                if d >= 0.8 && d <= 1 {
                    n += 1
                    for k in 0..<3 { shift[k] += Float(px.data[idx(dx + i, dy + j) + k]) - Float(px.data[idx(sx + i, sy + j) + k]) }
                }
            } }
            if n > 0 { for k in 0..<3 { shift[k] /= Float(n) } }
        }
        let src = px.data
        for j in 0..<size { for i in 0..<size {
            let d = hypot(Float(i - r), Float(j - r)) / Float(r)
            if d > 1 { continue }
            var t = min(1, max(0, (d - 0.6) / 0.4)); t = t * t * (3 - 2 * t)
            let a = 1 - t // feathered edge
            for k in 0..<3 {
                let from = Float(src[idx(sx + i, sy + j) + k]) + shift[k], to = Float(src[idx(dx + i, dy + j) + k])
                px.data[idx(dx + i, dy + j) + k] = UInt8(min(255, max(0, (to + (from - to) * a).rounded())))
            }
        } }
    }
    return px.cgImage
}

/// A brush mask rasterised from its strokes, white = painted, at most 1024 px on the long edge, placed over the w×h source.
private func brush(_ m: Mask, _ w: Int, _ h: Int) -> CIImage {
    let k = min(1, 1024 / Double(max(w, h)))
    let bw = max(1, Int((Double(w) * k).rounded())), bh = max(1, Int((Double(h) * k).rounded()))
    let ctx = CGContext(data: nil, width: bw, height: bh, bitsPerComponent: 8, bytesPerRow: 0, space: CGColorSpaceCreateDeviceGray(),
                        bitmapInfo: CGImageAlphaInfo.none.rawValue)!
    ctx.setFillColor(gray: 0, alpha: 1); ctx.fill(CGRect(x: 0, y: 0, width: bw, height: bh))
    ctx.translateBy(x: 0, y: CGFloat(bh)); ctx.scaleBy(x: 1, y: -1) // top-left coordinates, like the strokes
    ctx.setLineCap(.round); ctx.setLineJoin(.round)
    let long = Double(max(bw, bh))
    for s in m.strokes {
        ctx.setStrokeColor(gray: s.erase ? 0 : 1, alpha: 1)
        ctx.setLineWidth(CGFloat(Double(s.size) * long))
        ctx.beginPath()
        ctx.move(to: CGPoint(x: Double(s.pts[0]) * Double(bw), y: Double(s.pts[1]) * Double(bh)))
        if s.pts.count == 2 { ctx.addLine(to: CGPoint(x: Double(s.pts[0]) * Double(bw) + 0.01, y: Double(s.pts[1]) * Double(bh))) }
        for i in stride(from: 2, to: s.pts.count, by: 2) { ctx.addLine(to: CGPoint(x: Double(s.pts[i]) * Double(bw), y: Double(s.pts[i + 1]) * Double(bh))) }
        ctx.strokePath()
    }
    var img = CIImage(cgImage: ctx.makeImage()!)
    let soft = Double(m.strokes.map(\.size).max() ?? 0) * long * Double(m.feather) * 0.3
    if soft > 0.5 { img = img.clampedToExtent().applyingGaussianBlur(sigma: soft).cropped(to: img.extent) }
    return img.transformed(by: CGAffineTransform(scaleX: CGFloat(w) / CGFloat(bw), y: CGFloat(h) / CGFloat(bh)))
}

/// Gaussian blur at reduced size; the radius is a fraction of the long edge so preview and export match (as on Android).
private func blur(_ img: CIImage, _ frac: Double, _ maxLong: Double) -> CIImage {
    let long = Double(max(img.extent.width, img.extent.height))
    let k = min(1, maxLong / long)
    let small = img.transformed(by: CGAffineTransform(scaleX: k, y: k))
    let sigma = max(0.5, frac * long * k * 0.57735 + 0.5) // Skia's radius → sigma, so it matches Android's RenderEffect blur
    return small.clampedToExtent().applyingGaussianBlur(sigma: sigma).cropped(to: small.extent)
        .transformed(by: CGAffineTransform(scaleX: 1 / k, y: 1 / k)).cropped(to: img.extent)
}

private let dummy = CIImage(color: .black).cropped(to: CGRect(x: 0, y: 0, width: 1, height: 1))

/// A photo ready to develop: the pixels (spots baked in), its blurs and lookup tables. One per photo, and per export.
final class Look {
    let base: CGImage
    let w: Double, h: Double
    private(set) var src: CIImage
    private var blurS: CIImage, blurL: CIImage
    private var spotsFor: [Spot] = []
    private var lutFor: [[CGPoint]]?
    private var lutImg = dummy
    private var profileFor: String?
    private var profileImg: (CIImage, Int)?
    private var brushFor = [AnyHashable?](repeating: nil, count: MAX_MASKS)
    private var brushImg = [CIImage?](repeating: nil, count: MAX_MASKS)
    var overlay: (Double, Double, Double) = (1, 0.35, 0.35)

    init(_ cg: CGImage) {
        base = cg; w = Double(cg.width); h = Double(cg.height)
        src = CIImage(cgImage: cg)
        blurS = blur(src, 0.004, 1024); blurL = blur(src, 0.03, 512)
    }

    var baseImage: CIImage { CIImage(cgImage: base) }

    private func prepare(_ e: Edit) {
        if spotsFor != e.retouch {
            src = CIImage(cgImage: bake(base, e.retouch))
            blurS = blur(src, 0.004, 1024); blurL = blur(src, 0.03, 512)
            spotsFor = e.retouch
        }
        if lutFor != e.curves { lutImg = curveLUT(e.curves); lutFor = e.curves }
        if profileFor != e.profile { profileImg = Profiles.lut(e.profile); profileFor = e.profile }
        for i in 0..<MAX_MASKS {
            let m = e.masks.indices.contains(i) && e.masks[i].type == "brush" ? e.masks[i] : nil
            let key: AnyHashable? = m.map { AnyHashable("\($0.strokes.count)|\($0.strokes.last?.pts.count ?? 0)|\($0.strokes.hashValue)|\($0.feather)") }
            if key != brushFor[i] { brushImg[i] = m.map { brush($0, Int(w), Int(h)) }; brushFor[i] = key }
        }
    }

    /// The edit in output pixels, extent (0, 0, width, height). `withCrop = false` gives the whole straightened frame (crop mode).
    /// `showMask` tints that mask's area with the overlay colour (-1 = off).
    func image(_ e: Edit, withCrop: Bool = true, showMask: Int = -1) -> CIImage {
        prepare(e)
        let m = e.matrix(w, h, withCrop: withCrop)
        let (ow, oh): (Double, Double) = withCrop ? { let s = e.outSize(w, h); return (Double(s.0), Double(s.1)) }() : e.oriented(w, h)
        let t = m.then(.scale(1 / ow, 1 / oh)).m
        func v4(_ a: Float, _ b: Float, _ c: Float, _ d: Float) -> CIVector { CIVector(x: CGFloat(a), y: CGFloat(b), z: CGFloat(c), w: CGFloat(d)) }
        func pad(_ a: [Float]) -> [Float] { a + [Float](repeating: 0, count: 4 - a.count) }
        var args: [Any] = [src, blurS, blurL, lutImg, profileImg?.0 ?? dummy]
        args += (0..<MAX_MASKS).map { brushImg[$0] ?? dummy }
        args += PACKS.map { pack in let x = pad(pack.map { e[$0] }); return v4(x[0], x[1], x[2], x[3]) }
        for k in ["Hue", "Sat", "Lum"] {
            let x = HUES.map { e["\($0)\(k)"] }
            args += [v4(x[0], x[1], x[2], x[3]), v4(x[4], x[5], x[6], x[7])]
        }
        args += [CIVector(x: w, y: h, z: max(1, max(w, h) / 2048), w: ow / oh),
                 v4(Float(profileImg?.1 ?? 0), e.profileAmount, Float(showMask), 0),
                 CIVector(x: overlay.0, y: overlay.1, z: overlay.2, w: 0),
                 CIVector(x: t[0], y: t[1], z: t[2], w: 0), CIVector(x: t[3], y: t[4], z: t[5], w: 0), CIVector(x: t[6], y: t[7], z: t[8], w: 0)]
        for i in 0..<MAX_MASKS {
            let mk = e.masks.indices.contains(i) ? e.masks[i] : nil
            let a = mk?.adj ?? [Float](repeating: 0, count: MASK_ADJ.count), g = mk?.g ?? [0, 0, 0, 0]
            let type = Float(mk.map { (MASK_TYPES.firstIndex(of: $0.type) ?? -1) + 1 } ?? 0)
            args += [v4(g[0], g[1], g[2], g[3]), v4(type, mk?.feather ?? 0, mk?.invert == true ? 1 : 0, mk?.range ?? 0),
                     v4(a[0], a[1], a[2], a[3]), v4(a[4], a[5], a[6], a[7])]
        }
        let extent = src.extent
        let warps = e["distortion"] != 0 || e["chroma"] != 0
        let margin = 8 * max(1, max(w, h) / 2048) + 4
        let lutExtent = lutImg.extent, profileExtent = (profileImg?.0 ?? dummy).extent
        let maskExtents = (0..<MAX_MASKS).map { (brushImg[$0] ?? dummy).extent }
        let developed = developKernel.apply(extent: extent, roiCallback: { i, r in
            switch i {
            case 0, 1, 2: return warps ? extent : r.insetBy(dx: -margin, dy: -margin)
            case 3: return lutExtent
            case 4: return profileExtent
            default: return maskExtents[Int(i) - 5]
            }
        }, arguments: args)!

        // geometry: map the source corners through the edit's matrix (top-left coords) into Core Image's bottom-left output frame
        let corners = [CGPoint(x: 0, y: 0), CGPoint(x: w, y: 0), CGPoint(x: w, y: h), CGPoint(x: 0, y: h)].map {
            let p = m.apply($0); return CIVector(x: p.x, y: oh - p.y)
        }
        let out = CGRect(x: 0, y: 0, width: ow, height: oh)
        if m == .identity { return developed.cropped(to: out) }
        return developed.clampedToExtent().cropped(to: extent).applyingFilter("CIPerspectiveTransform", parameters: [
            "inputTopLeft": corners[0], "inputTopRight": corners[1], "inputBottomRight": corners[2], "inputBottomLeft": corners[3],
        ]).cropped(to: out)
    }

    /// Rendered to a CGImage, scaled so the long edge is at most `maxLong` (0 = full size).
    func render(_ e: Edit, maxLong: Double = 0, withCrop: Bool = true, showMask: Int = -1) -> CGImage {
        var img = image(e, withCrop: withCrop, showMask: showMask)
        let long = Double(max(img.extent.width, img.extent.height))
        if maxLong > 0 && long > maxLong {
            let k = maxLong / long
            img = img.transformed(by: CGAffineTransform(scaleX: k, y: k))
            img = img.cropped(to: CGRect(x: 0, y: 0, width: img.extent.width.rounded(.down), height: img.extent.height.rounded(.down)))
        }
        return ciContext.createCGImage(img, from: img.extent, format: .RGBA8, colorSpace: sRGB)!
    }
}

// MARK: decoding

/// Decoded with EXIF orientation applied (and RAW developed by macOS), long edge at most `maxPixel` (0 = full size).
func decode(_ url: URL, maxPixel: Int = 0) -> CGImage? {
    guard let src = CGImageSourceCreateWithURL(url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary) else { return nil }
    let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any]
    let full = max(props?[kCGImagePropertyPixelWidth] as? Int ?? 0, props?[kCGImagePropertyPixelHeight] as? Int ?? 0)
    let opts: [CFString: Any] = [
        kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true,
        kCGImageSourceThumbnailMaxPixelSize: maxPixel > 0 ? min(maxPixel, max(full, 1)) : max(full, 1),
        kCGImageSourceShouldCacheImmediately: true,
    ]
    guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary) else { return nil }
    // convert once to sRGB (wide-gamut photos included), like Android's setTargetColorSpace(SRGB)
    if cg.colorSpace?.name == CGColorSpace.sRGB, cg.bitsPerComponent == 8 { return cg }
    let ctx = CGContext(data: nil, width: cg.width, height: cg.height, bitsPerComponent: 8, bytesPerRow: 0, space: sRGB,
                        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    ctx.draw(cg, in: CGRect(x: 0, y: 0, width: cg.width, height: cg.height))
    return ctx.makeImage()
}
