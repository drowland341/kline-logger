//
//  FormulaEvaluator.swift
//  KlineLogger
//
//  Created by Douglas Rowland on 10/5/26.
//
//  Evaluates the PID formulas from pids.json. Supported syntax mirrors
//  kline.py's formula language: +,-,*,/, parentheses, the variables
//  A..H / raw / sraw / n, and the functions abs/min/max/round/int/float/signed.

import Foundation

enum FormulaError: Error, LocalizedError {
    case syntax(String)
    case unknownVariable(String)
    case unknownFunction(String)
    case wrongArgumentCount(String)

    var errorDescription: String? {
        switch self {
        case .syntax(let s): return "syntax error: \(s)"
        case .unknownVariable(let v): return "unknown variable '\(v)'"
        case .unknownFunction(let f): return "unknown function '\(f)'"
        case .wrongArgumentCount(let f): return "wrong number of arguments for '\(f)'"
        }
    }
}

private enum FormulaToken: Equatable {
    case number(Double)
    case identifier(String)
    case plus, minus, star, slash
    case lparen, rparen, comma
}

private func tokenize(_ text: String) throws -> [FormulaToken] {
    var tokens: [FormulaToken] = []
    let chars = Array(text)
    var i = 0
    while i < chars.count {
        let c = chars[i]
        if c.isWhitespace { i += 1; continue }
        if c.isNumber || (c == "." && i + 1 < chars.count && chars[i + 1].isNumber) {
            var j = i
            while j < chars.count, chars[j].isNumber || chars[j] == "." { j += 1 }
            let numStr = String(chars[i..<j])
            guard let value = Double(numStr) else { throw FormulaError.syntax("bad number '\(numStr)'") }
            tokens.append(.number(value))
            i = j
            continue
        }
        if c.isLetter || c == "_" {
            var j = i
            while j < chars.count, chars[j].isLetter || chars[j].isNumber || chars[j] == "_" || chars[j] == "." { j += 1 }
            tokens.append(.identifier(String(chars[i..<j])))
            i = j
            continue
        }
        switch c {
        case "+": tokens.append(.plus)
        case "-": tokens.append(.minus)
        case "*": tokens.append(.star)
        case "/": tokens.append(.slash)
        case "(": tokens.append(.lparen)
        case ")": tokens.append(.rparen)
        case ",": tokens.append(.comma)
        default: throw FormulaError.syntax("unexpected character '\(c)'")
        }
        i += 1
    }
    return tokens
}

private indirect enum FormulaExpr {
    case number(Double)
    case variable(String)
    case call(String, [FormulaExpr])
    case add(FormulaExpr, FormulaExpr)
    case sub(FormulaExpr, FormulaExpr)
    case mul(FormulaExpr, FormulaExpr)
    case div(FormulaExpr, FormulaExpr)
    case neg(FormulaExpr)
}

private final class FormulaParser {
    private let tokens: [FormulaToken]
    private var pos = 0

    init(_ tokens: [FormulaToken]) { self.tokens = tokens }

    private var current: FormulaToken? { pos < tokens.count ? tokens[pos] : nil }

    func parseExpression() throws -> FormulaExpr {
        let expr = try parseAddSub()
        guard pos == tokens.count else { throw FormulaError.syntax("unexpected trailing tokens") }
        return expr
    }

    private func parseAddSub() throws -> FormulaExpr {
        var left = try parseMulDiv()
        while let t = current, t == .plus || t == .minus {
            pos += 1
            let right = try parseMulDiv()
            left = (t == .plus) ? .add(left, right) : .sub(left, right)
        }
        return left
    }

    private func parseMulDiv() throws -> FormulaExpr {
        var left = try parseUnary()
        while let t = current, t == .star || t == .slash {
            pos += 1
            let right = try parseUnary()
            left = (t == .star) ? .mul(left, right) : .div(left, right)
        }
        return left
    }

    private func parseUnary() throws -> FormulaExpr {
        if current == .minus {
            pos += 1
            return .neg(try parseUnary())
        }
        if current == .plus {
            pos += 1
            return try parseUnary()
        }
        return try parsePrimary()
    }

    private func parsePrimary() throws -> FormulaExpr {
        guard pos < tokens.count else { throw FormulaError.syntax("unexpected end of formula") }
        let token = tokens[pos]
        pos += 1
        switch token {
        case .number(let v):
            return .number(v)
        case .identifier(let name):
            if current == .lparen {
                pos += 1
                var args: [FormulaExpr] = []
                if current != .rparen {
                    args.append(try parseAddSub())
                    while current == .comma {
                        pos += 1
                        args.append(try parseAddSub())
                    }
                }
                guard current == .rparen else { throw FormulaError.syntax("missing ')'") }
                pos += 1
                return .call(name, args)
            }
            return .variable(name)
        case .lparen:
            let expr = try parseAddSub()
            guard current == .rparen else { throw FormulaError.syntax("missing ')'") }
            pos += 1
            return expr
        default:
            throw FormulaError.syntax("unexpected token")
        }
    }
}

private enum FormulaFunctions {
    static func call(_ name: String, _ args: [Double]) throws -> Double {
        switch name {
        case "abs":
            guard args.count == 1 else { throw FormulaError.wrongArgumentCount(name) }
            return abs(args[0])
        case "round":
            guard args.count == 1 else { throw FormulaError.wrongArgumentCount(name) }
            return args[0].rounded()
        case "int", "float":
            guard args.count == 1 else { throw FormulaError.wrongArgumentCount(name) }
            return args[0] < 0 ? args[0].rounded(.up) : args[0].rounded(.down)
        case "min":
            guard !args.isEmpty else { throw FormulaError.wrongArgumentCount(name) }
            return args.min()!
        case "max":
            guard !args.isEmpty else { throw FormulaError.wrongArgumentCount(name) }
            return args.max()!
        case "signed":
            guard args.count == 2 else { throw FormulaError.wrongArgumentCount(name) }
            let bits = Int(args[1])
            guard bits > 0, bits <= 64 else { throw FormulaError.wrongArgumentCount(name) }
            let mask: UInt64 = bits >= 64 ? .max : (1 << UInt64(bits)) - 1
            let value = UInt64(args[0]) & mask
            let signBit: UInt64 = 1 << UInt64(bits - 1)
            if value & signBit != 0 {
                return Double(Int64(value) - Int64(mask) - 1)
            }
            return Double(value)
        case "math.sin":
            guard args.count == 1 else { throw FormulaError.wrongArgumentCount(name) }
            return sin(args[0])
        case "math.cos":
            guard args.count == 1 else { throw FormulaError.wrongArgumentCount(name) }
            return cos(args[0])
        case "math.sqrt":
            guard args.count == 1 else { throw FormulaError.wrongArgumentCount(name) }
            return sqrt(args[0])
        default:
            throw FormulaError.unknownFunction(name)
        }
    }
}

struct CompiledFormula {
    private let expr: FormulaExpr

    init(_ source: String) throws {
        let tokens = try tokenize(source)
        expr = try FormulaParser(tokens).parseExpression()
    }

    func evaluate(variables: [String: Double]) throws -> Double {
        try Self.evaluate(expr, variables: variables)
    }

    private static func evaluate(_ expr: FormulaExpr, variables: [String: Double]) throws -> Double {
        switch expr {
        case .number(let v):
            return v
        case .variable(let name):
            if let v = variables[name] { return v }
            if name == "math.pi" { return Double.pi }
            throw FormulaError.unknownVariable(name)
        case .add(let l, let r):
            return try evaluate(l, variables: variables) + (try evaluate(r, variables: variables))
        case .sub(let l, let r):
            return try evaluate(l, variables: variables) - (try evaluate(r, variables: variables))
        case .mul(let l, let r):
            return try evaluate(l, variables: variables) * (try evaluate(r, variables: variables))
        case .div(let l, let r):
            let rv = try evaluate(r, variables: variables)
            guard rv != 0 else { return 0 }
            return try evaluate(l, variables: variables) / rv
        case .neg(let e):
            return -(try evaluate(e, variables: variables))
        case .call(let name, let args):
            let values = try args.map { try evaluate($0, variables: variables) }
            return try FormulaFunctions.call(name, values)
        }
    }
}

enum PIDByteDecoding {
    /// Builds the formula environment kline.py uses: A..H (data bytes), raw (big-endian unsigned),
    /// sraw (same, signed), n (byte count).
    static func variables(from data: [UInt8]) -> [String: Double] {
        var vars: [String: Double] = [:]
        let letters = ["A", "B", "C", "D", "E", "F", "G", "H"]
        for (i, letter) in letters.enumerated() {
            vars[letter] = i < data.count ? Double(data[i]) : 0
        }
        var raw: UInt64 = 0
        for byte in data { raw = (raw << 8) | UInt64(byte) }
        vars["raw"] = Double(raw)
        vars["n"] = Double(data.count)
        if !data.isEmpty {
            let bits = 8 * data.count
            let mask: UInt64 = bits >= 64 ? .max : (1 << UInt64(bits)) - 1
            let v = raw & mask
            let signBit: UInt64 = bits >= 64 ? (1 << 63) : (1 << UInt64(bits - 1))
            vars["sraw"] = (v & signBit != 0) ? Double(Int64(v) - Int64(mask) - 1) : Double(v)
        } else {
            vars["sraw"] = 0
        }
        return vars
    }
}
