/*
 * udp_echo_flood -- an Ethernet link at line rate, through the MAC's hardware
 * UDP echo (jop.io.UdpEcho; NetTest turns it on for port 7007 where
 * Const.ETH_UDP_ECHO says the MAC has one).
 *
 * Sends COUNT datagrams of SIZE payload bytes to HOST:PORT -- as fast as this
 * host can, or paced to RATE Mb/s on the wire -- and takes the echoes on a
 * second thread. Each datagram holds its sequence number and a pattern made
 * from it, so every echo is checked byte for byte and a duplicate is seen.
 * The host's own UDP drop counters (/proc/net/snmp) are read before and after:
 * a loss there is this machine's, not the link's.
 *
 * On the wire a datagram is SIZE + 66 bytes: UDP 8, IP 20, Ethernet 14, FCS 4,
 * preamble 8, gap 12. At SIZE 1472 that is 1538, and 1 Gb/s carries 81,274 of
 * them a second: 957 Mb/s of payload.
 *
 * Build: cc -O2 -pthread -o udp_echo_flood fpga/scripts/udp_echo_flood.c
 * Usage: udp_echo_flood HOST [PORT [SIZE [COUNT [RATE_MBPS]]]]
 *        (defaults 7007 1472 200000, unpaced)
 */
#include <arpa/inet.h>
#include <errno.h>
#include <netdb.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

static int sock;
static long size, count;
static volatile int sending = 1;
static long received, corrupt, duplicate, reordered;
static uint8_t *seen;
static double firstRx, lastRx;

static double now(void)
{
	struct timespec t;
	clock_gettime(CLOCK_MONOTONIC, &t);
	return t.tv_sec + t.tv_nsec * 1e-9;
}

static uint8_t pattern(uint64_t seq, long j)
{
	return (uint8_t)(seq * 131 + j * 7 + (seq >> 8));
}

static void fill(uint8_t *b, uint64_t seq)
{
	memcpy(b, &seq, 8);
	for (long j = 8; j < size; j++)
		b[j] = pattern(seq, j);
}

static void *receiver(void *arg)
{
	(void)arg;
	uint8_t *b = malloc(65536);
	uint64_t expect = 0;
	double quiet = 0;
	for (;;) {
		ssize_t n = recv(sock, b, 65536, 0);
		if (n < 0) {
			if (errno == EAGAIN || errno == EWOULDBLOCK) {
				// Stop once the sender is done and nothing came for a second
				if (!sending && (quiet == 0 ? (quiet = now(), 0) : now() - quiet > 1.0))
					break;
				continue;
			}
			perror("recv");
			break;
		}
		quiet = 0;
		double t = now();
		if (received == 0)
			firstRx = t;
		lastRx = t;
		uint64_t seq;
		int bad = n != size || n < 8;
		if (!bad) {
			memcpy(&seq, b, 8);
			bad = seq >= (uint64_t)count;
			for (long j = 8; !bad && j < size; j++)
				bad = b[j] != pattern(seq, j);
		}
		if (bad) {
			corrupt++;
			continue;
		}
		if (seen[seq]) {
			duplicate++;
			continue;
		}
		seen[seq] = 1;
		received++;
		if (seq < expect)
			reordered++;
		else
			expect = seq + 1;
	}
	free(b);
	return NULL;
}

/* Udp: InErrors and RcvbufErrors from /proc/net/snmp. */
static void udpErrors(long *inErrors, long *rcvbuf)
{
	FILE *f = fopen("/proc/net/snmp", "r");
	char head[1024], vals[1024];
	*inErrors = *rcvbuf = -1;
	if (!f)
		return;
	while (fgets(head, sizeof head, f) && fgets(vals, sizeof vals, f)) {
		if (strncmp(head, "Udp:", 4) != 0)
			continue;
		char *hs, *vs, *h = strtok_r(head + 4, " \n", &hs), *v = strtok_r(vals + 4, " \n", &vs);
		for (; h && v; h = strtok_r(NULL, " \n", &hs), v = strtok_r(NULL, " \n", &vs)) {
			if (!strcmp(h, "InErrors"))
				*inErrors = atol(v);
			if (!strcmp(h, "RcvbufErrors"))
				*rcvbuf = atol(v);
		}
		break;
	}
	fclose(f);
}

int main(int argc, char **argv)
{
	if (argc < 2) {
		fprintf(stderr, "usage: %s HOST [PORT [SIZE [COUNT [RATE_MBPS]]]]\n", argv[0]);
		return 2;
	}
	const char *host = argv[1];
	const char *port = argc > 2 ? argv[2] : "7007";
	size = argc > 3 ? atol(argv[3]) : 1472;
	count = argc > 4 ? atol(argv[4]) : 200000;
	double rate = argc > 5 ? atof(argv[5]) : 0;
	if (size < 8 || size > 1472) {
		fprintf(stderr, "SIZE must be 8..1472\n");
		return 2;
	}

	struct addrinfo hints = { .ai_family = AF_INET, .ai_socktype = SOCK_DGRAM }, *ai;
	if (getaddrinfo(host, port, &hints, &ai)) {
		fprintf(stderr, "cannot resolve %s\n", host);
		return 2;
	}
	sock = socket(AF_INET, SOCK_DGRAM, 0);
	int big = 32 << 20;
	setsockopt(sock, SOL_SOCKET, SO_RCVBUF, &big, sizeof big);
	setsockopt(sock, SOL_SOCKET, SO_SNDBUF, &big, sizeof big);
	struct timeval tv = { 0, 100000 };
	setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
	if (connect(sock, ai->ai_addr, ai->ai_addrlen)) {
		perror("connect");
		return 2;
	}
	int rcvbuf;
	socklen_t len = sizeof rcvbuf;
	getsockopt(sock, SOL_SOCKET, SO_RCVBUF, &rcvbuf, &len);

	seen = calloc(count, 1);
	long inErr0, rcvbuf0, inErr1, rcvbuf1;
	udpErrors(&inErr0, &rcvbuf0);

	pthread_t rx;
	pthread_create(&rx, NULL, receiver, NULL);

	uint8_t *b = malloc(size);
	double wire = (size + 66) * 8.0; /* bits a datagram takes on the wire */
	double gap = rate > 0 ? wire / (rate * 1e6) : 0;
	long sendErrors = 0;
	double t0 = now(), next = t0;
	for (long seq = 0; seq < count; seq++) {
		fill(b, seq);
		if (gap > 0) {
			while (now() < next)
				;
			next += gap;
		}
		while (send(sock, b, size, 0) < 0) {
			if (errno != ENOBUFS && errno != EAGAIN) {
				sendErrors++;
				break;
			}
		}
	}
	double t1 = now();
	sending = 0;
	pthread_join(rx, NULL);
	udpErrors(&inErr1, &rcvbuf1);

	double ts = t1 - t0, tr = lastRx - firstRx;
	printf("%s:%s  %ld datagrams of %ld bytes (%ld on the wire), host receive buffer %d bytes\n",
	       host, port, count, size, size + 66, rcvbuf);
	printf("sent      %ld in %.3f s: %.0f/s, %.1f Mb/s on the wire, %.1f Mb/s payload%s\n",
	       count, ts, count / ts, count * wire / ts / 1e6, count * size * 8.0 / ts / 1e6,
	       sendErrors ? " (send errors)" : "");
	printf("echoed    %ld in %.3f s: %.0f/s, %.1f Mb/s on the wire, %.1f Mb/s payload\n",
	       received, tr, received / tr, received * wire / tr / 1e6, received * size * 8.0 / tr / 1e6);
	printf("lost      %ld (%.4f%%), corrupt %ld, duplicate %ld, out of order %ld\n",
	       count - received, 100.0 * (count - received) / count, corrupt, duplicate, reordered);
	printf("this host dropped UDP: InErrors +%ld, RcvbufErrors +%ld\n",
	       inErr1 - inErr0, rcvbuf1 - rcvbuf0);
	return received == count && !corrupt ? 0 : 1;
}
