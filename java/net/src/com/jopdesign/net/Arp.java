package com.jopdesign.net;

/**
 * ARP cache and request/reply handling.
 *
 * Maintains a table mapping IP addresses to MAC addresses.
 * Handles ARP requests (replies when target is us) and ARP replies
 * (updates cache).
 *
 * ARP packet layout (after 14-byte Ethernet header):
 *   +0  HTYPE    (2 bytes) = 0x0001 (Ethernet)
 *   +2  PTYPE    (2 bytes) = 0x0800 (IPv4)
 *   +4  HLEN     (1 byte)  = 6
 *   +5  PLEN     (1 byte)  = 4
 *   +6  OPER     (2 bytes) = 1 (request) or 2 (reply)
 *   +8  SHA      (6 bytes) sender MAC
 *   +14 SPA      (4 bytes) sender IP
 *   +18 THA      (6 bytes) target MAC
 *   +24 TPA      (4 bytes) target IP
 */
public class Arp {

	/** ARP packet offset within Ethernet frame. */
	private static final int ARP = EthDriver.ETH_HEADER;

	/** ARP operation: request */
	private static final int OP_REQUEST = 1;
	/** ARP operation: reply */
	private static final int OP_REPLY = 2;

	/** Total ARP frame length (Ethernet header + 28 bytes ARP). */
	private static final int ARP_FRAME_LEN = EthDriver.ETH_HEADER + 28;

	// ARP cache
	private static final int CACHE_SIZE = NetConfig.ARP_CACHE_SIZE;
	private static int[] cacheIp = new int[CACHE_SIZE];
	private static int[] cacheMacHi = new int[CACHE_SIZE];
	private static int[] cacheMacLo = new int[CACHE_SIZE];
	private static int[] cacheAge = new int[CACHE_SIZE];
	private static int ageClock = 0;

	// Pending ARP request
	private static int pendingIp = 0;
	private static int pendingTimeout = 0;

	/** Initialize ARP cache. */
	public static void init() {
		for (int i = 0; i < CACHE_SIZE; i++) {
			cacheIp[i] = 0;
			cacheMacHi[i] = 0;
			cacheMacLo[i] = 0;
			cacheAge[i] = 0;
		}
		ageClock = 0;
		pendingIp = 0;
		pendingTimeout = 0;
	}

	/**
	 * Look up a MAC address for an IP. If found, sets the destination MAC
	 * in the packet and returns true. If not found, sends an ARP request
	 * and returns false.
	 *
	 * @param pkt packet whose dst MAC to set (if found)
	 * @param ip the IP to resolve (should be next-hop, not final dest)
	 * @return true if resolved, false if ARP request was sent
	 */
	public static boolean resolve(Packet pkt, int ip) {
		// Search cache
		for (int i = 0; i < CACHE_SIZE; i++) {
			if (cacheIp[i] == ip && ip != 0) {
				EthDriver.setDstMac(pkt, cacheMacHi[i], cacheMacLo[i]);
				cacheAge[i] = ++ageClock;
				return true;
			}
		}
		// Not found — send ARP request
		sendRequest(ip);
		return false;
	}

	/**
	 * Process a received ARP packet.
	 */
	public static void process(Packet pkt) {
		int oper = pkt.getShort(ARP + 6);
		int senderIp = pkt.getInt(ARP + 14);
		int senderMacHi = (pkt.getByte(ARP + 8) << 8) | pkt.getByte(ARP + 9);
		int senderMacLo = (pkt.getByte(ARP + 10) << 24) | (pkt.getByte(ARP + 11) << 16)
			| (pkt.getByte(ARP + 12) << 8) | pkt.getByte(ARP + 13);

		// Always update cache from sender info (ARP snooping)
		updateCache(senderIp, senderMacHi, senderMacLo);

		if (oper == OP_REQUEST) {
			int targetIp = pkt.getInt(ARP + 24);
			if (targetIp == NetConfig.ip) {
				sendReply(pkt, senderIp, senderMacHi, senderMacLo);
			}
		}
	}

	/** Send an ARP request for the given IP. */
	private static void sendRequest(int ip) {
		Packet pkt = Packet.alloc();
		if (pkt == null) return;

		// Ethernet header: broadcast destination
		EthDriver.setDstMac(pkt, EthDriver.BCAST_MAC_HI, EthDriver.BCAST_MAC_LO);
		EthDriver.setSrcMac(pkt);
		EthDriver.setEtherType(pkt, EthDriver.ETHERTYPE_ARP);

		// ARP header
		pkt.setShort(ARP + 0, 0x0001);  // HTYPE = Ethernet
		pkt.setShort(ARP + 2, 0x0800);  // PTYPE = IPv4
		pkt.setByte(ARP + 4, 6);        // HLEN
		pkt.setByte(ARP + 5, 4);        // PLEN
		pkt.setShort(ARP + 6, OP_REQUEST);

		// Sender hardware address (our MAC)
		for (int i = 0; i < 6; i++) {
			pkt.setByte(ARP + 8 + i, NetConfig.getMacByte(i));
		}
		// Sender protocol address (our IP)
		pkt.setInt(ARP + 14, NetConfig.ip);

		// Target hardware address (zeros for request)
		for (int i = 0; i < 6; i++) {
			pkt.setByte(ARP + 18 + i, 0);
		}
		// Target protocol address
		pkt.setInt(ARP + 24, ip);

		pkt.len = ARP_FRAME_LEN;
		EthDriver.send(pkt);
		pkt.free();

		pendingIp = ip;
		pendingTimeout = NetConfig.ARP_TIMEOUT;
	}

	/** Send an ARP reply to a requester. */
	private static void sendReply(Packet rxPkt, int targetIp, int targetMacHi, int targetMacLo) {
		Packet pkt = Packet.alloc();
		if (pkt == null) return;

		// Ethernet header
		EthDriver.setDstMac(pkt, targetMacHi, targetMacLo);
		EthDriver.setSrcMac(pkt);
		EthDriver.setEtherType(pkt, EthDriver.ETHERTYPE_ARP);

		// ARP header
		pkt.setShort(ARP + 0, 0x0001);
		pkt.setShort(ARP + 2, 0x0800);
		pkt.setByte(ARP + 4, 6);
		pkt.setByte(ARP + 5, 4);
		pkt.setShort(ARP + 6, OP_REPLY);

		// Sender = us
		for (int i = 0; i < 6; i++) {
			pkt.setByte(ARP + 8 + i, NetConfig.getMacByte(i));
		}
		pkt.setInt(ARP + 14, NetConfig.ip);

		// Target = requester
		pkt.setByte(ARP + 18, (targetMacHi >>> 8) & 0xFF);
		pkt.setByte(ARP + 19, targetMacHi & 0xFF);
		pkt.setByte(ARP + 20, (targetMacLo >>> 24) & 0xFF);
		pkt.setByte(ARP + 21, (targetMacLo >>> 16) & 0xFF);
		pkt.setByte(ARP + 22, (targetMacLo >>> 8) & 0xFF);
		pkt.setByte(ARP + 23, targetMacLo & 0xFF);
		pkt.setInt(ARP + 24, targetIp);

		pkt.len = ARP_FRAME_LEN;
		EthDriver.send(pkt);
		pkt.free();
	}

	/** Update ARP cache with a new entry. Replaces oldest entry if full. */
	private static void updateCache(int ip, int macHi, int macLo) {
		if (ip == 0) return;

		// Check if already present
		for (int i = 0; i < CACHE_SIZE; i++) {
			if (cacheIp[i] == ip) {
				cacheMacHi[i] = macHi;
				cacheMacLo[i] = macLo;
				cacheAge[i] = ++ageClock;
				return;
			}
		}

		// Find empty or oldest slot
		int oldest = 0;
		int oldestAge = cacheAge[0];
		for (int i = 0; i < CACHE_SIZE; i++) {
			if (cacheIp[i] == 0) {
				oldest = i;
				break;
			}
			if (cacheAge[i] < oldestAge) {
				oldestAge = cacheAge[i];
				oldest = i;
			}
		}

		cacheIp[oldest] = ip;
		cacheMacHi[oldest] = macHi;
		cacheMacLo[oldest] = macLo;
		cacheAge[oldest] = ++ageClock;
	}

	/**
	 * Learn a MAC/IP mapping from a received IP frame's Ethernet header.
	 * Called by IP.receive() to ensure we can reply to the sender.
	 */
	public static void learnFrom(int ip, int macHi, int macLo) {
		updateCache(ip, macHi, macLo);
	}

	/**
	 * Send a gratuitous ARP broadcast to announce our IP/MAC to the LAN.
	 * This is a broadcast ARP reply with sender = us and target = us.
	 */
	public static void sendGratuitous() {
		Packet pkt = Packet.alloc();
		if (pkt == null) return;

		// Ethernet header: broadcast destination
		EthDriver.setDstMac(pkt, EthDriver.BCAST_MAC_HI, EthDriver.BCAST_MAC_LO);
		EthDriver.setSrcMac(pkt);
		EthDriver.setEtherType(pkt, EthDriver.ETHERTYPE_ARP);

		// ARP header
		pkt.setShort(ARP + 0, 0x0001);  // HTYPE = Ethernet
		pkt.setShort(ARP + 2, 0x0800);  // PTYPE = IPv4
		pkt.setByte(ARP + 4, 6);        // HLEN
		pkt.setByte(ARP + 5, 4);        // PLEN
		pkt.setShort(ARP + 6, OP_REPLY); // Gratuitous = reply

		// Sender = us
		for (int i = 0; i < 6; i++) {
			pkt.setByte(ARP + 8 + i, NetConfig.getMacByte(i));
		}
		pkt.setInt(ARP + 14, NetConfig.ip);

		// Target = broadcast MAC, our IP
		for (int i = 0; i < 6; i++) {
			pkt.setByte(ARP + 18 + i, 0xFF);
		}
		pkt.setInt(ARP + 24, NetConfig.ip);

		pkt.len = ARP_FRAME_LEN;
		EthDriver.send(pkt);
		pkt.free();
	}

	/** Decrement pending ARP timeout. Called from NetLoop. */
	public static void tick() {
		if (pendingTimeout > 0) {
			pendingTimeout--;
		}
	}
}
