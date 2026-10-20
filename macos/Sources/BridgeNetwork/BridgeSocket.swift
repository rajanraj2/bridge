import Foundation
import BridgeDomain
import BridgeProtocol
import SwiftProtobuf

#if os(Linux)
import Glibc
#else
import Darwin
#endif

public protocol SocketEventListener: AnyObject, Sendable {
    func onConnected(remoteAddress: String)
    func onEnvelopeReceived(envelope: Bridge_Envelope)
    func onDisconnected(reason: String)
}

public final class BridgeSocketConnection: @unchecked Sendable {
    private let fd: Int32
    private var isRunning: Bool = true
    private let lock = NSLock()
    public weak var listener: SocketEventListener?

    public init(fd: Int32, listener: SocketEventListener? = nil) {
        self.fd = fd
        self.listener = listener
    }

    public func startReading() {
        let thread = Thread { [weak self] in
            guard let self = self else { return }
            var buffer = Data()

            while self.isRunning {
                var chunk = [UInt8](repeating: 0, count: 4096)
                let bytesRead = read(self.fd, &chunk, chunk.count)
                if bytesRead <= 0 {
                    break
                }
                buffer.append(contentsOf: chunk.prefix(bytesRead))

                while true {
                    do {
                        guard let result = try WireFrame.decode(from: buffer) else {
                            break // Incomplete frame, await more data
                        }
                        buffer.removeSubrange(0..<result.consumed)
                        if let envelope = try? Bridge_Envelope(serializedBytes: result.payload) {
                            self.listener?.onEnvelopeReceived(envelope: envelope)
                        }
                    } catch {
                        self.closeConnection()
                        return
                    }
                }
            }
            self.listener?.onDisconnected(reason: "Remote closed connection")
            self.closeConnection()
        }
        thread.name = "BridgeSocketConnection-Reader"
        thread.start()
    }

    @discardableResult
    public func send(envelope: Bridge_Envelope) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard isRunning else { return false }

        do {
            let data = try envelope.serializedData()
            let framed = try WireFrame.encode(payload: data)
            let count = framed.count
            var sent = 0

            try framed.withUnsafeBytes { ptr in
                guard let base = ptr.baseAddress else { return }
                while sent < count {
                    let res = write(self.fd, base.advanced(by: sent), count - sent)
                    if res <= 0 {
                        throw NSError(domain: "SocketWrite", code: -1)
                    }
                    sent += res
                }
            }
            return true
        } catch {
            closeConnection()
            return false
        }
    }

    public func closeConnection() {
        lock.lock()
        defer { lock.unlock() }
        if isRunning {
            isRunning = false
            #if os(Linux)
            _ = Glibc.close(fd)
            #else
            _ = Darwin.close(fd)
            #endif
        }
    }
}

public final class BridgeSocketServer: @unchecked Sendable {
    public let port: Int
    private var serverFd: Int32 = -1
    private var isRunning: Bool = false
    private let lock = NSLock()
    private var activeConnection: BridgeSocketConnection?
    public weak var listener: SocketEventListener?

    public init(port: Int = 54884) {
        self.port = port
    }

    public func start() throws {
        lock.lock()
        defer { lock.unlock() }
        guard !isRunning else { return }

        #if os(Linux)
        let sock = socket(AF_INET, Int32(SOCK_STREAM.rawValue), 0)
        #else
        let sock = socket(AF_INET, SOCK_STREAM, 0)
        #endif
        guard sock >= 0 else {
            throw NSError(domain: "SocketError", code: 1, userInfo: [NSLocalizedDescriptionKey: "Failed to create socket"])
        }

        var opt: Int32 = 1
        setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, &opt, socklen_t(MemoryLayout<Int32>.size))

        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = UInt16(port).bigEndian
        addr.sin_addr.s_addr = in_addr_t(0) // INADDR_ANY

        let bindRes = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                bind(sock, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bindRes == 0 else {
            #if os(Linux)
            _ = Glibc.close(sock)
            #else
            _ = Darwin.close(sock)
            #endif
            throw NSError(domain: "SocketError", code: 2, userInfo: [NSLocalizedDescriptionKey: "Failed to bind port \(port)"])
        }

        listen(sock, 5)
        serverFd = sock
        isRunning = true

        let thread = Thread { [weak self] in
            while let self = self, self.isRunning {
                var clientAddr = sockaddr_in()
                var clientLen = socklen_t(MemoryLayout<sockaddr_in>.size)
                let clientFd = withUnsafeMutablePointer(to: &clientAddr) {
                    $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                        accept(sock, $0, &clientLen)
                    }
                }
                guard clientFd >= 0 else { break }

                let ipStr = String(cString: inet_ntoa(clientAddr.sin_addr))
                let conn = BridgeSocketConnection(fd: clientFd, listener: self.listener)
                self.lock.lock()
                self.activeConnection?.closeConnection()
                self.activeConnection = conn
                self.lock.unlock()

                self.listener?.onConnected(remoteAddress: ipStr)
                conn.startReading()
            }
        }
        thread.name = "BridgeSocketServer-Acceptor"
        thread.start()
    }

    public func send(envelope: Bridge_Envelope) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return activeConnection?.send(envelope: envelope) ?? false
    }

    public func stop() {
        lock.lock()
        defer { lock.unlock() }
        if isRunning {
            isRunning = false
            activeConnection?.closeConnection()
            activeConnection = nil
            #if os(Linux)
            _ = Glibc.close(serverFd)
            #else
            _ = Darwin.close(serverFd)
            #endif
            serverFd = -1
        }
    }

    /// Discovers real local IPv4 addresses (Wi-Fi, Ethernet, or Tailscale)
    public static func getLocalIpAddresses() -> [String] {
        var addresses: [String] = []
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let firstAddr = ifaddr else { return addresses }
        defer { freeifaddrs(ifaddr) }

        var ptr: UnsafeMutablePointer<ifaddrs>? = firstAddr
        while let current = ptr {
            let flags = Int(current.pointee.ifa_flags)
            let isUp = (flags & Int(IFF_UP)) != 0
            let isLoopback = (flags & Int(IFF_LOOPBACK)) != 0
            if isUp && !isLoopback && current.pointee.ifa_addr != nil {
                let family = current.pointee.ifa_addr.pointee.sa_family
                if family == UInt8(AF_INET) {
                    var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                    let res = getnameinfo(
                        current.pointee.ifa_addr,
                        socklen_t(current.pointee.ifa_addr.pointee.sa_len_compatible),
                        &hostname,
                        socklen_t(hostname.count),
                        nil,
                        0,
                        NI_NUMERICHOST
                    )
                    if res == 0 {
                        addresses.append(String(cString: hostname))
                    }
                }
            }
            ptr = current.pointee.ifa_next
        }
        return addresses
    }
}

public final class BridgeSocketClient: @unchecked Sendable {
    private var connection: BridgeSocketConnection?
    private let lock = NSLock()
    public weak var listener: SocketEventListener?

    public init(listener: SocketEventListener? = nil) {
        self.listener = listener
    }

    public func connect(host: String, port: Int = 54884) throws {
        lock.lock()
        defer { lock.unlock() }
        connection?.closeConnection()

        #if os(Linux)
        let sock = socket(AF_INET, Int32(SOCK_STREAM.rawValue), 0)
        #else
        let sock = socket(AF_INET, SOCK_STREAM, 0)
        #endif
        guard sock >= 0 else {
            throw NSError(domain: "SocketError", code: 1, userInfo: [NSLocalizedDescriptionKey: "Failed to create client socket"])
        }

        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = UInt16(port).bigEndian

        inet_pton(AF_INET, host, &addr.sin_addr)

        let connectRes = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin_or_Linux_connect(sock, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }

        guard connectRes == 0 else {
            #if os(Linux)
            _ = Glibc.close(sock)
            #else
            _ = Darwin.close(sock)
            #endif
            throw NSError(domain: "SocketError", code: 2, userInfo: [NSLocalizedDescriptionKey: "Failed to connect to \(host):\(port)"])
        }

        let conn = BridgeSocketConnection(fd: sock, listener: listener)
        self.connection = conn
        listener?.onConnected(remoteAddress: host)
        conn.startReading()
    }

    public func send(envelope: Bridge_Envelope) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return connection?.send(envelope: envelope) ?? false
    }

    public func disconnect() {
        lock.lock()
        defer { lock.unlock() }
        connection?.closeConnection()
        connection = nil
    }
}

private func Darwin_or_Linux_connect(_ fd: Int32, _ addr: UnsafePointer<sockaddr>, _ len: socklen_t) -> Int32 {
    #if os(Linux)
    return Glibc.connect(fd, addr, len)
    #else
    return Darwin.connect(fd, addr, len)
    #endif
}

private extension sockaddr {
    var sa_len_compatible: Int {
        #if os(Linux)
        return MemoryLayout<sockaddr_in>.size
        #else
        return Int(self.sa_len)
        #endif
    }
}
