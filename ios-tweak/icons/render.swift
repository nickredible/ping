// Renders the SVG icons to PNG with transparent corners (qlmanage paints them white).
// Regenerate after editing an SVG, from ios-tweak/icons/:
//   swiftc -O -o /tmp/render render.swift
//   for s in 29:icon 58:icon@2x 87:icon@3x; do /tmp/render settings-icon.svg ${s%%:*} ${s##*:}.png; done
//   /tmp/render icon.svg 512 CydiaIcon.png
import AppKit
// usage: render.swift <svg> <size px> <out.png>
let a = CommandLine.arguments
guard let img = NSImage(contentsOf: URL(fileURLWithPath: a[1])) else { fatalError("cannot load svg") }
let px = Int(a[2])!
let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: px, pixelsHigh: px, bitsPerSample: 8,
                           samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                           colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
rep.size = NSSize(width: px, height: px)
NSGraphicsContext.saveGraphicsState()
let ctx = NSGraphicsContext(bitmapImageRep: rep)!
ctx.imageInterpolation = .high
NSGraphicsContext.current = ctx
NSColor.clear.set()
NSRect(x: 0, y: 0, width: px, height: px).fill()
img.draw(in: NSRect(x: 0, y: 0, width: px, height: px), from: .zero, operation: .sourceOver, fraction: 1)
NSGraphicsContext.restoreGraphicsState()
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: a[3]))
