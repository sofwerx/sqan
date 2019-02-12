# Squad Area Network (SqAN)

SqAN is an experimental effort to provide a Mobile Adhoc NETwork (MANET) is support of small, dismounted maneuver units based solely on open source available techniques and pre-existing on-device hardware. This repository includes:

- SqAN itself
- SqAN Test (in the "testing" directory), a tool for conducting load testing on SqAN

SqAN is an evaluation of three possible ways to do a MANET using API level access to Android hardware:

- Nearby Connections
- WiFi Aware*
- WiFi Direct*

Current plans are to include a VPN and intercept all traffic in a specific IP range (a user selectable range within
reserved space for private networks) and forward that traffic over SqAN to other SqAN nodes. SqAN will also likely
include some dynamic IP allocation scheme within that block to avoid the need for any user IP pre-configuration.

## Nearby Connections approach

Nearby Connections is currently utilized in the cluster configuration. A few modifications have been made to help heal broken connections. All devices in Nearby Connections remain active on advertising and discovering although future work will probably stop advertisement and discovery once the network members appear to have been identified in order to reduce the significant stability costs of continous advertising/discovery.

Performance so far:
 - connections typically are taking around 1 min to fully stabalize
 - latency tends to be around 300ms, but a large portion of the connections so far appear to be utilizing access via a shared connection to the same WiFi router
 - no significant load testing has been conducted at this point

## WiFi Aware approach

WiFi Aware connections begin by the device subscribing and waiting a fixed amount (currently 1 min) for any device advertizing SqAN. If the device does not find an advertizer during that time, the device assumes the hub role, stops discovery, and begins advertising.

Performance so far:
 - this capability is still under development and is temptoraily blocked by some hardware issues

## WiFi Direct approach

WiFi Direct architecture is still under development but build on all devices performing Network Service Discovery and then one device self-selecting to be the Server with other devices then coming online as Clients.

Performance so far:
 - although occssional stability issues exist on start-up, possibly related to debugging starting and stopping, NSD occurs rather rapidly (typically under 15 sec)
 - Server/Client connections typically occur rapidly with the mesh forming often in under 3 seconds
 - Messages originating from the server or broadcast by the server have a bit (1-3 seconds) extra latency as the server queues outgoing messages but does not immediately burst them. This will need evaluation to see if the latency hit is worth the battery saving

## Augmenting WiFi Aware and Direct

Since only Nearby Connections organically uses both WiFi and Bluetooth, a seperate Bluetooth capability will be built out for WiFi Aware and WiFi Direct meshes. The intent of this Bluetooth connectivity is to provide another, sometimes redundant mesh to enhance overall up time and to help bridge information between meshes when the WiFi Hub/Spoke models have to (either due to proximity or size) form multiple Hub/Spoke clusters.

Initial bluetooth focus will be on experimental efforts with the less frequently used bluetooth high speed protocol as a way to provide a larger pipe between seperated clusters with emphasis on bandwidth over number of connections.

## WiFiManager

An approach using WiFi Manager was explored as well. WiFiManager could be used to create a access point but that required reflection to access private APIs starting at Android 7. This capability was discontinued entirely at Android 8, so this approach was dropped as long-term unstable.

# Sending data via IPC

Although SqAN is intended primarily to intercept and re-route TCP/IP traffic, SqAN can be used via Interprocess Communication (IPC) to send data from apps specifically for transmission via SqAN. To use IPC to send data over SqAN:
 - copy the "org.sofwerx.sqan.ipc.IpcBroadcastTransceiver" into the app you want to communicate with SqAN (SqAN relies on Broadcasts to communicate with other apps).
 - inside your app, call IpcBroadcastTransceiver.register() to listen for messages from SqAN. Your app's IpcBroadcastTransceiver.IpcBroadcastListener will be notified when data is received from SqAN.
 - when the app needs to broadcast something over SqAN, call IpcBroadcastTransceiver.broadcast() and pass your data. SqAN passes byte arrays so you will need to marshal/unmarshal your data on the sending and receiving ends. 
 - call IpcBroadcastTransceiver.unregister() when your app no longer needs to receive messages from SqAN.
 
 Only the IpcBroadcastTransceiver is needed to communicate with SqAN, but it may also help to include the classes in the org.sofwerx.sqan.manet.common.packet package. Classes in this package provide additional capabilities (like designating a channel for the communications) that may be helpful. However, outside apps are not allowed to send "admin" type packets (such as a ping or heartbeat) so broadcasts using these will be ignored by SqAN.
 
 ### *WiFi Aware™ and WiFi Direct® are trademarked, but their trade marking characters have been ommitted from the majority of this readme as both symbols render distractingly large in some readme viewers.