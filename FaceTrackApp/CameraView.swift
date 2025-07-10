//
//  CameraView.swift
//
//
//  Created by Guzman Family on 7/8/25.
//

import SwiftUI
import AVFoundation
import MLKit // MLKit is used by CameraViewController, so useful to import here too.
import MLKitVision


struct CameraView: UIViewControllerRepresentable {
    @Binding var cameraPosition: AVCaptureDevice.Position

    func makeUIViewController(context: Context) -> CameraViewController {
        let vc = CameraViewController()
        vc.cameraPosition = cameraPosition
        vc.delegate = context.coordinator // Delegate is set here
        return vc
    }

    func updateUIViewController(_ uiViewController: CameraViewController, context: Context) {
        // Only update if the binding value has actually changed
        if uiViewController.cameraPosition != cameraPosition {
            uiViewController.switchCamera(to: cameraPosition)
        }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    // MARK: - Coordinator Class
    // This class acts as the delegate for CameraViewController,
    // allowing it to send messages back to the SwiftUI CameraView.
    class Coordinator: NSObject, CameraViewControllerDelegate {
        var parent: CameraView

        init(_ parent: CameraView) {
            self.parent = parent
        }

        // Example of a delegate method you might implement here:
        // func cameraDidStartRunning() {
        //     print("CameraViewController reported it started running.")
        // }
    }
}

// MARK: - CameraViewControllerDelegate Protocol
// Defines methods that CameraViewController can call on its delegate (the Coordinator).
protocol CameraViewControllerDelegate: AnyObject {
    // Add methods here if CameraViewController needs to communicate back to SwiftUI.
    // For example:
    // func didDetectFaces(count: Int)
    // func cameraDidEncounterError(_ error: Error)
}

//
//  CameraViewController.swift
//
//
//  Created by Guzman Family on 7/8/25.
//

class CameraViewController: UIViewController, AVCaptureVideoDataOutputSampleBufferDelegate {

    // MARK: - UI Elements
    private var overlayView = FaceOverlayView()
    private var previewLayer: AVCaptureVideoPreviewLayer!

    // MARK: - AVFoundation Properties
    private var captureSession: AVCaptureSession! // Initialized in viewDidLoad
    // private let faceDetector = FaceDetector.faceDetector() // Old line

    // MARK: - ML Kit FaceDetector Configuration
    private let faceDetector: FaceDetector = {
        let options = FaceDetectorOptions()

        // Key Changes for your use case:
        options.performanceMode = .fast      // Prioritize speed for real-time single-face tracking.
        options.minFaceSize = 0.25           // Increase this if faces are always large and close.
                                             // Adjust this value based on how "close" the face will be.

        // Important for contours:
        options.contourMode = .all           // Still crucial for getting all contour points.

        // REMOVE THIS LINE:
        // options.trackingEnabled = false     // This line causes the error when contourMode is .all

        // Keep these if you need them, otherwise set to .none for performance:
        options.landmarkMode = .none         // Set to .none if you only care about contours.
        options.classificationMode = .none   // Definitely set to .none if you don't need smile/eye-open detection.

        return FaceDetector.faceDetector(options: options)
    }()

    // MARK: - State Properties
    private var videoSize: CGSize = .zero // Stores dimensions of the raw video buffer
    var cameraPosition: AVCaptureDevice.Position = .front {
        didSet {
            // The actual camera switching is triggered by updateUIViewController in CameraView
            // calling switchCamera(to:), so no direct setupCamera call here.
        }
    }
    private var isMirrored: Bool = false // Tracks if the current camera output is horizontally mirrored

    // MARK: - Delegate Property
    // Allows communication back to the SwiftUI CameraView via its Coordinator
    weak var delegate: CameraViewControllerDelegate?

    // MARK: - View Lifecycle
    override func viewDidLoad() {
        super.viewDidLoad()

        // Initialize captureSession and previewLayer early
        // This ensures previewLayer has a non-nil session from the start.
        captureSession = AVCaptureSession()
        previewLayer = AVCaptureVideoPreviewLayer(session: captureSession)

        // Configure and add previewLayer
        previewLayer.frame = view.bounds
        previewLayer.videoGravity = .resizeAspectFill // Fills the layer, potentially cropping
        view.layer.addSublayer(previewLayer) // Add previewLayer first (behind overlay)

        // Configure and add overlayView
        overlayView.frame = view.bounds
        overlayView.backgroundColor = .clear // Make it transparent so camera feed is visible
        view.addSubview(overlayView) // Add overlayView on top

        // Initial camera setup
        setupCamera(position: cameraPosition)
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        // Ensure previewLayer and overlayView frames are updated on device rotation or size changes
        previewLayer.frame = view.bounds
        overlayView.frame = view.bounds
        overlayView.setNeedsDisplay() // Redraw overlay to match new bounds
    }

    // MARK: - Camera Setup
    func setupCamera(position: AVCaptureDevice.Position) {
        // Perform camera setup on a background queue to keep UI responsive
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self else { return }

            // Stop any running session cleanly before reconfiguring
            if self.captureSession.isRunning {
                self.captureSession.stopRunning()
            }
            // Clear all existing inputs and outputs for a fresh setup
            self.captureSession.inputs.forEach { self.captureSession.removeInput($0) }
            self.captureSession.outputs.forEach { self.captureSession.removeOutput($0) }


            // MARK: - Configure Camera Input
            guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position) else {
                print("Failed to get camera for position: \(position)")
                return
            }
            guard let input = try? AVCaptureDeviceInput(device: camera) else {
                print("Failed to create AVCaptureDeviceInput for camera: \(camera)")
                return
            }
            if self.captureSession.canAddInput(input) {
                self.captureSession.addInput(input)
            } else {
                print("Could not add camera input to the session")
                return
            }

            self.isMirrored = camera.position == .front // Determine mirroring state based on camera position

            // Get video dimensions from the camera's active format for coordinate conversion
            let dimensions = CMVideoFormatDescriptionGetDimensions(camera.activeFormat.formatDescription)
            self.videoSize = CGSize(width: CGFloat(dimensions.width), height: CGFloat(dimensions.height))

            // MARK: - Configure Video Data Output (for MLKit)
            let output = AVCaptureVideoDataOutput()
            // Set delegate to process sample buffers on a dedicated queue
            output.setSampleBufferDelegate(self, queue: DispatchQueue(label: "cameraQueue", qos: .userInteractive))
            // Set pixel format to BGRA for MLKit compatibility and performance
            output.videoSettings = [(kCVPixelBufferPixelFormatTypeKey as String): kCVPixelFormatType_32BGRA]

            if self.captureSession.canAddOutput(output) {
                self.captureSession.addOutput(output) // Correct method: addOutput
            } else {
                print("Could not add video data output to the session")
                return
            }

            // MARK: - Configure Video Connection Orientation
            // Ensure the video feed is in portrait orientation for consistent MLKit processing
            if let videoConnection = output.connection(with: .video) {
                if #available(iOS 17.0, *) {
                    if videoConnection.isVideoRotationAngleSupported(0) {
                        videoConnection.videoRotationAngle = 0 // Set to portrait angle
                    } else {
                        print("Video rotation angle 0 is not supported for this connection.")
                    }
                } else {
                    if videoConnection.isVideoOrientationSupported {
                        videoConnection.videoOrientation = .portrait // Fallback for older iOS
                    }
                }
            }

            // MARK: - Start Session and Update UI
            self.captureSession.startRunning()

            // Update UI elements on the main thread after session starts
            DispatchQueue.main.async {
                // The previewLayer.session property was already set in viewDidLoad, no need to re-assign.
                self.overlayView.isMirrored = self.isMirrored // Update mirroring state in overlay
                self.overlayView.videoSize = self.videoSize // Update video size in overlay
                self.overlayView.setNeedsDisplay() // Trigger redraw with new parameters
                print("Camera started: \(position == .front ? "Front" : "Back"), Mirrored: \(self.isMirrored), Video Size: \(self.videoSize)")
            }
        }
    }

    // MARK: - Camera Control
    func switchCamera(to newPosition: AVCaptureDevice.Position) {
        guard cameraPosition != newPosition else { return } // Only switch if position changed
        cameraPosition = newPosition // Update internal state
        setupCamera(position: newPosition) // Reconfigure camera for new position
    }

    // MARK: - AVCaptureVideoDataOutputSampleBufferDelegate
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        let visionImage = VisionImage(buffer: sampleBuffer)
        visionImage.orientation = .right

        faceDetector.process(visionImage) { [weak self] faces, error in
            guard let self = self else { return }

            if let faces = faces {
                print("ML Kit detected \(faces.count) faces.")
                if let firstFace = faces.first {
                    print("First face bounding box: \(firstFace.frame)")
                }
            } else if let error = error {
                print("Face detection error: \(error.localizedDescription)")
            } else {
                print("ML Kit detected 0 faces (or faces is nil and no error).")
            }

            guard error == nil, let faces = faces else {
                DispatchQueue.main.async {
                    self.overlayView.update(faces: [], videoSize: self.videoSize, isMirrored: self.isMirrored)
                }
                if let error = error {
                    print("Face detection error: \(error.localizedDescription)")
                }
                return
            }

            DispatchQueue.main.async {
                print("Updating overlayView. videoSize: \(self.videoSize), isMirrored: \(self.isMirrored)")
                self.overlayView.update(faces: faces, videoSize: self.videoSize, isMirrored: self.isMirrored)
            }
        }
    }
}
