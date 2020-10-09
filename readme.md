# Squad Area Network (SqAN)
[![Build Status](https://travis-ci.org/sofwerx/sqan.svg?branch=master)](https://travis-ci.org/sofwerx/sqan)

SqAN is an experimental effort to provide a Mobile Ad-hoc NETwork (MANET) in support of small, dismounted maneuver units, based solely on open source available techniques and pre-existing mobile device hardware.

#### [Compiled APK (Android app installer)](https://github.com/sofwerx/sqan/releases/)

![SCREENSHOT1 ](art/screenshots/a.png "SCREENSHOT1 ")   ![SCREENSHOT2 ](art/screenshots/b.jpg "SCREENSHOT2 ")

_**SqAN is experimental and under active development!**_

![WARNING! ](art/caution_geek.png "WARNING! ")

* **No backward compatibility** is guaranteed
* No packet structure, saved file, or even IPC interface that works today is guaranteed to work the same way tomorrow
* Documentation and example project(s) in this repo may lag behind current code

## What Lives Here

This repository includes:

- SqAN itself
- SqAN Test (in the "testing" directory), a tool for conducting load testing on SqAN
- SqANDR Test (in the "testing" directory), a tool for tuning the on-SDR software

SqAN is an evaluation of four possible ways to do a MANET using API level access to Android hardware:

- Nearby Connections
- WiFi Aware*
- WiFi Direct*
- Bluetooth*

SqAN includes VPN and intercepts all traffic in a specific IP range and forward that traffic over SqAN to other SqAN nodes. SqAN also
includes a dynamic IP allocation scheme within that block to avoid the need for any user IP pre-configuration.

### Nearby Connections approach

Nearby Connections is utilized in the cluster configuration. A few modifications have been made to help heal broken connections. All devices in Nearby Connections remain active on advertising and discovering although future work will probably stop advertisement and discovery once the network members appear to have been identified in order to reduce the significant stability costs of continous advertising/discovery. Based on testing to date, Nearby Connections does not seem stable or performant enough to be a viable option long term.

Performance:
 - connections typically are taking around 1 min to stabilize
 - latency tends to be around 300ms, but a large portion of the connections so far appear to be utilizing access via a shared connection to the same WiFi router
 - high bandwidth traffic (like video) lags and becomes increasingly buffered over time
 - at or above 3 devices, the Nearby Connections mesh fragments or otherwise becomes unstable

_**Nearby Connections is not a recommended MANET approach**_

### WiFi Aware approach

WiFi Aware connections begin by simultaneously advertising and discovering. Mesh typically forms within one minute with typical formation around 10 seconds.

Performance:
 - like Nearby Connections, high bandwidth traffic lags and becomes increasingly buffered over time
 - Due to poor P2P performance, connectivity with more than 2 devices not heavily tested
 
_**WiFi Aware is not a recommended MANET approach**_

### WiFi Direct approach

WiFi Direct architecture is built on all devices performing Network Service Discovery and then one device self-selecting to be the Server with other devices then coming online as Clients. Of note, WiFi Direct can be used while the device is still connected to other WiFi networks.

Performance:
 - NSD occurs rather rapidly (typically under 15 sec)
 - Server/Client connections typically occur rapidly with the mesh forming sometimes in under 3 seconds with more normal cold formations around 10 seconds
 - Mesh tends to perform fairly well with high bandwidth traffic like video to include supporting one hop between producer and consumer or in supporting more than one producer
 - Stability problems appear to be mostly addressed
 - Mesh reforms automatically
 - All connected devices must be within the same WiFi Direct network (i.e. there is one hub and many spokes). Spokes communicate with each other relatively seemlessly but are still dependant on connection to the same hub (which may be reassigned over time)
 
_**WiFi Direct is the highest performing mesh so far for high bandwidth applications**_


### Augmenting WiFi Aware and Direct

Since only Nearby Connections organically uses both WiFi and Bluetooth, a separate Bluetooth capability has been built out for WiFi Aware and WiFi Direct meshes. The intent of this Bluetooth connectivity is to provide another, sometimes redundant mesh to enhance overall up time and to help bridge information between meshes when the WiFi Hub/Spoke models have to (either due to proximity or size) form multiple Hub/Spoke clusters. Currently, as both WiFi Aware and WiFi Direct are in testing, the Bluetooth mesh normally assigned to support these two approaches has been disabled. **To enable Bluetooth and WiFi simultaneously** in the _ManetOps_ class constructor, find the lines that say _"//TODO uncomment this to enable bluetooth"_.


### Bluetooth

Bluetooth mesh is available as a separate, stand-alone options as well and SqAN will blend data across meshes based on connectivity. Of the currently available mesh approaches, the Bluetooth mesh is the most mature at this time. The Bluetooth mesh is a custom built modified flood-based system, but is not the BLE based "Mesh Profile Specification" or "Mesh Model Specification" as these two are: 1) not yet supported natively within Android and 2) not optimized for the type of traffic anticipated within a dismounted maneuver unit environment.

Performance:
 - After initial pairing process, Bluetooth based mesh appears to form relatively quickly, self heal consistently and maintain connectivity at distances in excess of 50m in open terrain
 - Some lower end and older devices occasionally need to be restarted after a period of use in order to access any bluetooth connection but this problem appears to extend beyond the Bluetooth mesh itself
 
_**Bluetooth mesh is the highest performing mesh so far for lower bandwidth, lower energy usage, multi-hop applications**_


### Software Defined Radio

_**[WARNING: prolonged use of some SDRs without adequate heat management may cause heat-related damage to the SDR]**_
SqANDR was a support library that has is absorbed into SqAN to extend SqAN across SDRs. Currently only PlutoSDRs or any *nix SDR with AD9361 agile transceiver family of devices supporting libiio. SqANDR is under active development with the latest on-SDR code provided in the "pluto" directory.

SDR performance relies heavily on a number of factors, most of which are adjustable from within the SerialConnection and AbstractDataConnection classes. Here are some key settings:
 - **Mega Samples per Second** (MiS/s), listed as SerialConnection.SAMPLE_RATE can be **between 0.6 and 6.0** inclusive. Any sampling rate below 3.2 MiS/s requires on Pluto filtering, which is automatically included when launching SqANDR. With the current scheme, Pluto's typically struggle to process in real time for any sampling rate higher than 2.1 MiS/s. Note: SDRs provided with dedicated power (rather than just the USB power from the attached Android) appear to have improved filter performance. Also of note, any sampling rate under 0.8 MiS/s is insufficiently fast to stream media
 - **SerialConnection.MESSAGE_REPEAT** defines how many times a given message will be sent during a single transmission window. Higher values equal greater chance the message will be properly received and is limited based on how many messages can fit within the _SerialConnection.TX_BUFFER_SIZE_. Any messages above the buffer size will be ignored.
 - **AbstractDataConnection.USE_GAP_STRATEGY** indicates that a custom form of forward error correction should be used. This increases the size of a single packet, but also substantially increases the fidelity of packets. This flag is recommended, especially wih Pluto SDRs as some devices experience a bit inversion that appears to happen every 400 to 600 bits and is believed to be related to clock differences. SqANDR as-is relies on packet headers but not a timing signal, this option also adds a timing signal.
 - **Segment.MAX_LENGTH_BEFORE_SEGMENTING** allows the Segmenting And Reassembly engine to decide where packets should be split to support transport across the system. This can be as low as 1 and as high as 216 but should be **at least above 49** if packets from the VPN are being sent across the network. Many smaller segments sent multiple times increase the chance that a complete sequence of all segments will be reconstructed into a packet, but adds the expense of more header data which effectively slows down the overall data throughput.

SDR performance requires tuning the the specific set-up in use. Three main indicators are available in Logcat when trying to tune to a specific hardware set-up. The first is an indicator warning about the cycle time. If the SDR is not able to cycle fast enough to process data in real time, a warning will appear with the lag between the required and actual time. When an SDR (like the generally low powered Pluto) is unable to cycle fast enough, the device in effect ends up listening for a fraction of the actual time leading to periodic gaps where traffic is dropped. Adjusting the sample rate is one of the most direct ways to effect cycle time (lower sample rate equals fewer samples to process in a given time but also lower data throughput). The second indicator is a warning from the SerialConnection whenever it is sending data faster than the SDR is able to process it. When this happens, the SDR will start to lag behind real time and often drop packets. Switching the SDR to BIN_IN mode provides the greatest speed available for the Pluto SDR. The third indicator is the signal to noise ratio (SNR). The SNR will be regularly updated in logcat (with a "signal" being any packet that is received successfully, even if it is a duplicate, and "noise" being any packet that is partially received but corrupt to the point of being unrecoverable. SqANDR includes both a forward error correction mechanism (ContinuityGapSAR) as well as a SAR mechanism (Segment) that both allow multiple incomplete copies of data to be fused into one valid packet. When the SNR is significantly off, the user will also receive a visible warning message about high levels of data corruption and noise.

SDR exists as a stand-alone mesh. However, SDR can be enabled with other meshes. **To enable SDR and other meshes simultaneously** in the _ManetOps_ class constructor, find the lines that say _"//TODO uncomment this to enable SDR"_. SDR will handle any traffic, to include VPN data, available to other SqAN mesh approaches and will route traffic between meshes based on connectivity.  


### WiFiManager

An approach using WiFi Manager was explored as well. WiFiManager could be used to create an access point but that required reflection to access private APIs starting at Android 7. This capability was discontinued entirely at Android 8, so this approach was dropped as long-term unstable.


### Logging

SqAN generates a moderately detailed log of connectivity and routing decisions in the device's Documents/SqAN folder. Portions of this log are viewable from within the About page of SqAN and the most verbose logging is available in logcat. For the purposes of load testing and routing debugging, the text log files in the SqAN folder should be sufficient for most cases.


### The Testing App

The SqAN Test app found in the Testing folder serves two purposes: 1) to provide a structured way to implement various load tests and 2) to provide an easy demo of SqAN actually doing something. The SqAN Test app contains a geographic based network map of how the devices are connecting as well as a rudimentary messaging capability to communicate by text messages between the apps. The SqAN Test app also serves as an example of how to use the SqAN channel IPC to provide app-to-app communication.

A second SqANDR Test app found in the Testing folder is intended primarily to aid in the development and changes to the embedded SqANDR code deployed by SqAN to an attached SDR.


## Sending data via IPC

Although SqAN is intended primarily to intercept and re-route TCP/IP traffic, SqAN can be used via Interprocess Communication (IPC) to send data from apps specifically for transmission via SqAN. To use IPC to send data over SqAN:
 - copy the _org.sofwerx.sqan.ipc.IpcBroadcastTransceiver_ into the app you want to communicate with SqAN (SqAN relies on Broadcasts to communicate with other apps).
 - inside your app, call _IpcBroadcastTransceiver.register()_ to listen for messages from SqAN. Your app's IpcBroadcastTransceiver.IpcBroadcastListener will be notified when data is received from SqAN.
 - when the app needs to broadcast something over SqAN, call _IpcBroadcastTransceiver.broadcast()_ and pass your data. SqAN passes byte arrays so you will need to marshal/unmarshal your data on the sending and receiving ends.
 - call _IpcBroadcastTransceiver.unregister()_ when your app no longer needs to receive messages from SqAN.

Only the _IpcBroadcastTransceiver_ is needed to communicate with SqAN, but it may also help to include the classes in the _org.sofwerx.sqan.manet.common.packet_ package. Classes in this package provide additional capabilities (like designating a channel for the communications) that may be helpful. However, outside apps are not allowed to send "admin" type packets (such as a ping or heartbeat) so broadcasts using these will be ignored by SqAN.
 
The SOFWERX Swe-Library project (which provides for the ability to send SOS-T compliant sensor messages over TCP/IP connections) also organically supports SqAN IPC connectivity. To use this library, visit (https://github.com/sofwerx/swe-android).
 
 
## Sending data via TCP/IP
 
SqAN includes a VPN capability. This capability has been marginally tested with both text and high bandwidth traffic (like video) and appears stable and performant. The VPN feature allows SqAN to route any TCP/IP traffic to another SqAN node as long as that traffic is within the forwarded IP block _169.x.x.x_ and also includes multicast capability for IP addresses in the _224.x.x.x_, _225.x.x.x_, _226.x.x.x_ and _239.x.x.x_ blocks. To use the VPN capabilty, ensure VPN is set to enabled in SqAN settings and look for the SqAN device's IPV4 hosting address that is displayed at the bottom of the About screen. SqAN device IPV4 addresses are also shared across SqAN, so the IPV4 of any connected SqAN device can be found remotely.

SqAN also provides a web server and hosts a page at the SqAN device's _169.254.x.x_ IP address for testing connections. If you application requires accessing web traffic, it is recommended that you disable the "Host a VPN page" setting.

SqAN addresses in _169.254.x.x_ are reserved for actual SqAN nodes, while devices in _169.x.x.x_ other than those reserved addresses are used for forwarding of devices connected to a SqAN node. SqAN contains a limited IP forwarding capability that is not currently in use (to enable it change the comments in isVpnForwardIps()). Within the SqAnVpnConnection class there is logic that adjusts IPV4 headers to forward traffic from other networks connected to the device. This capability does not, however, include altering UDP/TCP headers. Some effort was put towards taht direction as well as generating a port forwarding scheme, but was dropped as it was not needed for the specific requirements. See the note before the SqAnVpnConnection constructor for additional details.

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
 
At this point SqAN relies solely on Bluetooth Security Level 3 to handle all pairing and Encryption in the Bluetooth Security Manager. This is implemented on the Android side through BluetoothSockets being formed from _createRfcommSocketToServiceRecord_ calls rather than _createInsecureRfcommSocketToServiceRecord_ calls. SqAN may someday fall back to these insecure RFC calls but that is not currently planned with the given encryption scheme.
 
SqAN has a provisional stub for authentication and encryption handled and is based on an architecture that is fairly resilient to flooding so later security measures can be implemented without protocol changes. That being said, the current approach which does not rely on export restricted code within SqAN relies heavily on the user not taking the affirative action to pair with a device they do not trust.

### A note on Aware MANET security:

The current Aware MANET should be considered insecure. Although capabilities are stubbed-out to facilitate securing that MANET approach, they have not been implemented and the MANET should be considered to be unencrypted.
 
### *WiFi Aware™, WiFi Direct®, and Bluetooth® are trademarked, but their trade marking characters have been ommitted from the majority of this readme as both symbols render distractingly oversized in some viewers.
