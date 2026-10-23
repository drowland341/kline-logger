//
//  VehicleImageStore.swift
//  KlineLogger
//
//  The rider's own ski picture for the Digital dash style: picked from Photos, the background
//  removed and the result trimmed, kept as a PNG in app-private storage so it survives restarts.
//  Ported from the Android app's VehicleImage.kt/BackgroundCut.kt — which hand-rolls a flood-fill
//  background cut — using Vision's subject-lifting (`GenerateForegroundInstanceMaskRequest`)
//  instead, since it does the same job natively and more robustly on this platform.
//

import UIKit
import Vision
import CoreImage

enum VehicleImageStore {
    private static let maxSide: CGFloat = 900

    private static var fileURL: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("dash_vehicle.png")
    }

    static func load() -> UIImage? {
        guard let data = try? Data(contentsOf: fileURL) else { return nil }
        return UIImage(data: data)
    }

    static func clear() {
        try? FileManager.default.removeItem(at: fileURL)
    }

    enum ImportError: LocalizedError {
        case notAPicture
        case noSubjectFound
        case saveFailed

        var errorDescription: String? {
            switch self {
            case .notAPicture: return "That file isn't a picture the app can read."
            case .noSubjectFound: return "Couldn't find anything to cut out of that picture."
            case .saveFailed: return "Couldn't save the picture."
            }
        }
    }

    /// Cuts the background out of `data` (an image a rider picked), trims it, and saves it.
    /// Slow — call off the main thread.
    static func `import`(data: Data) async throws -> UIImage {
        guard let original = UIImage(data: data), let downsized = original.downsized(maxSide: maxSide), let cgImage = downsized.cgImage else {
            throw ImportError.notAPicture
        }

        let request = GenerateForegroundInstanceMaskRequest()
        guard let observation = try await request.perform(on: cgImage), !observation.allInstances.isEmpty else {
            throw ImportError.noSubjectFound
        }

        let handler = ImageRequestHandler(cgImage)
        let pixelBuffer = try observation.generateMaskedImage(for: observation.allInstances, imageFrom: handler, croppedToInstancesExtent: true)

        let ciImage = CIImage(cvPixelBuffer: pixelBuffer)
        let ciContext = CIContext()
        guard let outputCG = ciContext.createCGImage(ciImage, from: ciImage.extent) else { throw ImportError.saveFailed }
        let trimmed = UIImage(cgImage: outputCG)

        guard let pngData = trimmed.pngData() else { throw ImportError.saveFailed }
        try pngData.write(to: fileURL, options: .atomic)
        return trimmed
    }
}

private extension UIImage {
    /// Downsamples so the longer side is at most `maxSide`, preserving aspect ratio.
    func downsized(maxSide: CGFloat) -> UIImage? {
        let longest = Swift.max(size.width, size.height)
        guard longest > maxSide else { return self }
        let scale = maxSide / longest
        let target = CGSize(width: size.width * scale, height: size.height * scale)
        let renderer = UIGraphicsImageRenderer(size: target)
        return renderer.image { _ in draw(in: CGRect(origin: .zero, size: target)) }
    }
}
