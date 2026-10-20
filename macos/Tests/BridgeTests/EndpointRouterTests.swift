import XCTest
import Foundation
@testable import BridgeDomain
@testable import BridgeNetwork

final class EndpointRouterTests: XCTestCase {

    func testLocalFirstRouteResolution() {
        let discovery = PeerDiscoveryService()
        let store = InMemoryRemoteEndpointStore()
        let router = EndpointRouter(discoveryService: discovery, endpointStore: store)

        discovery.addDiscoveredPeer(DiscoveredPeer(id: "android-001", displayName: "Pixel", host: "192.168.1.100", port: 54884, protocolMajor: 1, protocolMinor: 0))
        store.saveEndpoint(RemoteEndpointConfig(deviceId: "android-001", tailscaleHost: "100.64.0.5", port: 54884, isEnabled: true))

        let route = router.resolveRoute(for: "android-001")
        XCTAssertNotNil(route)
        XCTAssertEqual(route?.routeType, .localLAN)
        XCTAssertEqual(route?.host, "192.168.1.100")
    }

    func testTailscaleFallbackWhenLocalDiscoveryMissing() {
        let discovery = PeerDiscoveryService()
        let store = InMemoryRemoteEndpointStore()
        let router = EndpointRouter(discoveryService: discovery, endpointStore: store)

        store.saveEndpoint(RemoteEndpointConfig(deviceId: "android-001", tailscaleHost: "phone.magicdns.net", port: 54884, isEnabled: true))

        let route = router.resolveRoute(for: "android-001")
        XCTAssertNotNil(route)
        XCTAssertEqual(route?.routeType, .remoteTailscale)
        XCTAssertEqual(route?.host, "phone.magicdns.net")
    }

    func testDisabledEndpointReturnsNil() {
        let discovery = PeerDiscoveryService()
        let store = InMemoryRemoteEndpointStore()
        let router = EndpointRouter(discoveryService: discovery, endpointStore: store)

        store.saveEndpoint(RemoteEndpointConfig(deviceId: "android-001", tailscaleHost: "100.64.0.5", port: 54884, isEnabled: false))
        XCTAssertNil(router.resolveRoute(for: "android-001"))
    }
}
