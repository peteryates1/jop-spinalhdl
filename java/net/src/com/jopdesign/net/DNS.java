package com.jopdesign.net;

import com.jopdesign.sys.JVMHelp;

/**
 * DNS resolver (RFC 1035).
 *
 * Performs blocking A record lookups via UDP. Uses a 4-entry cache with
 * TTL-based expiry (capped at 5 minutes).
 *
 * Intercepts UDP responses via DNS.pendingPort in UDP.receive() — uses
 * zero UDP connection slots.
 */
public class DNS {

	/** DNS server port. */
	static final int DNS_PORT = 53;

	/** Query timeout in milliseconds. */
	static final int TIMEOUT_MS = 5000;

	/** Maximum retries per resolve. */
	static final int MAX_RETRIES = 3;

	/** Maximum TTL in cache (5 minutes, in milliseconds). */
	static final int MAX_CACHE_TTL_MS = 300000;

	/** Cache size. */
	static final int CACHE_SIZE = 4;

	// Cache arrays
	static int[] cacheHash = new int[CACHE_SIZE];
	static int[] cacheIp = new int[CACHE_SIZE];
	static int[] cacheExpiry = new int[CACHE_SIZE];  // NumFunctions.now() timestamp

	// Pending query state
	/** Non-zero when a DNS query is in flight. UDP.receive() checks this. */
	public static int pendingPort = 0;
	static int pendingId = 0;
	static int responseIp = 0;
	static boolean responseReady = false;

	/** Local port counter for DNS queries. */
	private static int localPort = 49200;

	/**
	 * Resolve a hostname to an IPv4 address. Blocking — polls NetLoop
	 * internally until response or timeout.
	 *
	 * @param hostname character array containing the hostname
	 * @param len number of characters in the hostname
	 * @return IPv4 address, or 0 on failure
	 */
	public static int resolve(char[] hostname, int len) {
		if (NetConfig.dnsServer == 0) {
			JVMHelp.wr("DNS: no server\n");
			return 0;
		}

		// Check cache first
		int hash = hashHostname(hostname, len);
		int now = NumFunctions.now();
		for (int i = 0; i < CACHE_SIZE; i++) {
			if (cacheHash[i] == hash && cacheIp[i] != 0) {
				if (now - cacheExpiry[i] < 0) {
					// Cache hit, not expired
					return cacheIp[i];
				}
			}
		}

		// Build and send query
		for (int retry = 0; retry < MAX_RETRIES; retry++) {
			Packet pkt = Packet.alloc();
			if (pkt == null) return 0;

			// Allocate a local port for this query
			localPort++;
			if (localPort > 65000) localPort = 49200;

			pendingId = (pendingId + 1) & 0xFFFF;
			if (pendingId == 0) pendingId = 1;
			responseReady = false;
			responseIp = 0;
			pendingPort = localPort;

			int payloadLen = buildQuery(pkt, hostname, len);
			if (payloadLen <= 0) {
				pkt.free();
				pendingPort = 0;
				return 0;
			}

			UDP.sendDirect(NetConfig.dnsServer, localPort, DNS_PORT, pkt, payloadLen);
			pkt.free();

			// Poll until response or timeout
			int startTime = NumFunctions.now();
			while (!responseReady) {
				NetLoop.poll();
				if (NumFunctions.now() - startTime > TIMEOUT_MS) {
					break;
				}
			}

			pendingPort = 0;

			if (responseReady && responseIp != 0) {
				// Cache the result
				addToCache(hash, responseIp, now);
				return responseIp;
			}
		}

		JVMHelp.wr("DNS: timeout\n");
		return 0;
	}

	/**
	 * Process a received DNS response (called from UDP.receive() intercept).
	 *
	 * @param pkt the received packet
	 * @param off offset of DNS payload (after UDP header)
	 * @param len length of DNS payload
	 */
	public static void receive(Packet pkt, int off, int len) {
		if (len < 12) return;  // DNS header is 12 bytes

		// Verify transaction ID
		int rxId = pkt.getShort(off);
		if (rxId != pendingId) return;

		// Check QR bit (must be response) and RCODE (must be 0 = no error)
		int flags = pkt.getShort(off + 2);
		if ((flags & 0x8000) == 0) return;  // Not a response
		int rcode = flags & 0x000F;
		if (rcode != 0) {
			responseReady = true;
			return;
		}

		int qdCount = pkt.getShort(off + 4);
		int anCount = pkt.getShort(off + 6);

		// Skip question section
		int pos = off + 12;
		for (int i = 0; i < qdCount; i++) {
			pos = skipName(pkt, pos, off, off + len);
			if (pos < 0) return;
			pos += 4;  // QTYPE + QCLASS
			if (pos > off + len) return;
		}

		// Parse answer section — find first A record
		for (int i = 0; i < anCount; i++) {
			pos = skipName(pkt, pos, off, off + len);
			if (pos < 0) return;
			if (pos + 10 > off + len) return;

			int aType = pkt.getShort(pos);
			int aClass = pkt.getShort(pos + 2);
			int ttl = pkt.getInt(pos + 4);
			int rdLength = pkt.getShort(pos + 8);
			pos += 10;

			if (pos + rdLength > off + len) return;

			if (aType == 1 && aClass == 1 && rdLength == 4) {
				// A record — IPv4 address
				responseIp = pkt.getInt(pos);
				responseReady = true;

				// Store TTL for caching (cap at MAX_CACHE_TTL_MS)
				if (ttl < 0) ttl = 0;
				int ttlMs = ttl * 1000;
				if (ttlMs > MAX_CACHE_TTL_MS || ttlMs < 0) ttlMs = MAX_CACHE_TTL_MS;
				int now = NumFunctions.now();
				addToCache(0, responseIp, now);  // Hash set by caller
				// Update expiry with actual TTL
				for (int j = 0; j < CACHE_SIZE; j++) {
					if (cacheIp[j] == responseIp) {
						cacheExpiry[j] = now + ttlMs;
						break;
					}
				}
				return;
			}

			pos += rdLength;
		}

		// No A record found
		responseReady = true;
	}

	/**
	 * Build a DNS query in the packet buffer.
	 * Returns the payload length (DNS message size), or -1 on error.
	 */
	private static int buildQuery(Packet pkt, char[] hostname, int len) {
		int off = IP.IP_OFF + IP.IP_HEADER_LEN + UDP.UDP_HEADER;

		// DNS header (12 bytes)
		pkt.setShort(off, pendingId);       // Transaction ID
		pkt.setShort(off + 2, 0x0100);      // Flags: standard query, recursion desired
		pkt.setShort(off + 4, 1);            // QDCOUNT = 1
		pkt.setShort(off + 6, 0);            // ANCOUNT
		pkt.setShort(off + 8, 0);            // NSCOUNT
		pkt.setShort(off + 10, 0);           // ARCOUNT

		// QNAME: encode hostname as length-prefixed labels
		int pos = off + 12;
		int labelStart = pos;
		pos++;  // Reserve space for first label length

		for (int i = 0; i <= len; i++) {
			char c = (i < len) ? hostname[i] : '.';
			if (c == '.' || i == len) {
				int labelLen = pos - labelStart - 1;
				if (labelLen <= 0 && i < len) return -1;  // Empty label
				if (labelLen > 63) return -1;  // Label too long
				if (labelLen > 0) {
					pkt.setByte(labelStart, labelLen);
				}
				if (i < len) {
					labelStart = pos;
					pos++;  // Reserve for next label length
				}
			} else {
				pkt.setByte(pos++, c & 0xFF);
			}
		}
		pkt.setByte(pos++, 0);  // Root label (zero length)

		// QTYPE = A (1), QCLASS = IN (1)
		pkt.setShort(pos, 1);
		pos += 2;
		pkt.setShort(pos, 1);
		pos += 2;

		return pos - off;
	}

	/**
	 * Skip a DNS name (handles compression pointers).
	 * Returns offset after the name, or -1 on error.
	 */
	private static int skipName(Packet pkt, int pos, int baseOff, int maxOff) {
		int jumped = 0;
		int returnPos = -1;
		int safetyCount = 0;

		while (pos < maxOff && safetyCount < 128) {
			safetyCount++;
			int b = pkt.getByte(pos);
			if (b == 0) {
				// End of name
				if (returnPos >= 0) return returnPos;
				return pos + 1;
			}
			if ((b & 0xC0) == 0xC0) {
				// Compression pointer
				if (pos + 1 >= maxOff) return -1;
				int ptr = ((b & 0x3F) << 8) | pkt.getByte(pos + 1);
				if (returnPos < 0) returnPos = pos + 2;
				pos = baseOff + ptr;
				jumped++;
				if (jumped > 10) return -1;  // Prevent infinite loops
			} else {
				// Label
				pos += 1 + b;
			}
		}
		return -1;  // Malformed name
	}

	/**
	 * Compute a simple hash of a hostname for cache lookup.
	 */
	private static int hashHostname(char[] hostname, int len) {
		int h = 0;
		for (int i = 0; i < len; i++) {
			h = h * 31 + (hostname[i] & 0xFF);
		}
		// Ensure non-zero (0 means empty cache entry)
		if (h == 0) h = 1;
		return h;
	}

	/**
	 * Add an entry to the DNS cache.
	 */
	private static void addToCache(int hash, int ip, int now) {
		// Check if already present
		for (int i = 0; i < CACHE_SIZE; i++) {
			if (cacheIp[i] == ip || cacheHash[i] == hash) {
				cacheHash[i] = hash;
				cacheIp[i] = ip;
				cacheExpiry[i] = now + MAX_CACHE_TTL_MS;
				return;
			}
		}
		// Find empty or oldest entry
		int oldest = 0;
		int oldestExpiry = cacheExpiry[0];
		for (int i = 0; i < CACHE_SIZE; i++) {
			if (cacheIp[i] == 0) {
				oldest = i;
				break;
			}
			if (cacheExpiry[i] - oldestExpiry < 0) {
				oldestExpiry = cacheExpiry[i];
				oldest = i;
			}
		}
		cacheHash[oldest] = hash;
		cacheIp[oldest] = ip;
		cacheExpiry[oldest] = now + MAX_CACHE_TTL_MS;
	}
}
