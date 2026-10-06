//
//  ConnectingView.swift
//  KlineLogger
//
//  Created by Douglas Rowland on 10/4/26.
//

import SwiftUI

struct ConnectingView: View {
    var statusText: String
    var onCancel: () -> Void

    @State private var isPulsing = false

    var body: some View {
        VStack(spacing: 28) {
            Spacer()

            ZStack {
                Circle()
                    .fill(Color.accentColor.opacity(0.15))
                    .frame(width: 160, height: 160)
                    .scaleEffect(isPulsing ? 1.15 : 0.9)

                Image(systemName: "antenna.radiowaves.left.and.right")
                    .font(.system(size: 52))
                    .foregroundStyle(.tint)
            }
            .animation(.easeInOut(duration: 1.1).repeatForever(autoreverses: true), value: isPulsing)
            .onAppear { isPulsing = true }

            Text(statusText)
                .font(.headline)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)

            ProgressView()
                .controlSize(.large)

            Spacer()

            Button("Cancel", role: .cancel, action: onCancel)
                .buttonStyle(.bordered)
                .controlSize(.large)
                .padding(.bottom, 32)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

#Preview {
    ConnectingView(statusText: "Searching for your Veepeak adapter…", onCancel: {})
}
