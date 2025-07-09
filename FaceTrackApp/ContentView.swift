//
//  ContentView.swift
//  FaceTrackApp
//
//  Created by Guzman Family on 7/8/25.
//

import SwiftUI
import AVFoundation // Important for AVCaptureDevice.Position

struct ContentView: View {
    @State private var cameraPosition: AVCaptureDevice.Position = .front // Start with front camera

    var body: some View {
        VStack {
            CameraView(cameraPosition: $cameraPosition)
                .edgesIgnoringSafeArea(.all) // Make the camera view full screen

            Button(action: {
                // Toggle camera position
                cameraPosition = cameraPosition == .front ? .back : .front
            }) {
                Image(systemName: "arrow.triangle.2.circlepath.circle.fill")
                    .font(.largeTitle)
                    .foregroundColor(.white)
                    .padding()
                    .background(Color.black.opacity(0.6))
                    .clipShape(Circle())
            }
            .padding(.bottom, 30) // Give some space at the bottom
        }
        .background(Color.black) // Ensure background is black behind the camera view
    }
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View {
        ContentView()
    }
}
