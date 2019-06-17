# Squad Area Network (SqAN)
[![Build Status](https://travis-ci.org/sofwerx/sqan.svg?branch=master)](https://travis-ci.org/sofwerx/sqan)

SqAN is an experimental effort to provide a Mobile Ad-hoc NETwork (MANET) in support of small, dismounted maneuver units, based solely on open source available techniques and pre-existing mobile device hardware.

#### [Compiled APK (Android app installer)](https://github.com/sofwerx/sqan/releases/)

![WARNING! ](art/caution_geek.png "WARNING! ")

_**SqAN is experimental and under active development!**_

* **No backward compatibility** is guaranteed
* No packet structure, saved file, or even IPC interface that works today is guaranteed to work the same way tomorrow
* Documentation and example project(s) in this repo may lag behind current code

## What Lives Here

This repository includes:

- SqAN itself
- SqAN Test (in the "testing" directory), a tool for conducting load testing on SqAN

SqAN is an evaluation of four possible ways to do a MANET using API level access to Android hardware:

- Nearby Connections
- WiFi Aware*
- WiFi Direct*
- Bluetooth*

Current plans are to include a VPN and intercept all traffic in a specific IP range (a user selectable range within
reserved space for private networks) and forward that traffic over SqAN to other SqAN nodes. SqAN will also likely
include some dynamic IP allocation scheme within that block to avoid the need for any user IP pre-configuration.

### Nearby Connections approach

Nearby Connections is currently utilized in the cluster configuration. A few modifications have been made to help heal broken connections. All devices in Nearby Connections remain active on advertising and discovering although future work will probably stop advertisement and discovery once the network members appear to have been identified in order to reduce the significant stability costs of continous advertising/discovery. Based on testing to date, Nearby Connections does not seem stable or performant enough to be a viable option long term.

Performance so far:
 - connections typically are taking around 1 min to stabalize
 - latency tends to be around 300ms, but a large portion of the connections so far appear to be utilizing access via a shared connection to the same WiFi router
 - high bandwidth traffic (like video) lags and becomes increasingly buffered over time
 - at or above 3 devices, the Nearby Connections mesh fragments or otherwise becomes unstable
_** Nearby Connections is not a recommended MANET approach **_

### WiFi Aware approach

WiFi Aware connections begin by simultaneously advertising and discovering. Mesh typically forms within one minute with typical formation around 10 seconds.

Performance so far:
 - like Nearby Connections, high bandwidth traffic lags and becomes increasingly buffered over time
 - Connectivity with more than 2 devices not yet tested
_** WiFi Aware is not a recommended MANET approach at this time **_

### WiFi Direct approach

WiFi Direct architecture is built on all devices performing Network Service Discovery and then one device self-selecting to be the Server with other devices then coming online as Clients.

Performance so far:
 - NSD occurs rather rapidly (typically under 15 sec)
 - Server/Client connections typically occur rapidly with the mesh forming often in under 3 seconds
 - Mesh tends to perform fairly well with high bandwidth traffic like video to include supporting one hop between producer and consumer or in supporting more than one producer
 - Stability problems appear to be mostly addressed
 - All connected devices must be within the same WiFi Direct network (i.e. there is one hub and many spokes). Spokes communicate with each other relatively seemlessly but are still dependant on connection to the same hub (which may be reassigned over time)
_** WiFi Direct is the highest performing mesh so far for high bandwidth applications**_


### Augmenting WiFi Aware and Direct

Since only Nearby Connections organically uses both WiFi and Bluetooth, a seperate Bluetooth capability has been built out for WiFi Aware and WiFi Direct meshes. The intent of this Bluetooth connectivity is to provide another, sometimes redundant mesh to enhance overall up time and to help bridge information between meshes when the WiFi Hub/Spoke models have to (either due to proximity or size) form multiple Hub/Spoke clusters. Currently, as both WiFi Aware and WiFi Direct are in testing, the Bluetooth mesh normally assigned to support these two approaches has been disabled.


### Bluetooth

Bluetooth mesh is available as a separate, stand-alone options as well and SqAN will blend data across meshes based on connectivity. Of the currently available mesh approaches, the Bluetooth mesh is the most mature at this time. The Bluetooth mesh is a constom built modified flood-based system, but is not the BLE based "Mesh Profile Specification" or "Mesh Model Specification" as these two are: 1) not yet supported natively within Android and 2) not optimized for the type of traffic anticipated within a dismounted manuever unit environment.

Performance so far:
 - After initial pairing process, Bluetooth based mesh appears to form relatively quickly, self heal consistently and maintain connectivity at distances in excess of 50m in open terrain
 - Some lower end and older devices occasionally need to be restarted after a period of use in order to access any bluetooth connection but this problem appears to extend beyond the Bluetooth mesh itself
_** Bluetooth mesh is the highest performing mesh so far for lower bandwidth, lower energy usage, multi-hop applications **_


### Software Defined Radio

_**[WARNING: prolonged use of some SDR may cause heat-related damage to the SDR]**_
SqANDR is a support library that has is being built in parallel to extend SqAN connectivity across SDRs. Currently only PlutoSDRs or any *nix SDR with AD9361 agile transceiver family of devices supporting libiio. SqANDR is currently under active development with the latested on-SDR code provided in the "pluto" directory.

### WiFiManager

An approach using WiFi Manager was explored as well. WiFiManager could be used to create a access point but that required reflection to access private APIs starting at Android 7. This capability was discontinued entirely at Android 8, so this approach was dropped as long-term unstable.


### Logging

SqAN generates a moderately detailed log of connectivity and routing decisions in the device's Documents/SqAN folder. Portions of this log are viewable from within the About page of SqAN and the most verbose logging is available in logcat. For the purposes of load testing and routing debugging, the text log files in the SqAN folder should be sufficient for most cases.


### The Testing App

The SqAN Test app found in the Testing folder serves two purposes: 1) to provide a structred way to implement various load tests and 2) to provide an easy demo of SqAN actually doing something. The SqAN Test app contains a geographic based network map of how the devices are connecting as well as a rudimentary messaging capability to communicate by text messages between the apps. The SqAN Test app also serves as an example of how to use the SqAN channel IPC to provide app-to-app communication.


## Sending data via IPC

Although SqAN is intended primarily to intercept and re-route TCP/IP traffic, SqAN can be used via Interprocess Communication (IPC) to send data from apps specifically for transmission via SqAN. To use IPC to send data over SqAN:
 - copy the "org.sofwerx.sqan.ipc.IpcBroadcastTransceiver" into the app you want to communicate with SqAN (SqAN relies on Broadcasts to communicate with other apps).
 - inside your app, call IpcBroadcastTransceiver.register() to listen for messages from SqAN. Your app's IpcBroadcastTransceiver.IpcBroadcastListener will be notified when data is received from SqAN.
 - when the app needs to broadcast something over SqAN, call IpcBroadcastTransceiver.broadcast() and pass your data. SqAN passes byte arrays so you will need to marshal/unmarshal your data on the sending and receiving ends.
 - call IpcBroadcastTransceiver.unregister() when your app no longer needs to receive messages from SqAN.

Only the IpcBroadcastTransceiver is needed to communicate with SqAN, but it may also help to include the classes in the org.sofwerx.sqan.manet.common.packet package. Classes in this package provide additional capabilities (like designating a channel for the communications) that may be helpful. However, outside apps are not allowed to send "admin" type packets (such as a ping or heartbeat) so broadcasts using these will be ignored by SqAN.
 
The SOFWERX Swe-Library project (which provides for the ability to send SOS-T compliant sensor messages over TCP/IP connections) also organically supports SqAN IPC connectivity. To use this library, visit (https://github.com/sofwerx/swe-android).
 
## Sending data via TCP/IP
 
SqAN includes a VPN capability. This capability has been marginally tested with both text and high bandwidth traffic (like video) and appears stable and performant. The VPN feature allows SqAN to route any TCP/IP traffic to another SqAN node as long as that traffic is within the forwarded IP block 169.254.x.x and also includes multicast capability for IP addresses in the 224.x.x.x, 225.x.x.x, 226.x.x.x and 239.x.x.x blocks. To use the VPN capabilty, ensure VPN is set to enabled in SqAN settings and look for the SqAN device's IPV4 hosting address that is displayed at the bottom of the About screen. SqAN device IPV4 addresses are also shared across SqAN, so the IPV4 of any connected SqAN device can be found remotely.

SqAN also provides a web server and hosts a page at the SqAN device's 169.254.x.x IP address for testing connections. If you application requires accessing web traffic, it is recommended that you disable the "Host a VPN page" setting.

## Connecting an outside (non-Android) Bluetooth Device

_**This is very experimental**_
 
These are the current steps to make an outside (i.e. non-Android) device work with the SqAN bluetooth MANET
 
### Steps to join and contribute to the SqAN bluetooth mesh as a non-SqAN device:
 
1) For one-time initial set-up, a device needs to be able to enter advertise and discovery mode at the same time that the other mesh nodes are put in this same state. The device will need a long to act as its unique ID and should also generate a type 4 UUID for future deonfliction use.
 
2) During advertising and discovery, the device should advertise (and discover for) a service UUID of "b5952d38-b23a-36f2-8935-373166703c96"; alternatively, the device may change it's name to begin with "sqan" and then be followed with a string representation of a long. The device should listen for other devices that display this naming format.
 
3) Once possible devices have been identified during discovery, the device should pair with (with user authorization) the devices and persist both their MAC address and long term key (Identity Resolving Key may not be fully/consistently implemented yet). This concludes the one-time set-up.
 
4) When the device wants to enter into the SqAN mesh, the device should cycle through the presistent saved SqAN devices and attempt to connect with each one. Simulatenously the device should also enter hub mode and accept connections from any of these saved devices.
 
5) SqAN intentionally limits active bluetooth connections (current limit is set to 4, but that fluxuates based on testing) to prevent typically limited mobile device bluetooth radios from becoming unstable. Other connected devices are encourages to limit these connections as well. Ideally, any SqAN enabled device should accept connections as a hub up to its max and limit pending and active connection attempts as a client to one less than its max. Devices should also try to prioritize connection attempts to other devices that are not yet visible on the mesh. SqAN currently builds a priority list that is organized by tiers (such as devices that have zero connections and devices that are connected but with multiple hops) with the device priority periodically randomly shifted within any one given tier.
 
6) At a minimum, any SqAN connected device should be able to send and receive a Heartbeat Packet as well as conduct routing logic on a genetic AbstractPacket and increment the hop counter within any packet. All packets are marshalled into byte arrays and do not consist of any higher level structure like JSON, XML, Protobufs, etc. Primatives are mashalled and unmarshalled in Java so any other device should handle endian-ness accordingly.
 
### A note on Bluetooth MANET security:
 
To connect, an outside bluetooth device must be able to pair and generate and retain a long term key (LTK) ideally through Numeric Comparison pairing method. The outside device must be able to advertise and discover on command in order to initially set-up connections, but will be expected to operate without advertising or discovering.
 
At this point SqAN relies solely on Bluetooth Security Level 3 to handle all pairing and Encryption in the Bluetooth Security Manager. This is implemented on the Android side through BluetoothSockets being formed from createRfcommSocketToServiceRecord calls rather than createInsecureRfcommSocketToServiceRecord calls. SqAN may someday fall back to these insecure RFC calls but that is not currently planned with the given encryption scheme.
 
SqAN has a provisional stub for authentication and encryption handled and is based on an architecture that is fairly resiliant to flooding so later security measures can be implemented without protocol changes. That being said, the current approach which does not rely on export restricted code within SqAN relies heavily on the user not taking the affirative action to pair with a device they do not trust.

### A note on Aware MANET security:

The current Aware MANET should be considered insecure. Although capabilities are stubbed-out to facilitate securing that MANET approach, they have not been implemented and the MANET should be considered to be unencrypted.
 
### *WiFi Aware™, WiFi Direct®, and Bluetooth® are trademarked, but their trade marking characters have been ommitted from the majority of this readme as both symbols render distractingly oversized in some viewers.
