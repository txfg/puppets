//
//  FaceOverlayView.swift
//  FaceTrackApp
//
//  Created by Guzman Family on 7/9/25.
//

import UIKit
import MLKit

class FaceOverlayView: UIView {
    var faces: [Face] = []
    var videoSize: CGSize = .zero // Dimensions of the raw video buffer (e.g., 1920x1080 for landscape)
    var isMirrored: Bool = false // True if the current camera output is intended to be horizontally flipped (like front camera)



    override func draw(_ rect: CGRect) {
        // --- ADD THIS PRINT STATEMENT ---
        print("FaceOverlayView: draw(_:) called. Number of faces: \(faces.count)")
        // --- END ADDITION ---

        guard let context = UIGraphicsGetCurrentContext() else { return }
        context.clear(rect)

        context.setStrokeColor(UIColor.green.cgColor)
        context.setLineWidth(1.5)

        for face in faces {
          
            let contoursToDraw: [FaceContourType] = [
                .face,
                .leftEyebrowTop, .leftEyebrowBottom,
                .rightEyebrowTop, .rightEyebrowBottom,
                .leftEye, .rightEye,
                .noseBridge, .noseBottom,
                .upperLipTop, .upperLipBottom,
                .lowerLipTop, .lowerLipBottom,
                .leftCheek, .rightCheek
            ]

            for contourType in contoursToDraw {
                if let contour = face.contour(ofType: contourType) {
                    // --- ADD THIS PRINT STATEMENT ---
                    print("    Found contour: \(contourType), points count: \(contour.points.count)")
                    // --- END ADDITION ---

                    guard let firstVisionPoint = contour.points.first else { continue }

                    let path = UIBezierPath()
                    let firstCGPoint = convertPoint(fromVisionPoint: firstVisionPoint)
                    path.move(to: firstCGPoint)

                    for i in 1..<contour.points.count {
                        let nextVisionPoint = contour.points[i]
                        let nextCGPoint = convertPoint(fromVisionPoint: nextVisionPoint)
                        path.addLine(to: nextCGPoint)
                    }

                    if contourType == .face || contourType == .leftEye || contourType == .rightEye ||
                       contourType == .upperLipTop || contourType == .upperLipBottom ||
                       contourType == .lowerLipTop || contourType == .lowerLipBottom {
                        path.close()
                    }

                    path.stroke()
                } else {
                    // --- ADD THIS PRINT STATEMENT ---
                    print("    Contour \(contourType) not found for this face.")
                    // --- END ADDITION ---
                }
            }

            // --- Optional: Draw key landmarks as dots ---
            context.setFillColor(UIColor.blue.cgColor)
            let dotRadius: CGFloat = 2.0

            if let noseBase = face.landmark(ofType: .noseBase) {
                let center = convertPoint(fromVisionPoint: noseBase.position)
                let dotRect = CGRect(x: center.x - dotRadius, y: center.y - dotRadius, width: dotRadius * 2, height: dotRadius * 2)
                context.fillEllipse(in: dotRect)
                // --- ADD THIS PRINT STATEMENT ---
                print("    NoseBase VisionPoint: (\(noseBase.position.x), \(noseBase.position.y)) -> Converted CGPoint: \(center)")
                // --- END ADDITION ---
            }
        }
    }

    func update(faces: [Face], videoSize: CGSize, isMirrored: Bool) {
        self.faces = faces
        self.videoSize = videoSize
        self.isMirrored = isMirrored
        setNeedsDisplay() // Triggers draw(_:) on the main thread
    }

    func convertRect(fromImageRect rect: CGRect) -> CGRect {
        guard videoSize != .zero else { return .zero }

        let viewWidth = bounds.width
        let viewHeight = bounds.height

        // MLKit's VisionImage.orientation = .right means:
        // MLKit's X-axis corresponds to original video's Y-axis
        // MLKit's Y-axis corresponds to original video's X-axis
        // So, the effective dimensions MLKit is working on are (videoHeight, videoWidth)
        let mlKitImageWidth = videoSize.height  // e.g., 1080 for 1920x1080 landscape video
        let mlKitImageHeight = videoSize.width // e.g., 1920 for 1920x1080 landscape video

        // Determine content size and offset based on .resizeAspectFill
        var contentWidth: CGFloat
        var contentHeight: CGFloat
        let viewAspectRatio = viewWidth / viewHeight
        let mlKitImageAspectRatio = mlKitImageWidth / mlKitImageHeight

        if mlKitImageAspectRatio > viewAspectRatio {
            contentWidth = viewWidth
            contentHeight = viewWidth / mlKitImageAspectRatio
        } else {
            contentHeight = viewHeight
            contentWidth = viewHeight * mlKitImageAspectRatio
        }

        let offsetX = (viewWidth - contentWidth) / 2.0
        let offsetY = (viewHeight - contentHeight) / 2.0

        // Step 1: Scale the MLKit rect's coordinates to the content area
        // MLKit's Y (vertical in its rotated space) maps to the screen's X
        var transformedX = rect.origin.y * (contentWidth / mlKitImageWidth)
        // MLKit's X (horizontal in its rotated space) maps to the screen's Y
        var transformedY = rect.origin.x * (contentHeight / mlKitImageHeight)

        let transformedWidth = rect.size.height * (contentWidth / mlKitImageWidth)
        let transformedHeight = rect.size.width * (contentHeight / mlKitImageHeight)


        // Step 2: Apply the "Mirror" transformation for the X-axis selectively.
        // Based on testing, the front camera (`isMirrored == true`) is already mirrored by the preview.
        // So, we only need to flip X for the back camera (`!isMirrored`).
        if !isMirrored {
            transformedX = contentWidth - transformedX - transformedWidth
        }

        // Step 3: Y-axis inversion.
        // Based on testing, this flip is NOT needed for this specific setup.
        // transformedY = contentHeight - transformedY - transformedHeight


        // Step 4: Apply the offsets to position within the view
        transformedX += offsetX
        transformedY += offsetY

        return CGRect(x: transformedX, y: transformedY, width: transformedWidth, height: transformedHeight)
    }
    
    func convertPoint(fromVisionPoint visionPoint: VisionPoint) -> CGPoint {
        guard videoSize != .zero else { return .zero }

        let viewWidth = bounds.width
        let viewHeight = bounds.height

        // These dimensions correspond to how MLKit interprets the rotated image data.
        // videoSize.height is the width of MLKit's internal image (after orientation .right)
        // videoSize.width is the height of MLKit's internal image (after orientation .right)
        let mlKitImageWidth = videoSize.height
        let mlKitImageHeight = videoSize.width

        // Determine content size and offset based on .resizeAspectFill
        var contentWidth: CGFloat
        var contentHeight: CGFloat
        let viewAspectRatio = viewWidth / viewHeight
        let mlKitImageAspectRatio = mlKitImageWidth / mlKitImageHeight

        if mlKitImageAspectRatio > viewAspectRatio {
            contentWidth = viewWidth
            contentHeight = viewWidth / mlKitImageAspectRatio
        } else {
            contentHeight = viewHeight
            contentWidth = viewHeight * mlKitImageAspectRatio
        }

        let offsetX = (viewWidth - contentWidth) / 2.0
        let offsetY = (viewHeight - contentHeight) / 2.0

        // Scale the MLKit point's coordinates to the content area
        // MLKit's Y (vertical in its rotated space) maps to the screen's X
        var transformedX = CGFloat(visionPoint.y) * (contentWidth / mlKitImageWidth)
        // MLKit's X (horizontal in its rotated space) maps to the screen's Y
        var transformedY = CGFloat(visionPoint.x) * (contentHeight / mlKitImageHeight)


        // Apply the "Mirror" transformation for the X-axis selectively (from your existing logic).
        // If !isMirrored, flip horizontally within the content area.
        if !isMirrored {
            transformedX = contentWidth - transformedX
        }

        // Y-axis inversion. Based on your previous testing, this flip is NOT needed.
        // If it later appears inverted, you might re-enable:
        // transformedY = contentHeight - transformedY

        // Apply the offsets to position within the view
        transformedX += offsetX
        transformedY += offsetY
        
        print("      convertPoint: Input VisionPoint (\(visionPoint.x), \(visionPoint.y)) -> Output CGPoint (\(transformedX), \(transformedY))")

        return CGPoint(x: transformedX, y: transformedY)
    }
}
