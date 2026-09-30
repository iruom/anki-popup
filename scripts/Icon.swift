import AppKit
let image = NSImage(size: NSSize(width: 1024, height: 1024))
image.lockFocus()
NSColor(calibratedRed: 0.09, green: 0.12, blue: 0.17, alpha: 1).setFill()
NSBezierPath(roundedRect: NSRect(x: 32, y: 32, width: 960, height: 960), xRadius: 210, yRadius: 210).fill()
NSColor(calibratedRed: 0.24, green: 0.37, blue: 0.38, alpha: 1).setFill()
NSBezierPath(roundedRect: NSRect(x: 208, y: 177, width: 630, height: 665), xRadius: 95, yRadius: 95).fill()
NSColor(calibratedRed: 0.62, green: 0.91, blue: 0.80, alpha: 1).setFill()
NSBezierPath(roundedRect: NSRect(x: 168, y: 217, width: 630, height: 665), xRadius: 95, yRadius: 95).fill()
let font = NSFont.systemFont(ofSize: 460, weight: .semibold)
let text = "A" as NSString
let attributes: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: NSColor(calibratedRed: 0.08, green: 0.15, blue: 0.18, alpha: 1)]
let size = text.size(withAttributes: attributes)
text.draw(at: NSPoint(x: 484 - size.width / 2, y: 307), withAttributes: attributes)
NSColor(calibratedRed: 0.08, green: 0.15, blue: 0.18, alpha: 0.35).setFill()
NSBezierPath(roundedRect: NSRect(x: 260, y: 275, width: 447, height: 18), xRadius: 9, yRadius: 9).fill()
image.unlockFocus()
let bitmap = NSBitmapImageRep(data: image.tiffRepresentation!)!
try bitmap.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: CommandLine.arguments[1]))
