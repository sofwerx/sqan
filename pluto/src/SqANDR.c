/*
 * libiio - AD9361 IIO streaming example
 *
 * Copyright (C) 2014 IABG mbH
 * Author: Michael Feilen <feilen_at_iabg.de>
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 **/

#include <stdbool.h>
#include <stdint.h>
#include <string.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <math.h>
#include <time.h>

#ifdef __APPLE__
#include <iio/iio.h>
#else
#include <iio.h>
#endif

/* helper macros */
#define MHZ(x) ((long long)(x*1000000.0 + .5))
#define GHZ(x) ((long long)(x*1000000000.0 + .5))

#define ASSERT(expr) { \
	if (!(expr)) { \
		(void) fprintf(stderr, "assertion failed (%s:%d)\n", __FILE__, __LINE__); \
		(void) abort(); \
	} \
}

/* RX is input, TX is output */
enum iodev { RX, TX };

/* common RX and TX streaming params */
struct stream_cfg {
	long long bw_hz; // Analog banwidth in Hz
	long long fs_hz; // Baseband sample rate in Hz
	long long lo_hz; // Local oscillator frequency in Hz
	const char* rfport; // Port name
};

/* static scratch mem for strings */
static char tmpstr[64];

/* IIO structs required for streaming */
static struct iio_context *ctx   = NULL;
static struct iio_channel *rx0_i = NULL;
static struct iio_channel *rx0_q = NULL;
static struct iio_channel *tx0_i = NULL;
static struct iio_channel *tx0_q = NULL;
static struct iio_buffer  *rxbuf = NULL;
static struct iio_buffer  *txbuf = NULL;

static bool stop;

/* cleanup and exit */
static void shutdown()
{
	printf("* Destroying buffers\n");
	if (rxbuf) { iio_buffer_destroy(rxbuf); }
	if (txbuf) { iio_buffer_destroy(txbuf); }

	printf("* Disabling streaming channels\n");
	if (rx0_i) { iio_channel_disable(rx0_i); }
	if (rx0_q) { iio_channel_disable(rx0_q); }
	if (tx0_i) { iio_channel_disable(tx0_i); }
	if (tx0_q) { iio_channel_disable(tx0_q); }

	printf("* Destroying context\n");
	if (ctx) { iio_context_destroy(ctx); }
	exit(0);
}

static void handle_sig(int sig)
{
	printf("Waiting for process to finish...\n");
	stop = true;
}

/* check return value of attr_write function */
static void errchk(int v, const char* what) {
	 if (v < 0) { fprintf(stderr, "Error %d writing to channel \"%s\"\nvalue may not be supported.\n", v, what); shutdown(); }
}

/* write attribute: long long int */
static void wr_ch_lli(struct iio_channel *chn, const char* what, long long val)
{
	errchk(iio_channel_attr_write_longlong(chn, what, val), what);
}

/* write attribute: string */
static void wr_ch_str(struct iio_channel *chn, const char* what, const char* str)
{
	errchk(iio_channel_attr_write(chn, what, str), what);
}

/* helper function generating channel names */
static char* get_ch_name(const char* type, int id)
{
	snprintf(tmpstr, sizeof(tmpstr), "%s%d", type, id);
	return tmpstr;
}

/* returns ad9361 phy device */
static struct iio_device* get_ad9361_phy(struct iio_context *ctx)
{
	struct iio_device *dev =  iio_context_find_device(ctx, "ad9361-phy");
	ASSERT(dev && "No ad9361-phy found");
	return dev;
}

/* finds AD9361 streaming IIO devices */
static bool get_ad9361_stream_dev(struct iio_context *ctx, enum iodev d, struct iio_device **dev)
{
	switch (d) {
	case TX: *dev = iio_context_find_device(ctx, "cf-ad9361-dds-core-lpc"); return *dev != NULL;
	case RX: *dev = iio_context_find_device(ctx, "cf-ad9361-lpc");  return *dev != NULL;
	default: ASSERT(0); return false;
	}
}

/* finds AD9361 streaming IIO channels */
static bool get_ad9361_stream_ch(struct iio_context *ctx, enum iodev d, struct iio_device *dev, int chid, struct iio_channel **chn)
{
	*chn = iio_device_find_channel(dev, get_ch_name("voltage", chid), d == TX);
	if (!*chn)
		*chn = iio_device_find_channel(dev, get_ch_name("altvoltage", chid), d == TX);
	return *chn != NULL;
}

/* finds AD9361 phy IIO configuration channel with id chid */
static bool get_phy_chan(struct iio_context *ctx, enum iodev d, int chid, struct iio_channel **chn)
{
	switch (d) {
	case RX: *chn = iio_device_find_channel(get_ad9361_phy(ctx), get_ch_name("voltage", chid), false); return *chn != NULL;
	case TX: *chn = iio_device_find_channel(get_ad9361_phy(ctx), get_ch_name("voltage", chid), true);  return *chn != NULL;
	default: ASSERT(0); return false;
	}
}

/* finds AD9361 local oscillator IIO configuration channels */
static bool get_lo_chan(struct iio_context *ctx, enum iodev d, struct iio_channel **chn)
{
	switch (d) {
	 // LO chan is always output, i.e. true
	case RX: *chn = iio_device_find_channel(get_ad9361_phy(ctx), get_ch_name("altvoltage", 0), true); return *chn != NULL;
	case TX: *chn = iio_device_find_channel(get_ad9361_phy(ctx), get_ch_name("altvoltage", 1), true); return *chn != NULL;
	default: ASSERT(0); return false;
	}
}

/* applies streaming configuration through IIO */
bool cfg_ad9361_streaming_ch(struct iio_context *ctx, struct stream_cfg *cfg, enum iodev type, int chid)
{
	struct iio_channel *chn = NULL;

	// Configure phy and lo channels
	printf("* Acquiring AD9361 phy channel %d\n", chid);
	if (!get_phy_chan(ctx, type, chid, &chn)) {	return false; }
	wr_ch_str(chn, "rf_port_select",     cfg->rfport);
	wr_ch_lli(chn, "rf_bandwidth",       cfg->bw_hz);
	wr_ch_lli(chn, "sampling_frequency", cfg->fs_hz);

	// Configure LO channel
	printf("* Acquiring AD9361 %s lo channel\n", type == TX ? "TX" : "RX");
	if (!get_lo_chan(ctx, type, &chn)) { return false; }
	wr_ch_lli(chn, "frequency", cfg->lo_hz);
	return true;
}

/* simple configuration and streaming */
int main (int argc, char **argv)
{

	// Streaming devices
	struct iio_device *tx;
	struct iio_device *rx;

	// RX and TX sample counters
	size_t nrx = 0;
	size_t ntx = 0;

	// Stream configurations
	struct stream_cfg rxcfg;
	struct stream_cfg txcfg;

	// Listen to ctrl+c and ASSERT
	signal(SIGINT, handle_sig);
 
	// RX stream config
	rxcfg.bw_hz = MHZ(2);   // 2 MHz rf bandwidth
	rxcfg.fs_hz = MHZ(2.5);   // 2.5 MS/s rx sample rate
	rxcfg.lo_hz = GHZ(2.5); // 2.5 GHz rf frequency
	rxcfg.rfport = "A_BALANCED"; // port A (select for rf freq.)

	// TX stream config
	txcfg.bw_hz = MHZ(1.5); // 1.5 MHz rf bandwidth
	txcfg.fs_hz = MHZ(2.5);   // 2.5 MS/s tx sample rate
	txcfg.lo_hz = GHZ(2.5); // 2.5 GHz rf frequency
	txcfg.rfport = "A"; // port A (select for rf freq.)

	printf("* Acquiring IIO context\n");
	ASSERT((ctx = iio_create_default_context()) && "No context");
	ASSERT(iio_context_get_devices_count(ctx) > 0 && "No devices");

	printf("* Acquiring AD9361 streaming devices\n");
	ASSERT(get_ad9361_stream_dev(ctx, TX, &tx) && "No tx dev found");
	ASSERT(get_ad9361_stream_dev(ctx, RX, &rx) && "No rx dev found");

	printf("* Configuring AD9361 for streaming\n");
	ASSERT(cfg_ad9361_streaming_ch(ctx, &rxcfg, RX, 0) && "RX port 0 not found");
	ASSERT(cfg_ad9361_streaming_ch(ctx, &txcfg, TX, 0) && "TX port 0 not found");

	printf("* Initializing AD9361 IIO streaming channels\n");
	ASSERT(get_ad9361_stream_ch(ctx, RX, rx, 0, &rx0_i) && "RX chan i not found");
	ASSERT(get_ad9361_stream_ch(ctx, RX, rx, 1, &rx0_q) && "RX chan q not found");
	ASSERT(get_ad9361_stream_ch(ctx, TX, tx, 0, &tx0_i) && "TX chan i not found");
	ASSERT(get_ad9361_stream_ch(ctx, TX, tx, 1, &tx0_q) && "TX chan q not found");

	printf("* Enabling IIO streaming channels\n");
	iio_channel_enable(rx0_i);
	iio_channel_enable(rx0_q);
	iio_channel_enable(tx0_i);
	iio_channel_enable(tx0_q);

	printf("* Creating non-cyclic IIO buffers with 1 MiS\n");
	rxbuf = iio_device_create_buffer(rx, 17*500, false);
	if (!rxbuf) {
		perror("Could not create RX buffer");
		shutdown();
	}
	txbuf = iio_device_create_buffer(tx, 17*250, false);
	if (!txbuf) {
		perror("Could not create TX buffer");
		shutdown();
	}
 
	int n = 0;
	int h;
	int flag = 0;
	int a = 0;
	int bitout[8][500];
	int head[1024*1025];
	int bitflag = 0;
	int half = 0;
	int whole = 0;
	int cnt = 0;
	int cl = 0;
	int m = 0;
	int t = 0;
	int startrx = 0;
	int k = 0;
	int bit = 0;
	int hval;
	char hchar;
	printf("* Starting IO streaming (press CTRL+C to cancel)\n");
	while (!stop) {	
	k = 0;
		while (k < 5) {
		ssize_t nbytes_rx, nbytes_tx;
		char *p_dat, *p_end;
		ptrdiff_t p_inc;
		//printf("here2");
		// Schedule TX buffer
		nbytes_tx = iio_buffer_push(txbuf);
		if (nbytes_tx < 0) { printf("Error pushing buf %d\n", (int) nbytes_tx); shutdown(); }
		//printf("here3");
		// Refill RX buffer
		nbytes_rx = iio_buffer_refill(rxbuf);
		if (nbytes_rx < 0) { printf("Error refilling buf %d\n",(int) nbytes_rx); shutdown(); }
		//printf("here4");
		// WRITE: Get pointers to TX buf and write IQ to TX buf port 0
		
		cl = 0;
		// READ: Get pointers to RX buf and read IQ from RX buf port 0
		p_inc = iio_buffer_step(rxbuf);
		p_end = iio_buffer_end(rxbuf);
		for (p_dat = (char *)iio_buffer_first(rxbuf, rx0_i); p_dat < p_end; p_dat += p_inc) {
			// Example: swap I and Q
			const int16_t i = ((int16_t*)p_dat)[0]; // Real (I)
			const int16_t q = ((int16_t*)p_dat)[1]; // Imag (Q)
			a = (i << 16) | (q & 0xFFFF);
			head[n] = a;
			if (startrx == 1) {
			//printf("\tI: %d, Q: %d, Comb: %d, Half:%d, Line: %d, count: %d\n", i, q, a, half, cl, cnt);
			}
			if (n>8) {
				if ((head[n-9] > 0 && head[n-8] > 0 && head[n-7] < 0 && head[n-6] > 0 && head[n-5] < 0 && head[n-4] > 0 && head[n-3] < 0 && head[n-2] > 0 && head[n-1] > 0) || bitflag == 1) { 
						if (a < -10000) {
							bitout[whole][cl] = 0;
						}
						else if (a > 10000) {
							bitout[whole][cl] = 1;
						}
						whole = whole + 1;
					half = half + 1;
					bitflag = 1;
					if (half == 8) {
						bitflag = 0;
						//printf("\t%d%d%d%d%d%d%d%d\n",bitout[0][cl], bitout[1][cl], bitout[2][cl], bitout[3][cl], bitout[4][cl], bitout[5][cl], bitout[6][cl], bitout[7][cl]);
						whole = 0;
						half = 0;
						cl = cl + 1;
						bit = 1;
					}
				}
				else if ((head[n-9] < 0 && head[n-8] < 0 && head[n-7] > 0 && head[n-6] < 0 && head[n-5] > 0 && head[n-4] < 0 && head[n-3] > 0 && head[n-2] < 0 && head[n-1] < 0) || bitflag == 2) {

						if (a < -10000) {
							bitout[whole][cl] = 1;
						}
						else if (a > 10000) {
							bitout[whole][cl] = 0;
						}
						whole = whole + 1;
					half = half + 1;
					bitflag = 2;
					if (half == 8) {
						bitflag = 0;
						//printf("\t%d%d%d%d%d%d%d%d\n",bitout[0][cl], bitout[1][cl], bitout[2][cl], bitout[3][cl], bitout[4][cl], bitout[5][cl], bitout[6][cl], bitout[7][cl]);
						whole = 0;
						half = 0;
						cl = cl + 1;
						bit = 1;
					}
				}
				if (bitout[0][cl-1] == 1 && bitout[1][cl-1] == 1 && bitout[2][cl-1] == 1 && bitout[3][cl-1] == 1 && bitout[4][cl-1] == 1 && bitout[5][cl-1] == 1 && bitout[6][cl-1] == 1 && bitout[7][cl-1] == 1) {
					p_dat = p_end;
				}
			}
		n = n+1;
		}
		k = k + 1;
		cnt = cnt + 1;
		if (bit == 1) {
			printf("+");
		for (m = 0; m < cl; m++) {
			for (t = 0; t<2; t++) {
				if (t == 1) {
					hval = (bitout[4][m])*8 +(bitout[5][m])*4 + (bitout[6][m])*2 + (bitout[7][m])*1;
					if (hval < 10) {
						hchar = hval + 48;
					}
					else if (hval == 10) {hchar = 'a';} else if (hval == 11) {hchar = 'b';} else if (hval == 12) {hchar = 'c';} else if (hval == 13) {hchar = 'd';}
					else if (hval == 14) {hchar = 'e';} else if (hval == 15) {hchar = 'f';}
				printf("%c",hchar);
				}
				else {
					hval = (bitout[0][m])*8 + (bitout[1][m])*4 + (bitout[2][m])*2 + (bitout[3][m])*1;
					if (hval < 10) {
						hchar = hval + 48;
					}
					else if (hval == 10) {hchar = 'a';} else if (hval == 11) {hchar = 'b';} else if (hval == 12) {hchar = 'c';} else if (hval == 13) {hchar = 'd';}
					else if (hval == 14) {hchar = 'e';} else if (hval == 15) {hchar = 'f';}
				printf("%c",hchar);
				}
			}
		}
		printf("\n");
	}
	}
		
	k = 0;
	bit = 0;
	cnt = cnt + 1;
	startrx = 1;
	char hexin[1024];
	printf("Input: ");
	fgets(hexin, 1024, stdin);
	int count_lines = (strlen(hexin)-2)/2;
	t = 0;
	int bitin[17][count_lines];
	int cl = 0;
	int hex = 0;
	int j = 0;
	int sbit[9] = {1, 1, 0, 1, 0, 1, 0, 1, 1};
	if (hexin[0] == 'e') {
		break;
	}
	else if (hexin[0] == '*') {
		while (cl<count_lines) {
			for (int t = 0; t < 9; t++) {
				bitin[t][cl] = sbit[t];
			}
			
			if (hexin[hex+1] == '0') {
				int bin[4] = {0, 0, 0, 0};
				for (int t = 9; t < 13; t++) {
					bitin[t][cl] = bin[t-9];
				}
			}
				
			else if(hexin[hex+1] == '1') {
				int bin[4] = {0, 0, 0, 1};
				for (int t = 9; t < 13; t++) {
					bitin[t][cl] = bin[t-9];
				}		
			}
			
			else if(hexin[hex+1] == '2') {
				int bin[4] = {0, 0, 1, 0};
				for (int t = 9; t < 13; t++) {
					bitin[t][cl] = bin[t-9];
				}		
			}
			
			else if(hexin[hex+1] == '3') {
				int bin[4] = {0, 0, 1, 1};
				for (int t = 9; t < 13; t++) {
					bitin[t][cl] = bin[t-9];
				}		
			}
			
			else if(hexin[hex+1] == '4') {
				int bin[4] = {0, 1, 0, 0};
				for (int t = 9; t < 13; t++) {
					bitin[t][cl] = bin[t-9];
				}		
			}
			
			else if(hexin[hex+1] == '5') {
				int bin[4] = {0, 1, 0, 1};
				for (int t = 9; t < 13; t++) {
					bitin[t][cl] = bin[t-9];
				}		
			}
			
			else if(hexin[hex+1] == '6') {
				int bin[4] = {0, 1, 1, 0};
				for (int t = 9; t < 13; t++) {
					bitin[t][cl] = bin[t-9];
				}		
			}
			
			else if(hexin[hex+1] == '7') {
				int bin[4] = {0, 1, 1, 1};
				for (int t = 9; t < 13; t++) {
					bitin[t][cl] = bin[t-9];
				}		
			}
			
			else if(hexin[hex+1] == '8') {
				int bin[4] = {1, 0, 0, 0};
				for (int t = 9; t < 13; t++) {
					bitin[t][cl] = bin[t-9];
				}		
			}
			
			else if(hexin[hex+1] == '9') {
				int bin[4] = {1, 0, 0, 1};
				for (int t = 9; t < 13; t++) {
					bitin[t][cl] = bin[t-9];
				}		
			}
			
			else if(hexin[hex+1] == 'a') {
				int bin[4] = {1, 0, 1, 0};
				for (int t = 9; t < 13; t++) {
					bitin[t][cl] = bin[t-9];
				}		
			}
			
			else if(hexin[hex+1] == 'b') {
				int bin[4] = {1, 0, 1, 1};
				for (int t = 9; t < 13; t++) {
					bitin[t][cl] = bin[t-9];
				}	
			}
			
			else if(hexin[hex+1] == 'c') {
				int bin[4] = {1, 1, 0, 0};
				for (int t = 9; t < 13; t++) {
					bitin[t][cl] = bin[t-9];
				}	
			}
			
			else if(hexin[hex+1] == 'd') {
				int bin[4] = {1, 1, 0, 1};
				for (int t = 9; t < 13; t++) {
					bitin[t][cl] = bin[t-9];
				}		
			}
			
			else if(hexin[hex+1] == 'e') {
				int bin[4] = {1, 1, 1, 0};
				for (int t = 9; t < 13; t++) {
					bitin[t][cl] = bin[t-9];
				}		
			}
			
			else if(hexin[hex+1] == 'f') {
				int bin[4] = {1, 1, 1, 1};
				for (int t = 9; t < 13; t++) {
					bitin[t][cl] = bin[t-9];
				}		
			}
			
			if (hexin[hex+2] == '0') {
				int bin[4] = {0, 0, 0, 0};
				for (int t = 13; t < 17; t++) {
					bitin[t][cl] = bin[t-13];
				}
			}
				
			else if(hexin[hex+2] == '1') {
				int bin[4] = {0, 0, 0, 1};
				for (int t = 13; t < 17; t++) {
					bitin[t][cl] = bin[t-13];
				}		
			}
			
			else if(hexin[hex+2] == '2') {
				int bin[4] = {0, 0, 1, 0};
				for (int t = 13; t < 17; t++) {
					bitin[t][cl] = bin[t-13];
				}		
			}
			
			else if(hexin[hex+2] == '3') {
				int bin[4] = {0, 0, 1, 1};
				for (int t = 13; t < 17; t++) {
					bitin[t][cl] = bin[t-13];
				}		
			}
			
			else if(hexin[hex+2] == '4') {
				int bin[4] = {0, 1, 0, 0};
				for (int t = 13; t < 17; t++) {
					bitin[t][cl] = bin[t-13];
				}		
			}
			
			else if(hexin[hex+2] == '5') {
				int bin[4] = {0, 1, 0, 1};
				for (int t = 13; t < 17; t++) {
					bitin[t][cl] = bin[t-13];
				}		
			}
			
			else if(hexin[hex+2] == '6') {
				int bin[4] = {0, 1, 1, 0};
				for (int t = 13; t < 17; t++) {
					bitin[t][cl] = bin[t-13];
				}		
			}
			
			else if(hexin[hex+2] == '7') {
				int bin[4] = {0, 1, 1, 1};
				for (int t = 13; t < 17; t++) {
					bitin[t][cl] = bin[t-13];
				}		
			}
			
			else if(hexin[hex+2] == '8') {
				int bin[4] = {1, 0, 0, 0};
				for (int t = 13; t < 17; t++) {
					bitin[t][cl] = bin[t-13];
				}		
			}
			
			else if(hexin[hex+2] == '9') {
				int bin[4] = {1, 0, 0, 1};
				for (int t = 13; t < 17; t++) {
					bitin[t][cl] = bin[t-13];
				}		
			}
			
			else if(hexin[hex+2] == 'a') {
				int bin[4] = {1, 0, 1, 0};
				for (int t = 13; t < 17; t++) {
					bitin[t][cl] = bin[t-13];
				}		
			}
			
			else if(hexin[hex+2] == 'b') {
				int bin[4] = {1, 0, 1, 1};
				for (int t = 13; t < 17; t++) {
					bitin[t][cl] = bin[t-13];
				}	
			}
			
			else if(hexin[hex+2] == 'c') {
				int bin[4] = {1, 1, 0, 0};
				for (int t = 13; t < 17; t++) {
					bitin[t][cl] = bin[t-13];
				}	
			}
			
			else if(hexin[hex+2] == 'd') {
				int bin[4] = {1, 1, 0, 1};
				for (int t = 13; t < 17; t++) {
					bitin[t][cl] = bin[t-13];
				}		
			}
			
			else if(hexin[hex+2] == 'e') {
				int bin[4] = {1, 1, 1, 0};
				for (int t = 13; t < 17; t++) {
					bitin[t][cl] = bin[t-13];
				}		
			}
			
			else if(hexin[hex+2] == 'f') {
				int bin[4] = {1, 1, 1, 1};
				for (int t = 13; t < 17; t++) {
					bitin[t][cl] = bin[t-13];
				}		
			}			
		cl = cl + 1;
		hex = hex + 2;
		}
	
	
	for (cl = 0; cl < count_lines; cl++) {
		for (t = 0; t<17; t++) {
			printf("%d", bitin[t][cl]);
		}
		printf("\n");
	}
	}
		ssize_t nbytes_rx, nbytes_tx;
		char *p_dat, *p_end;
		ptrdiff_t p_inc;
		nbytes_tx = iio_buffer_push(txbuf);
		if (nbytes_tx < 0) { printf("Error pushing buf %d\n", (int) nbytes_tx); shutdown(); }
		//printf("here3");
		// Refill RX buffer
		nbytes_rx = iio_buffer_refill(rxbuf);
		if (nbytes_rx < 0) { printf("Error refilling buf %d\n",(int) nbytes_rx); shutdown(); }
		//printf("here4");
		// WRITE: Get pointers to TX buf and write IQ to TX buf port 0
		
		p_dat = (char *)iio_buffer_first(txbuf, tx0_i);
		p_inc = iio_buffer_step(txbuf);
		p_end = iio_buffer_end(txbuf);
		m = 0;
		j = 0;
		cl = 0;
		k = 0;
		//printf("p_inc: %c, p_end: %s",p_inc, p_end);
		while (k <= 1) {
			// Example: fill with zeros
			// 12-bit sample needs to be MSB alligned so shift by 4
			// https://wiki.analog.com/resources/eval/user-guides/ad-fmcomms2-ebz/software/basic_iq_datafiles#binary_format
			if (j < 16) {
			((int16_t*)p_dat)[0] = (0); // Real (I)
			((int16_t*)p_dat)[1] = (0); // Imag (Q)
			}
			else if (bitin[m][cl] == 1){
				((int16_t*)p_dat)[0] = (2000); // Real (I)
				((int16_t*)p_dat)[1] = (2000); // Imag (Q)
				printf("%d", bitin[m][cl]);
			}
			else if (bitin[m][cl] == 0) {
				((int16_t*)p_dat)[0] = (-2000); // Real (I)
				((int16_t*)p_dat)[1] = (-2000); // Imag (Q)
				printf("%d", bitin[m][cl]);
			}
			if (m == 16) {
				m = 0;
				cl = cl + 1;
				if (cl > count_lines) {
					cl = 0;
					k = k + 1;
				}
				printf("\n");
			}
			else if (j >= 16) {
				m = m+1;
			}
			p_dat += p_inc;
			j = j + 1;
		}


		// Sample counter increment and status output
		nrx += nbytes_rx / iio_device_get_sample_size(rx);
		ntx += nbytes_tx / iio_device_get_sample_size(tx);
		printf("\tRX %8.2f MSmp, TX %8.2f MSmp\n", nrx/1e6, ntx/1e6);
		printf("\t%d Samples\n", n);
		printf("cl: %d\n", cl);
		n = 0;

		
	}
 	shutdown();
	return 0;
  } 