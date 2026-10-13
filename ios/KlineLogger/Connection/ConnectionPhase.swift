//
//  ConnectionPhase.swift
//  KlineLogger
//
//  Created by Douglas Rowland on 10/5/26.
//

import Foundation

enum ConnectionPhase: Equatable {
    case idle
    case scanning
    case connecting(String)
    case connected(String)
    case failed(String)

    var statusText: String {
        switch self {
        case .scanning: return "Searching for your adapter…"
        case .connecting(let name): return "Connecting to \(name)…"
        default: return ""
        }
    }

    var errorMessage: String? {
        if case .failed(let message) = self { return message }
        return nil
    }
}
