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
	system("losetup /dev/loop1 /opt/vfat.img -o 512");
 
	system("mount /dev/loop1 /root");
	
	struct tm tm_now;
	char filename[128];
	time_t now = time(NULL);
	localtime_r(&now, &tm_now);

	strftime(filename, sizeof(filename), "filename_%Y_%m_%d_%H_%M.txt", &tm_now);
   
    FILE *nPack = fopen("/root/nPack.txt", "r");
	int count_lines = 0;
	char chr;
	printf("%ld\n",now);
	int t = 0;

	for (chr = getc(nPack); chr != 97; chr = getc(nPack)) {
		//Count whenever new line is encountered
		if (chr == '\n'){
			count_lines = count_lines + 1;
		}
		//take next character from file.
	}

	int bitin[34][count_lines];
	int cl = 0;
	int sbit[18] = {1, 1, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 1, 1};
	for (cl = 0; cl<count_lines; cl++) {
		for (int t = 0; t < 18; t++) {
			bitin[t][cl] = sbit[t];
		}
	}
	fclose(nPack);
	nPack = fopen("/root/nPack.txt", "r");
	cl = 0;
	t = 18;
	char c;
	for (c = getc(nPack); c != 97; c = getc(nPack)) {
		//Count whenever new line is encountered
		if (c == '\n'){
			//printf(", Line: %d, Together: ", cl);
			for (t = 0; t<34; t++) {
				//printf("%d", bitin[t][cl]);
			}
			//printf("\n");
			cl = cl + 1;
			t = 16;
		}
		else if (c != 13) {
			int z = c - 48;
			bitin[t][cl] = z;
			bitin[t+1][cl] = z;
			//printf("%d", bitin[t][cl]);
		}
		t = t + 2;
		//take next character from file.
	}
    fclose ( nPack );
	
	/*for (cl = 0; cl < count_lines; cl++) {
		for (t = 0; t<34; t++) {
			printf("%d", bitin[t][cl]);
		}
		printf("\n");
	}*/

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
	rxbuf = iio_device_create_buffer(rx, 50*count_lines, false);
	if (!rxbuf) {
		perror("Could not create RX buffer");
		shutdown();
	}
	txbuf = iio_device_create_buffer(tx, 50*count_lines, false);
	if (!txbuf) {
		perror("Could not create TX buffer");
		shutdown();
	}
 
	int m = 1;
	int n = 0;
	int j = 0;
	int h;
	int flag = 0;
	int a = 0;
	int bitout[8][500];
	int head[10000];
	int bitflag = 0;
	int half = 0;
	int whole = 0;
	int cnt = 0;
	printf("* Starting IO streaming (press CTRL+C to cancel)\n");
	while (cnt < 6)
	{		
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
		p_inc = iio_buffer_step(txbuf);
		p_end = iio_buffer_end(txbuf);
		
		cl = 0;
		for (p_dat = (char *)iio_buffer_first(txbuf, tx0_i); p_dat < p_end; p_dat += p_inc) {
			// Example: fill with zeros
			// 12-bit sample needs to be MSB alligned so shift by 4
			// https://wiki.analog.com/resources/eval/user-guides/ad-fmcomms2-ebz/software/basic_iq_datafiles#binary_format
			if (bitin[m][cl] == 2) {
				((int16_t*)p_dat)[0] = (2000); // Real (I)
				((int16_t*)p_dat)[1] = (-1000); // Imag (Q)
				//printf("Odd\n");
			}
			if (bitin[m][cl] == 1){
				((int16_t*)p_dat)[0] = (2000); // Real (I)
				((int16_t*)p_dat)[1] = (2000); // Imag (Q)
				//printf("Even\n");
			}
			if (bitin[m][cl] == 0) {
				((int16_t*)p_dat)[0] = (-2000); // Real (I)
				((int16_t*)p_dat)[1] = (-2000); // Imag (Q)
				//printf("Odd\n");
			}
			if (m == 33) {
				m = 0;
				cl = cl + 1;
			}
			else{
				m = m+1;
			}
		}
		//printf("here5");
		cl = 0;
		// READ: Get pointers to RX buf and read IQ from RX buf port 0
		p_inc = iio_buffer_step(rxbuf);
		p_end = iio_buffer_end(rxbuf);
		for (p_dat = (char *)iio_buffer_first(rxbuf, rx0_i); p_dat < p_end; p_dat += p_inc) {
			// Example: swap I and Q
			const int16_t i = ((int16_t*)p_dat)[0]; // Real (I)
			const int16_t q = ((int16_t*)p_dat)[1]; // Imag (Q)
			//length = snprintf( NULL, 0, "%d", i );
			//str = malloc( length + 1);
			//arr[j][1] = snprintf( str, length + 1, "%d", i );
			//length = snprintf( NULL, 0, "%d", q );
			//str = malloc( length + 1);
			//arr[j][2] = snprintf( str, length + 1, "%d", q );
			a = (i << 16) | (q & 0xFFFF);
			head[n] = a;
			printf("\tI: %d, Q: %d, Comb: %d, Half:%d, Line: %d, count: %d\n", i, q, a, half, cl, cnt);
			n = n+1;
			j = j+1;
			if (n>17) {
				if ((head[n-18] > 0 && head[n-17] > 0 && head[n-16] > 0 && head[n-15] > 0 && head[n-14] < 0 && head[n-13] < 0 && head[n-12] > 0 && head[n-11] > 0 && head[n-10] < 0 && head[n-9] < 0 && head[n-8] > 0 && head[n-7] > 0 && head[n-6] < 0 && head[n-5] < 0 && head[n-4] > 0 && head[n-3] > 0 && head[n-2] > 0 && head[n-1] > 0) || bitflag == 1) { 
					if (half == 1 || half == 3 || half == 5 || half == 7 || half == 9 || half == 11 || half == 13 || half == 15) {
						if (a < -100000) {
							bitout[whole][cl] = 0;
						}
						else if (a > 100000) {
							bitout[whole][cl] = 1;
						}
						whole = whole + 1;
					}
					half = half + 1;
					bitflag = 1;
					if (half == 16) {
						bitflag = 0;
						//printf("\t%d%d%d%d%d%d%d%d\n",bitout[0][cl], bitout[1][cl], bitout[2][cl], bitout[3][cl], bitout[4][cl], bitout[5][cl], bitout[6][cl], bitout[7][cl]);
						whole = 0;
						half = 0;
						cl = cl + 1;
					}
				}
				else if ((head[n-18] < 0 && head[n-17] < 0 && head[n-16] < 0 && head[n-15] < 0 && head[n-14] > 0 && head[n-13] > 0 && head[n-12] < 0 && head[n-11] < 0 && head[n-10] > 0 && head[n-9] > 0 && head[n-8] < 0 && head[n-7] < 0 && head[n-6] > 0 && head[n-5] > 0 && head[n-4] < 0 && head[n-3] < 0 && head[n-2] < 0 && head[n-1] < 0) || bitflag == 2) { 
					if (half == 1 || half == 3 || half == 5 || half == 7 || half == 9 || half == 11 || half == 13 || half == 15) {
						if (a < -100000) {
							bitout[whole][cl] = 1;
						}
						else if (a > 100000) {
							bitout[whole][cl] = 0;
						}
						whole = whole + 1;
					}
					half = half + 1;
					bitflag = 2;
					if (half == 16) {
						bitflag = 0;
						//printf("\t%d%d%d%d%d%d%d%d\n",bitout[0][cl], bitout[1][cl], bitout[2][cl], bitout[3][cl], bitout[4][cl], bitout[5][cl], bitout[6][cl], bitout[7][cl]);
						whole = 0;
						half = 0;
						cl = cl + 1;
					}
				}
				if (bitout[0][cl-1] == 1 && bitout[1][cl-1] == 1 && bitout[2][cl-1] == 1 && bitout[3][cl-1] == 1 && bitout[4][cl-1] == 1 && bitout[5][cl-1] == 1 && bitout[6][cl-1] == 1 && bitout[7][cl-1] == 1) {
					p_dat = p_end;
				}
			}
		}


		// Sample counter increment and status output
		nrx += nbytes_rx / iio_device_get_sample_size(rx);
		ntx += nbytes_tx / iio_device_get_sample_size(tx);
		printf("\tRX %8.2f MSmp, TX %8.2f MSmp\n", nrx/1e6, ntx/1e6);
		printf("\t%d Samples\n", n);
		printf("cl: %d\n", cl);
		n = 0;
		if (flag == 0 && cnt > 4) {
			FILE *fp;
			char fname[20] = ("/root/");
			strcat(fname, filename);
			strcat(fname,".txt");
			printf("filename: %s\n", fname);
			fp = fopen(fname, "w");
			for (count_lines = 0; count_lines < cl; count_lines++) {
				for (h = 0; h < 8; ++h) {
					fprintf(fp, "%d", bitout[h][count_lines]);
					//printf("%d", bitout[h][count_lines]);
				}
			fprintf(fp, "\n");
			//printf("\n");
			}
			fclose(fp);
			flag = 1;
		}

		cnt = cnt + 1;
	}
 	shutdown();
	return 0;
  } 