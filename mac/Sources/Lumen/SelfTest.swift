import Foundation
import CoreGraphics
import CoreImage

/// The Android self-test, ported: `Lumen.app/Contents/MacOS/Lumen --selftest` (or ./build.sh test). Exit code 0 = pass.
func selfTest() -> Bool {
    var checks: [(String, Bool)] = []
    func check(_ name: String, _ ok: Bool) { checks.append((name, ok)) }
    func solid(_ r: UInt8, _ g: UInt8, _ b: UInt8, _ w: Int = 1, _ h: Int = 1) -> CGImage {
        var p = Pixels(CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0, space: sRGB,
                                 bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!.makeImage()!)
        for i in 0..<(w * h) { p.data[i * 4] = r; p.data[i * 4 + 1] = g; p.data[i * 4 + 2] = b; p.data[i * 4 + 3] = 255 }
        return p.cgImage
    }
    func px(_ cg: CGImage, _ x: Int = 0, _ y: Int = 0) -> (Int, Int, Int) {
        let p = Pixels(cg); let i = (y * p.w + x) * 4
        return (Int(p.data[i]), Int(p.data[i + 1]), Int(p.data[i + 2]))
    }
    func edit(_ kv: [String: Float]) -> Edit { var e = Edit(); for (k, v) in kv { e[k] = v }; return e }
    func near(_ a: Int, _ b: Int, _ t: Int = 2) -> Bool { abs(a - b) <= t }

    let gray = solid(128, 128, 128)
    let base = px(Look(gray).render(Edit())), bright = px(Look(gray).render(edit(["exposure": 0.5]))), warm = px(Look(gray).render(edit(["temp": 1])))
    // same numbers the Android self-test logs: base=808080 bright=afafaf warm=90806d
    check("identity", near(base.0, 128) && near(base.1, 128) && near(base.2, 128))
    check("exposure", near(bright.0, 0xaf, 3))
    check("temp", near(warm.0, 0x90, 3) && near(warm.2, 0x6d, 3))
    let red = px(Look(solid(200, 40, 40)).render(edit(["redSat": -1])))
    check("mixer", abs(red.0 - red.1) < 12 && (60...100).contains(red.0))

    let line = curveFn(LINE)
    check("curve identity", (0...10).allSatisfy { abs(line(Double($0) / 10) - Double($0) / 10) < 1e-4 })
    let f = curveFn([CGPoint(x: 0, y: 0), CGPoint(x: 0.3, y: 0.5), CGPoint(x: 0.6, y: 0.55), CGPoint(x: 1, y: 1)])
    let fs = (0...100).map { f(Double($0) / 100) }
    check("curve monotone", zip(fs, fs.dropFirst()).allSatisfy { $1 >= $0 - 1e-5 } && abs(f(0.3) - 0.5) < 1e-4)

    var g = Edit(); g["straighten"] = 0.3
    let fit = g.shrinkToFit(CGRect(x: 0, y: 0, width: 1, height: 1), 300, 200)
    check("straighten fits", !g.fits(CGRect(x: 0, y: 0, width: 1, height: 1), 300, 200) && g.fits(fit, 300, 200) && fit.width < 1)
    var r = Edit(); r.crop = CGRect(x: 0.1, y: 0.2, width: 0.4, height: 0.7)
    for _ in 0..<4 { r.rotateRight() }
    check("4 turns", r.rot90 == 0 && abs(r.crop.minX - 0.1) < 1e-9 && abs(r.crop.maxY - 0.9) < 1e-9)
    var ks = Edit(); ks["vertical"] = 0.6
    let corner = ks.shape(300, 200).apply(.zero)
    check("perspective", ks.fits(CGRect(x: 0, y: 0, width: 1, height: 1), 300, 200) && corner.x < -10 && abs(corner.y) < 1e-6)

    // geometry: a rotated, cropped render has the right size and the right corner colour
    var gp = Pixels(solid(0, 0, 0, 40, 20))
    for y in 0..<10 { for x in 0..<20 { gp.data[(y * 40 + x) * 4] = 255 } } // red top-left quarter
    var rot = Edit(); rot.rotateRight()
    let turned = Look(gp.cgImage).render(rot)
    check("rotate 90", turned.width == 20 && turned.height == 40 && px(turned, 15, 5).0 > 200 && px(turned, 5, 5).0 < 50)

    // masks
    let flat = solid(100, 100, 100, 64, 64)
    func masked(_ m: Mask) -> CGImage { var e = Edit(); e.masks = [m]; return Look(flat).render(e) }
    var radial = Mask(type: "radial"); radial.g = [0.5, 0.5, 0.25, 0.25]; radial.adj[0] = 1
    let rr = masked(radial)
    check("radial mask", px(rr, 32, 32).0 > 140 && near(px(rr, 2, 2).0, 100))
    var lin = Mask(type: "linear"); lin.g = [0.5, 0, 0.5, 0.5]; lin.adj[0] = 1
    let lr = masked(lin)
    check("gradient mask", px(lr, 32, 1).0 > 140 && near(px(lr, 32, 60).0, 100))
    var inv = radial; inv.invert = true
    let ir = masked(inv)
    check("inverted mask", near(px(ir, 32, 32).0, 100) && px(ir, 2, 2).0 > 140)
    var unpicked = Mask(type: "color"); unpicked.adj[0] = 1
    check("unpicked colour mask", near(px(masked(unpicked), 32, 32).0, 100))
    var br = Mask(type: "brush"); br.adj[0] = 1; br.feather = 0; br.strokes = [Stroke(pts: [0.2, 0.5, 0.8, 0.5], size: 0.1, erase: false)]
    let brr = masked(br)
    check("brush mask", px(brr, 32, 32).0 > 140 && near(px(brr, 32, 5).0, 100))

    // heal
    var dotted = Pixels(flat)
    for x in 30...34 { for y in 30...34 { let i = (y * 64 + x) * 4; dotted.data[i] = 0; dotted.data[i + 1] = 0; dotted.data[i + 2] = 0 } }
    let healed = bake(dotted.cgImage, [Spot(dx: 0.5, dy: 0.5, sx: 0.2, sy: 0.2, r: 0.08, heal: true)])
    check("heal", (95...105).contains(px(healed, 32, 32).0))

    // profiles, LUTs, presets
    let mono = px(Look(solid(200, 40, 40)).render({ var e = Edit(); e.profile = "Mono"; return e }()))
    check("mono profile", abs(mono.0 - mono.2) < 6)
    let cube = try? Profiles.parseCube("LUT_3D_SIZE 2\n0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n")
    check("cube parse", cube.map { c in c.1 == 2 && Array(c.0[12..<15]) == [255, 0, 255] && Array(c.0[20..<23]) == [255, 255, 0] } ?? false)
    let xmp = xmpPreset("""
        <x:xmpmeta><rdf:Description crs:Exposure2012="+1.00" crs:Contrast2012="+20" crs:HueAdjustmentRed="-10">
        <crs:Name><rdf:Alt><rdf:li xml:lang="x-default">Sunny</rdf:li></rdf:Alt></crs:Name>
        <crs:ToneCurvePV2012><rdf:Seq><rdf:li>0, 20</rdf:li><rdf:li>255, 240</rdf:li></rdf:Seq></crs:ToneCurvePV2012></rdf:Description></x:xmpmeta>
        """, fallbackName: "file")
    let xe = xmp.map { var e = Edit(); e.merge($0.1, whole: false); return e }
    check("xmp preset", xmp?.0 == "Sunny" && xe.map { abs($0["exposure"] - 0.5) < 1e-4 && abs($0["contrast"] - 0.2) < 1e-4 && abs($0["redHue"] + 0.1) < 1e-4
        && abs($0.curves[0][0].y - 20.0 / 255) < 1e-4 && $0["saturation"] == 0 } ?? false)
    var pm = Edit(); pm["exposure"] = 0.4; pm.merge("{\"color\":{\"temp\":0.3}}", whole: false)
    check("preset merge", pm["exposure"] == 0.4 && abs(pm["temp"] - 0.3) < 1e-6)

    // grading, grain, noise reduction
    let graded = px(Look(solid(50, 50, 50)).render(edit(["shadowHue": 0, "shadowSat": 1])))
    check("grading", graded.0 > graded.2 + 8)
    func spread(_ cg: CGImage) -> Double {
        let p = Pixels(cg); let v = (0..<(p.w * p.h)).map { Double(p.data[$0 * 4 + 1]) }
        let m = v.reduce(0, +) / Double(v.count); return (v.map { ($0 - m) * ($0 - m) }.reduce(0, +) / Double(v.count)).squareRoot()
    }
    check("grain", spread(Look(flat).render(edit(["grain": 1]))) > 2 && spread(Look(flat).render(Edit())) < 0.5)
    var noisyPx = Pixels(flat)
    var rng = SystemRandomNumberGenerator()
    for i in 0..<(64 * 64) { let v = UInt8(80 + Int.random(in: 0...40, using: &rng)); for c in 0..<3 { noisyPx.data[i * 4 + c] = v } }
    let noisy = noisyPx.cgImage
    check("noise reduction", spread(Look(noisy).render(edit(["noise": 1]))) < spread(Look(noisy).render(Edit())))

    // auto tone, level, spot finding
    check("auto tone", (autoTone(solid(30, 30, 30))["exposure"] ?? 0) > 0.3)
    let tilted: CGImage = {
        let c = CGContext(data: nil, width: 200, height: 200, bitsPerComponent: 8, bytesPerRow: 0, space: sRGB, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        c.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1)); c.fill(CGRect(x: 0, y: 0, width: 200, height: 200))
        c.translateBy(x: 100, y: 100); c.rotate(by: -5 * .pi / 180); c.translateBy(x: -100, y: -100) // 5° clockwise on screen
        c.setStrokeColor(CGColor(red: 0, green: 0, blue: 0, alpha: 1)); c.setLineWidth(3)
        for y in stride(from: -100, through: 300, by: 20) { c.move(to: CGPoint(x: -100, y: y)); c.addLine(to: CGPoint(x: 300, y: y)) }
        c.strokePath(); return c.makeImage()!
    }()
    let level = autoLevel(tilted)
    check("auto level", abs(level + 5.0 / 45) < 0.03)
    var specked = Pixels(solid(150, 150, 150, 200, 200))
    for x in 98...101 { for y in 98...101 { let i = (y * 200 + x) * 4; for c in 0..<3 { specked.data[i + c] = 60 } } }
    let found = findSpots(specked.cgImage)
    check("find spots", found.count == 1 && abs(found[0].dx - 0.5) < 0.02 && abs(found[0].dy - 0.5) < 0.02)

    // edit document round trip, and the same database the phone uses
    var j = Edit()
    j["clarity"] = 0.2; j.curves = [[CGPoint(x: 0, y: 0.1), CGPoint(x: 1, y: 0.9)], LINE, LINE, LINE]; j.rotateRight()
    j.profile = "Film"; j.profileAmount = 0.7; j["vertical"] = 0.1; j["grain"] = 0.3; j["shadowHue"] = 0.6
    j.masks = [{ var m = Mask(type: "brush"); m.adj[2] = -0.5; m.strokes = [Stroke(pts: [0.1, 0.1, 0.3, 0.4], size: 0.05, erase: false)]; return m }()]
    j.retouch = [Spot(dx: 0.5, dy: 0.5, sx: 0.6, sy: 0.5, r: 0.02, heal: false)]
    var back = Edit(); back.merge(j.json())
    var look = Edit(); look.merge(j.json(whole: false))
    check("json round trip", back.json() == j.json() && back.masks.count == 1 && back.retouch.count == 1 && look.masks.isEmpty)
    let d = Db(path: nil)
    let first = d.open(key: "a.jpg|1", uri: "", w: 1, h: 1)
    d.saveEdit(first.id, j.json()); d.mark(first.id, "rating", 4)
    let again = d.open(key: "a.jpg|1", uri: "/other", w: 1, h: 1)
    check("db", again.id == first.id && again.edit == j.json() && again.rating == 4 && d.presets().count == 6 && !d.mark(first.id, "rating", 9))
    d.saveVersion(first.id, "v1", j.json())
    let alb = d.createAlbum("Trip"); d.setInAlbum(alb, first.id, true)
    check("versions + albums", d.versions(first.id).count == 1 && d.albumKeys(alb) == ["a.jpg|1"])

    let failed = checks.filter { !$0.1 }.map(\.0)
    print("selftest \(failed.isEmpty ? "PASS" : "FAIL \(failed)") (\(checks.count) checks) base=\(base) bright=\(bright) warm=\(warm) red=\(red) level=\(level)")
    return failed.isEmpty
}
