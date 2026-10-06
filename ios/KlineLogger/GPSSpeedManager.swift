//
//  GPSSpeedManager.swift
//  KlineLogger
//
//  Created by Douglas Rowland on 10/6/26.
//

import CoreLocation
import Observation

@Observable
final class GPSSpeedManager {
    private(set) var speedMPH: Double?
    private(set) var authorizationStatus: CLAuthorizationStatus = .notDetermined
    private(set) var errorMessage: String?

    private let locationManager = CLLocationManager()
    private var updateTask: Task<Void, Never>?

    func start() {
        authorizationStatus = locationManager.authorizationStatus
        if authorizationStatus == .notDetermined {
            locationManager.requestWhenInUseAuthorization()
        }
        guard updateTask == nil else { return }
        updateTask = Task { [weak self] in
            guard let self else { return }
            do {
                for try await update in CLLocationUpdate.liveUpdates() {
                    guard !Task.isCancelled else { return }
                    self.authorizationStatus = self.locationManager.authorizationStatus
                    if let location = update.location {
                        self.speedMPH = location.speed >= 0 ? location.speed * 2.2369362921 : nil
                    }
                }
            } catch {
                self.errorMessage = error.localizedDescription
            }
        }
    }

    func stop() {
        updateTask?.cancel()
        updateTask = nil
    }
}
