/*
 * Adapted in part from libiio - AD9361 IIO streaming example by Michael Feilen <feilen_at_iabg.de>
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
		(void) fprintf(stderr, "mAssertion failed (%s:%d)\n", __FILE__, __LINE__); \
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
	printf("d* Destroying buffers\n");
	if (rxbuf) { iio_buffer_destroy(rxbuf); }
	if (txbuf) { iio_buffer_destroy(txbuf); }

	printf("d* Disabling streaming channels\n");
	if (rx0_i) { iio_channel_disable(rx0_i); }
	if (rx0_q) { iio_channel_disable(rx0_q); }
	if (tx0_i) { iio_channel_disable(tx0_i); }
	if (tx0_q) { iio_channel_disable(tx0_q); }

	printf("d* Destroying context\n");
	if (ctx) { iio_context_destroy(ctx); }
	printf("mExiting...\n");
	exit(0);
}

static void handle_sig()
{
	printf("mWaiting for process to finish...\n");
	stop = true;
}

/* check return value of attr_write function */
static void errchk(int v, const char* what) {
	 if (v < 0) { fprintf(stderr, "mError %d writing to channel \"%s\"\nvalue may not be supported.\n", v, what); shutdown(); }
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
	printf("d* Acquiring AD9361 phy channel %d\n", chid);
	if (!get_phy_chan(ctx, type, chid, &chn)) {	return false; }
	wr_ch_str(chn, "rf_port_select",     cfg->rfport);
	wr_ch_lli(chn, "rf_bandwidth",       cfg->bw_hz);
	wr_ch_lli(chn, "sampling_frequency", cfg->fs_hz);

	// Configure LO channel
	printf("d* Acquiring AD9361 %s lo channel\n", type == TX ? "TX" : "RX");
	if (!get_lo_chan(ctx, type, &chn)) { return false; }
	wr_ch_lli(chn, "frequency", cfg->lo_hz);
	return true;
}

unsigned char HexChar (char c)
{
    if ('0' <= c && c <= '9') return (unsigned char)(c - '0');
    if ('A' <= c && c <= 'F') return (unsigned char)(c - 'A' + 10);
    if ('a' <= c && c <= 'f') return (unsigned char)(c - 'a' + 10);
    return 0xFF;
}

unsigned char hexToBin (char hex[]) {
	unsigned char result = HexChar(hex[0]) << 4;
	return result | HexChar(hex[1]);
}

/* simple configuration and streaming */
int main (int argc, char **argv)
{
	bool verbose = true;
	bool diagnostics = false; //allow running testing values
	bool binaryTestPattern = false; //used in conjunction with diagnostics to just send a simple repeated test pattern instead of more complex traffic

	//TODO here are the new bitwise operation constants and variables
	//these are declared outside the while loop since he buffer (will likely) split some of our actual data between pulls
	//TODO I switched the byteflag to 8 bits
	const char BYTE_FLAG[] = {0b00000001,0b00000010,0b00000100,0b00001000,0b00010000,0b00100000,0b01000000,0b10000000};
	const unsigned short HEADER               = 0b0000000110101011; //this is the 9 bit header that signals coming data
	const unsigned short HEADER_MASK          = 0b0000000111111111;
	const unsigned short LEAST_SIG_BIT_HEADER = 0b0000000000000001;
	const unsigned short INVERSE_HEADER       = 0b0000000001010100;
	const unsigned short SHORT_FLAG[] = {0b0000000000000001,0b0000000000000010,0b0000000000000100,0b0000000000001000,0b0000000000010000,0b0000000000100000,0b0000000001000000,0b0000000010000000,0b0000000100000000,0b0000001000000000,0b0000010000000000,0b0000100000000000,0b0001000000000000,0b0010000000000000,0b0100000000000000,0b1000000000000000};
	//const unsigned char LOWER_BITS_MASK = 0b00001111;
	int16_t SIGNAL_THRESHOLD = 1200;
	//const int16_t TRANSMIT_SIGNAL_VALUE = 2000; //TODO AD9361 bus-width is 12-bit so maybe shift left by 4?
	const int16_t TRANSMIT_SIGNAL_POS = 2000;
	const int16_t TRANSMIT_SIGNAL_NEG = -2000;
	const int MAX_BYTES_PER_LINE = 250;
	const unsigned char MOST_SIG_BIT = 0b10000000;
	const unsigned char LEAST_SIG_BIT = 0b00000001;
	unsigned char hexin[1024];
	int bitIndex = 0;
	unsigned short tempHeader = (unsigned short)0;
	bool isReadingHeader = true;
	bool isSignalInverted = false;
	int bytesSentThisLine = 0;
	//struct timespec start, end; //used to measure elapsed time
	bool testDataSent = false;

	ssize_t nbytes_rx, nbytes_tx;
	char *p_dat, *p_end;
	ptrdiff_t p_inc;

	float txf = 0.0; //assigned tX freq (if any)
	float rxf = 0.0; //assigned rX freq (if any)
	//ingest arguments
	if (argc > 0) {
		int pt = 0;
		for (int j=0; j<argc; j++) {
			if (strcmp("-tx",argv[j]) == 0) {
				pt = 1;
			} else if (strcmp("-rx",argv[j]) == 0) {
				pt = 2;
			} else if (strcmp("-threshold",argv[j]) == 0) {
				pt = 3;
			} else if (strcmp("-minComms",argv[j]) == 0) {
				printf("m Starting in min verbosity mode\n");
				verbose = false;
			} else if (strcmp("-test",argv[j]) == 0) {
				printf("m Running test traffic. SqANDR will wait for 5 cycles then send test traffic.\n");
				diagnostics = true;
			} else if (strcmp("-test01",argv[j]) == 0) {
				printf("m Running test traffic with just a repeated binary pattern. SqANDR will wait for 5 cycles then send test traffic.\n");
				diagnostics = true;
				binaryTestPattern = true;
			} else if ((strcmp("help",argv[j]) == 0) || (strcmp("-help",argv[j])==0)) {
				printf("SqANDR - the Squad Area Network Defined Radio app. This is the PlutoSDR app that serves as a companion to the SqAN Android app (https://github.com/sofwerx/sqan) and is intended to provide a link from the PlutoSDR to the Android device. SqANDR is intended to be called from within the SqAN app on Android but there are some basic capabilities included for manual diagnostics.\n\n");
				printf("Help, valid commands are:\n -rx [freq in MHz] = sets Rx freq\n -tx [freq in MHz] = sets Rx freq\n -threshold [signal threshold] = sets the minimum \"a\" level to be considered a signal\n -minComms = sets least verbose mode\n -test = runs in diagnostic mode\n -test01 = runs in diagnostic mode with a simple repeated pattern\n\n");
				exit(0);
			} else if (pt > 0) {
				float val = atof(argv[j]);
				if (val > 0.1) {
					switch(pt) {
						case 1:
							txf = val;
							break;

						case 2:
							rxf = val;
							break;

						case 3:
							SIGNAL_THRESHOLD = val;
							printf("m Threshold signal set to %d\n",SIGNAL_THRESHOLD);
							break;
					}
					pt = 0;
				}
			}
		}
	}

	// Streaming devices
	struct iio_device *tx;
	struct iio_device *rx;

	// Stream configurations
	struct stream_cfg rxcfg;
	struct stream_cfg txcfg;

	// Listen to ctrl+c and ASSERT
	signal(SIGINT, handle_sig);

	// RX stream config
	rxcfg.bw_hz = MHZ(2);   // 2 MHz rf bandwidth
	rxcfg.fs_hz = MHZ(2.5);   // 2.5 MS/s rx sample rate
	if (rxf > 0.1) {
		printf("m Assigning RX frequency = %f MHz\n", rxf);
		rxcfg.lo_hz = MHZ(rxf);
	} else
		rxcfg.lo_hz = GHZ(2.5); // 2.5 GHz rf frequency
	rxcfg.rfport = "A_BALANCED"; // port A (select for rf freq.)

	// TX stream config
	txcfg.bw_hz = MHZ(1.5); // 1.5 MHz rf bandwidth
	txcfg.fs_hz = MHZ(2.5);   // 2.5 MS/s tx sample rate
	if (txf > 0.1) {
		printf("mAssigning TX frequency = %f MHz\n", txf);
		txcfg.lo_hz = MHZ(txf);
	} else
		txcfg.lo_hz = GHZ(2.5); // 2.5 GHz rf frequency
	txcfg.rfport = "A"; // port A (select for rf freq.)

	if (verbose)
		printf("d* Acquiring IIO context\n");
	ASSERT((ctx = iio_create_default_context()) && "No context");
	ASSERT(iio_context_get_devices_count(ctx) > 0 && "No devices");

	if (verbose)
		printf("d* Acquiring AD9361 streaming devices\n");
	ASSERT(get_ad9361_stream_dev(ctx, TX, &tx) && "No tx dev found");
	ASSERT(get_ad9361_stream_dev(ctx, RX, &rx) && "No rx dev found");

	if (verbose)
		printf("d* Configuring AD9361 for streaming\n");
	ASSERT(cfg_ad9361_streaming_ch(ctx, &rxcfg, RX, 0) && "RX port 0 not found");
	ASSERT(cfg_ad9361_streaming_ch(ctx, &txcfg, TX, 0) && "TX port 0 not found");

	if (verbose)
		printf("d* Initializing AD9361 IIO streaming channels\n");
	ASSERT(get_ad9361_stream_ch(ctx, RX, rx, 0, &rx0_i) && "RX chan i not found");
	ASSERT(get_ad9361_stream_ch(ctx, RX, rx, 1, &rx0_q) && "RX chan q not found");
	ASSERT(get_ad9361_stream_ch(ctx, TX, tx, 0, &tx0_i) && "TX chan i not found");
	ASSERT(get_ad9361_stream_ch(ctx, TX, tx, 1, &tx0_q) && "TX chan q not found");

	if (verbose)
		printf("d* Enabling IIO streaming channels\n");
	iio_channel_enable(rx0_i);
	iio_channel_enable(rx0_q);
	iio_channel_enable(tx0_i);
	iio_channel_enable(tx0_q);

	if (verbose)
		printf("d* Creating non-cyclic IIO buffers with 1 MiS\n");
	rxbuf = iio_device_create_buffer(rx, 17*300, false);
	if (!rxbuf) {
		perror("m Could not create RX buffer");
		shutdown();
	}

	//txbuf = iio_device_create_buffer(tx, 17*300, true);
	txbuf = iio_device_create_buffer(tx, 17*300, false); //TODO I switched this as I don't think libiios approach to the circular buffer meets our needs
	if (!txbuf) {
		perror("m Could not create TX buffer");
		shutdown();
	}

	p_inc = iio_buffer_step(txbuf);
	p_end = iio_buffer_end(txbuf);
	for (p_dat = (char *)iio_buffer_first(txbuf, tx0_i); p_dat < p_end; p_dat += p_inc) { //load the output buffer with empty data
		((int16_t*)p_dat)[0] = (0); // Real (I)
		((int16_t*)p_dat)[1] = (0); // Imag (Q)
	}

	int16_t a = 0;

	int cnt = 0;
	int startrx = 0;
	int bufferCycleCount = 0;
	printf("m Starting IO streaming (press CTRL+C to cancel)\n");

	unsigned char tempByte = (unsigned char)0; //using char since C doesn't have a byte type

	int testCounter = 0;

	/**
	 * Clear out the receive buffer
	 */
	if (verbose)
		printf("d clearing out the Rx buffer...\nd ");
	for (int i=0;i<128;i++) {
		if (verbose)
			printf("-");
		nbytes_rx = iio_buffer_refill(rxbuf);
		if (nbytes_rx < 0) { printf("mError refilling buf %d\n",(int) nbytes_rx); shutdown(); }
	}
	if (verbose) {
		printf("\nd Rx buffer cleared out\n");
		printf("d clearing out the Tx buffer...\nd ");
	}
	for (int i=0;i<64;i++) {
		if (verbose)
			printf("-");
		nbytes_tx = iio_buffer_push(txbuf);
		if (nbytes_tx < 0) { printf("mError pushing buf %d\n",(int) nbytes_tx); shutdown(); }
	}
	if (verbose)
		printf("\nd Tx buffer cleared out\n");

	int count = 0; // a counter just used in the binary test pattern
	while (!stop) {
		//if (diagnostics)
		//	sleep(1);

		bufferCycleCount = 0;
		while (bufferCycleCount < 1) {
			// Schedule TX buffer
			nbytes_tx = iio_buffer_push(txbuf);
			if (nbytes_tx < 0) { printf("m Error pushing buf %d\n", (int) nbytes_tx); shutdown(); }
			if (verbose)
				printf("d Pushed %db to Tx buffer\n",(int)nbytes_tx);

			// Refill RX buffer
			nbytes_rx = iio_buffer_refill(rxbuf);
			if (nbytes_rx < 0) { printf("m Error refilling buf %d\n",(int) nbytes_rx); shutdown(); }
			if (verbose)
				printf("d Refill Rx buffer provided %db\n",(int)nbytes_rx);

			p_inc = iio_buffer_step(rxbuf);
			p_end = iio_buffer_end(rxbuf);

			/**
			 * READING the Rx buffer
			 */
			if (verbose)
				printf("m Reading buffer...\n");
			bool multiline = false;
			int bitsSearchedForHeader = 0;
			int samplesWithNoSignal = 0;
			int noiseBits = 0;
			int totalSamples = 0;
			int aNoisePosU = 0; //used to record the noise range
			int aNoisePosL = 0;
			int aNoiseNegU = 0;
			int aNoiseNegL = 0;
			int tempCounter = 0;
			for (p_dat = (char *)iio_buffer_first(rxbuf, rx0_i); p_dat < p_end; p_dat += p_inc) {
				totalSamples++;
				const int16_t i = ((int16_t*)p_dat)[0]; // Real (I)
				const int16_t q = ((int16_t*)p_dat)[1]; // Imag (Q)
				//lowest noise approach so far appears to be in taking the higher magnitude of I or Q
				if (abs(i) > abs(q))
					a = i;
				else
					a = q;

				/*if (diagnostics && (i != 0) && (q != 0)) {
					if ((abs(i) > SIGNAL_THRESHOLD/2) && (abs(q) > SIGNAL_THRESHOLD/2)) {
						if ((abs(i)/i) != abs(q)/q)
							printf("i=%d and q=%d have different signs\n",i,q);
					}
				}*/

				if (binaryTestPattern) {
					if (a >= SIGNAL_THRESHOLD)
						printf("1");
					else if (a <= -SIGNAL_THRESHOLD)
						printf("0");
					else
						printf("-");
					count++;
					if (count > 98) {
						printf("\n");
						count = 0;
					}
					continue;
				} else {
					if (abs(a) < SIGNAL_THRESHOLD) {
						samplesWithNoSignal++;
						continue; //ignore noise below the signal threshold
					}

					if (diagnostics && !testDataSent) {
						noiseBits++;
						if (a < 0) {
							if (a < aNoiseNegL) {
								aNoiseNegL = a;
								if (aNoiseNegU == 0)
									aNoiseNegU = a;
							} else if (a > aNoiseNegU)
								aNoiseNegU = a;
						} else {
							if (a > aNoisePosU) {
								aNoisePosU = a;
								if (aNoisePosL == 0)
									aNoisePosL = a;
							} else if (a < aNoisePosL)
								aNoisePosL = a;
						}
					}
				}

				if (isReadingHeader) {
					bitsSearchedForHeader++;
					bool headerComplete = false;
					tempHeader = tempHeader << 1; //move bits over to make room for new bit
					if (a >= SIGNAL_THRESHOLD)
						tempHeader = tempHeader | LEAST_SIG_BIT_HEADER;
					tempHeader = tempHeader & HEADER_MASK;

					if (tempHeader == HEADER) {
						headerComplete = true;
						isSignalInverted = false;
					} else if (tempHeader == INVERSE_HEADER) {
						headerComplete = true;
						isSignalInverted = true;
					} else {
						if (diagnostics && testDataSent) {
							//if (tempHeader != (unsigned char)0) {
							if (bitsSearchedForHeader == 10)
								printf("...\n");
							else if ((bitsSearchedForHeader > 10) && (bitsSearchedForHeader < 20)) {
								printf("looking for header in ");
								for (int i=8;i>=0;i--) {
									if ((tempHeader & SHORT_FLAG[i]) == SHORT_FLAG[i])
										printf("1");
									else
										printf("0");
								}
								printf("\n");
							} else if (bitsSearchedForHeader == 20)
								printf("...\n");
						}
					}

					if (headerComplete) {
						tempHeader = (unsigned char)0;
						if (diagnostics) {
							printf("m Header found");
							if (isSignalInverted)
								printf(" (inverted)");
							if (bitsSearchedForHeader > 10)
								printf(" (%d bits dropped before this header)",bitsSearchedForHeader-10);
							printf("\n");
						}
						isReadingHeader = false;
						bitIndex = 0;
						tempByte = (unsigned char)0;
						bitsSearchedForHeader = 0;
					}
				} else {
					bitIndex++;
					tempByte = tempByte << 1;
					if (isSignalInverted) {
						if (a <= -SIGNAL_THRESHOLD) {
							tempByte = tempByte | LEAST_SIG_BIT;
						}
					} else {
						if (a >= SIGNAL_THRESHOLD) {
							tempByte = tempByte | LEAST_SIG_BIT;
						}
					}
					if (bitIndex > 7) {
						if (!diagnostics && (bytesSentThisLine < 1))
							printf("+");
						if (diagnostics)
							printf("\t\t\t#### Byte Found: ");
						//print out this byte in Hex
						char *a = "0123456789abcdef"[tempByte >> 4];
						char *b = "0123456789abcdef"[tempByte & 0x0F];
						printf("%c%c",a,b);
						if (diagnostics)
							printf(" (i == %d, q == %d) ####\n",i,q);

						isReadingHeader = true; //go back to reading the header

						//check to see if we need a new line
						bytesSentThisLine++;
						if (bytesSentThisLine > MAX_BYTES_PER_LINE) { //prevent our output from exceeding what we can send on a single line
							if (diagnostics)
								printf("Received data is long, making a new line...");
							printf("\n");
							multiline = true;
							bytesSentThisLine = 0;
						}
					}
				}
			}
			if (!multiline)
				printf("\n");
			if (verbose) {
				if (totalSamples == samplesWithNoSignal)
					printf("d no data received\n");
				else {
					printf("d %d total samples",totalSamples);
					if (samplesWithNoSignal > 0)
						printf(", %d samples had no bit value",samplesWithNoSignal);
					if (noiseBits > 0) {
						printf(", at least %d bits were noise before any transmission",noiseBits);
						printf(" \"a\" noise values ranged from %d to %d and %d to %d",aNoiseNegL,aNoiseNegU,aNoisePosL,aNoisePosU);
					}
					if (bitsSearchedForHeader > 0)
						printf(", %d bits dropped without finding any header\n",bitsSearchedForHeader);
					printf("\n");
				}
			}
			bufferCycleCount++;
		}

		bufferCycleCount = 0;
		cnt = cnt + 1;
		startrx = 1;

		/**
		 * READING serial input and sending to the Tx buffer
		 */

		if (diagnostics) {
			const char TEST_CHARS_A[] = {'*','0','0','1','1','2','2','3','3','4','4','5','5','6','6','7','7','8','8','9','9','a','a','b','b','c','c','d','d','e','e','f','f','\n','\0'};
			const char TEST_CHARS_B[] = {'*','0','1','1','2','2','3','3','4','4','5','5','6','6','7','7','8','8','9','9','a','a','b','b','c','c','d','d','e','e','f','f','0','\n','\0'};
			if (testCounter < 20) {
				if (testCounter == 5) {
					printf("Diagnostic test is sending Test Message A for transmission\n");
					for (int i=0; i< (int)( sizeof(TEST_CHARS_A)/sizeof(TEST_CHARS_A[0]) ); i++) {
						hexin[i] = TEST_CHARS_A[i];
					}
					testDataSent = true;
				/*} else if (testCounter == 12) {
					printf("Diagnostic test is sending Test Message B for transmission\n");
					for (int i=0; i< (int)( sizeof(TEST_CHARS_B)/sizeof(TEST_CHARS_B[0]) ); i++) {
						hexin[i] = TEST_CHARS_B[i];
					}
					testDataSent = true;*/
				} else {
					if (testDataSent)
						printf("Diagnostic test is listening for last transmission\n");
					else
						printf("Diagnostic test is just listening for noise on the line\n");
					hexin[0] = '\n';
					hexin[1] = '\0';
				}
			} else {
				hexin[0] = 'e';
				hexin[1] = '\n';
				hexin[2] = '\0';
			}
			if (binaryTestPattern)
				printf("Input: 0100 repeating\n");
			else
				printf("Input: %s",hexin);
			testCounter++;
		} else {
			if (verbose)
				printf("dInput:\n");
			fgets(hexin, 1024, stdin);
		}
		int bytesInput;
		int inputLength = strlen(hexin);
		if (inputLength < 1)
			bytesInput = 0;
		else
			bytesInput = (inputLength-2)/2;
		if (verbose)
			printf("dInput Length: %db\n",bytesInput);

		if (hexin[0] == 'e')
			break;
		if (bytesInput < 1)
			continue;
		unsigned char bitin[bytesInput];
		int cl = 0;
		int hex = 1;
		unsigned char hexchar[2];
		if (hexin[0] == '*') {
			while (cl<bytesInput) {
				hexchar[0] = hexin[hex];
				hexchar[1] = hexin[hex + 1];
				bitin[cl] = hexToBin(hexchar);
				if (verbose && diagnostics && !binaryTestPattern) {
					printf("Current hex: %c%c = ",hexchar[0],hexchar[1]);

					unsigned char asBytes = hexToBin(hexchar);
					for (int i=0;i<8;i++) {
						if ((asBytes & MOST_SIG_BIT) == MOST_SIG_BIT)
							printf("1");
						else
							printf("0");
						asBytes = asBytes << 1;
					}

					printf(" = %d",(unsigned int)bitin[cl]);
					printf("\n");
				}
				cl++;
				hex += 2;
			}
		}

		// WRITE: Get pointers to TX buf and write IQ to TX buf port 0
		p_dat = (char *)iio_buffer_first(txbuf, tx0_i);
		p_inc = iio_buffer_step(txbuf);
		p_end = iio_buffer_end(txbuf);
		bufferCycleCount = 0;
		if (verbose)
			printf("dAdding %ib to Tx buffer:\n",bytesInput);
		while (bufferCycleCount < 1) {
			if (binaryTestPattern) {
				int temp = 0;
				while (p_dat < p_end) {
					temp++;
					if (temp == 2) {
						((int16_t*)p_dat)[0] = TRANSMIT_SIGNAL_POS; // Real (I)
						((int16_t*)p_dat)[1] = TRANSMIT_SIGNAL_POS; // Imag (Q)
					} else {
						((int16_t*)p_dat)[0] = TRANSMIT_SIGNAL_NEG; // Real (I)
						((int16_t*)p_dat)[1] = TRANSMIT_SIGNAL_NEG; // Imag (Q)
					}
					if (temp > 4) {
						((int16_t*)p_dat)[0] = (0); // Real (I)
						((int16_t*)p_dat)[1] = (0); // Imag (Q)
					}
					if (temp > 5)
						temp = 0;
					p_dat += p_inc;
				}
				if (verbose)
					printf("d Sending repeated 0100 as a signal");
				bufferCycleCount++;
				continue;
			}

			if (verbose)
				printf("d Sending byte data with 9 bit header to the buffer\n");

			//Send leading "no signal" bytes
			for (int i=0;i<16;i++) {
				if (p_dat > p_end) {
					printf("m Error - header was larger than remaining buffer size (this should not happen)");
					break;
				}
				((int16_t*)p_dat)[0] = 0; // Real (I)
				((int16_t*)p_dat)[1] = 0; // Imag (Q)
			}

			for (int bytePayloadIndex=0;bytePayloadIndex<=bytesInput;bytePayloadIndex++) { //send the data
				for (int i=8;i>=0;i--) { //send header 9 bits
					if (p_dat > p_end) {
						printf("m Error - header was larger than remaining buffer size (this should not happen)");
						break;
					}
					if ((HEADER & SHORT_FLAG[i]) == SHORT_FLAG[i]) {
						((int16_t*)p_dat)[0] = TRANSMIT_SIGNAL_POS; // Real (I)
						((int16_t*)p_dat)[1] = TRANSMIT_SIGNAL_POS; // Imag (Q)
						if (verbose)
							printf("1");
					} else {
						((int16_t*)p_dat)[0] = TRANSMIT_SIGNAL_NEG; // Real (I)
						((int16_t*)p_dat)[1] = TRANSMIT_SIGNAL_NEG; // Imag (Q)
						if (verbose)
							printf("0");
					}
					p_dat += p_inc;
				}
				if (verbose)
					printf(" ");

				//send actual byte
				for (int i=7;i>=0;i--) {
					if (p_dat > p_end) {
						printf("m Error - byte data was larger than remaining buffer size (this should not happen)");
						break;
					}
					if ((bitin[bytePayloadIndex] & BYTE_FLAG[i]) == BYTE_FLAG[i]) {
						((int16_t*)p_dat)[0] = TRANSMIT_SIGNAL_POS; // Real (I)
						((int16_t*)p_dat)[1] = TRANSMIT_SIGNAL_POS; // Imag (Q)
						if (verbose)
							printf("1");
					} else {
						((int16_t*)p_dat)[0] = TRANSMIT_SIGNAL_NEG; // Real (I)
						((int16_t*)p_dat)[1] = TRANSMIT_SIGNAL_NEG; // Imag (Q)
						if (verbose)
							printf("0");
					}
					p_dat += p_inc;
				}
				if (verbose)
					printf(" = %d\n",(unsigned int)bitin[bytePayloadIndex]);
				p_dat += p_inc;
			}

			int emptySignalCount = 0;
			while (p_dat < p_end) {
				((int16_t*)p_dat)[0] = (0); // Real (I)
				((int16_t*)p_dat)[1] = (0); // Imag (Q)
				if (verbose)
					emptySignalCount++;
				p_dat += p_inc;
			}
			if (verbose)
				printf("d Fill the rest of the buffer with %ib of no signal\n",emptySignalCount);

			bufferCycleCount++;
		}
	}
 	shutdown();
	return 0;
}
