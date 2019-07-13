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
#include <unistd.h>
#include <fcntl.h>

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

//static void set_gain(float gain) {
//	iio_channel_attr_write_longlong(iio_device_find_channel(get_ad9361_phy(ctx), "voltage0", true), "hardwaregain", gain);
//}

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
	bool listenOnlyMode = false; //continously listens and displays received traffic
	bool binaryTestPattern = false; //used in conjunction with diagnostics to just send a simple repeated test pattern instead of more complex traffic

	clock_t startTime, stopTime;

	//const char TEST_CHARS[] = {'6','6','9','9','0','0','1','1','2','2','3','3','4','4','5','5','6','6','7','7','8','8','9','9','a','a','b','b','c','c','d','d','e','e','f','f','0','0','1','1','2','2','3','3','4','4','5','5','6','6','7','7','8','8','9','9','a','a','b','b','c','c','d','d','e','e','f','f','0','0','1','1','2','2','3','3','4','4','5','5','6','6','7','7','8','8','9','9','a','a','b','b','c','c','d','d','e','e','f','f'};

	const char BYTE_FLAG[] = {0b00000001,0b00000010,0b00000100,0b00001000,0b00010000,0b00100000,0b01000000,0b10000000};
	const unsigned short HEADER                = 0b0000101101010011; //this is the 11 bit header that signals coming data
	//const unsigned short PART_HEADER           = 0b0000101101011100; //this is the 9 bit header that 
	//const unsigned short PART_HEADER_2         = 0b0000101101000011; //this is the 9 bit header that 
	//const unsigned short PART_HEADER_3         = 0b0000100101010011; //this is the 9 bit header that 
	//const unsigned short PART_HEADER_4         = 0b0000101101011011; //this is the 9 bit header that 
	const unsigned short HEADER_MASK           = 0b0000111111111111;
	//const unsigned short LONG_HEADER_MASK      = 0b0111111111111111;
	const unsigned short LEAST_SIG_BIT_HEADER  = 0b0000000000000001;
	const unsigned short MOST_SIG_BIT_HEADER   = 0b1000000000000000;
	const unsigned short INVERSE_HEADER        = 0b0000010010101100;
	//const unsigned short INVERSE_PART_HEADER   = 0b0000010010100111; 
	//const unsigned short INVERSE_PART_HEADER_2 = 0b0000010010111100;
	//const unsigned short INVERSE_PART_HEADER_3 = 0b0000100101010011; //this is the 9 bit header that
	//const unsigned short INVERSE_PART_HEADER_4 = 0b0000101101011011; //this is the 9 bit header that 
	const unsigned short SHORT_FLAG[] =         {0b0000000000000001,0b0000000000000010,0b0000000000000100,0b0000000000001000,0b0000000000010000,0b0000000000100000,0b0000000001000000,0b0000000010000000,0b0000000100000000,
	0b0000001000000000,0b0000010000000000,0b0000100000000000,0b0001000000000000,0b0010000000000000,0b0100000000000000,0b1000000000000000};
	
	const int HEARTBEAT_INTERVAL = 280; //how many cycles between heartbeats;
	
	const char COMMAND_EXIT = 0b110101011;
	
	const char SQAN_HEADER[] = {0b01100110,0b10011001};
	const int SQAN_HEADER_LEN = (int)(sizeof(SQAN_HEADER)/sizeof(SQAN_HEADER[0])) ;
	
	const char EXIT_HEADER[] = {0b10010110,0b01101001,0b00100010,0b01000100};
	const int EXIT_HEADER_LEN = (int)(sizeof(EXIT_HEADER)/sizeof(EXIT_HEADER[0])) ;	
	
	const char NO_DATA_HEARTBEAT[] = {0b00000001,0b00000010,0b00000011,0b00000100};
	const int NO_DATA_HEARTBEAT_LEN = (int)(sizeof(NO_DATA_HEARTBEAT)/sizeof(NO_DATA_HEARTBEAT[0])) ;	
	
	char SEND_NOTIFICATION[] = {0b00000001,0b00000010,0b00000011,0b00000100,0b00000000};
	const int SEND_NOTIFICATION_LEN = (int)(sizeof(SEND_NOTIFICATION)/sizeof(SEND_NOTIFICATION[0])) ;	
	
	//const unsigned char LOWER_BITS_MASK = 0b00001111;
	int16_t SIGNAL_THRESHOLD = 100;
	//const int16_t TRANSMIT_SIGNAL_VALUE = 2000; //TODO AD9361 bus-width is 12-bit so maybe shift left by 4?
	const bool AMPLITUDE_USE_I = true; //true == amplitude is I, false == amplitude is Q
	const int16_t PERCENT_LAST = 5; //percent of last amplitude to consider as threshold
	const int16_t TRANSMIT_SIGNAL_POS_I = 30000;
	const int16_t TRANSMIT_SIGNAL_NEG_I = -30000;
	const int16_t TRANSMIT_SIGNAL_POS_Q = 30000;
	const int16_t TRANSMIT_SIGNAL_NEG_Q = -30000;
	const int MAX_BYTES_PER_LINE = 250;
	const float DEFAULT_FREQ = 800; //in MHz
	const float DEFAULT_SAMPLE_RATE = 4.5; //FIXME switch back to 4.5
	const unsigned char MOST_SIG_BIT = 0b10000000;
	const unsigned char LEAST_SIG_BIT = 0b00000001;
	unsigned char hexin[1024];
	int TIMES_TO_SEND_MESSAGE = 5; //trigger sending a packet more than once
	char dataout[512]; //the received bytes to report back to the Android
	int dataoutIndex = 0;
	const int MAX_DATA_IN = 2048;
	unsigned char bytein[MAX_DATA_IN]; //the data received from OTA
	const int SIZE_OF_DATAOUT = (int)(sizeof(dataout)/sizeof(dataout[0]));
	int bitTimerCount = 0;
	int bitIndex = 0;
	int bitSensor = 0;
	bool byteTiming = false;
	int cyclesToHeartbeat = HEARTBEAT_INTERVAL;
	int timingInterval = 20;
	//const int BYTES_AFTER_HEADER = 1; //not tested - for sending multiple bytes per header
	//int bytesAfterHeaderCounter = 0;
	unsigned short tempHeader = (unsigned short)0;
	unsigned short tempTiming = (unsigned short)0;
	bool isReadingHeader = true;
	bool isSignalInverted = false;
	bool timingFound = false;
	int bytesSentThisLine = 0;
	//struct timespec start, end; //used to measure elapsed time
	bool testDataSent = false;
	bool superVerbose = false;
	bool screenForHeader = true; //true == only dat sequences that start with the SqAN header will be reported
	bool binIn = false;
	bool inNonBlock = true;
	bool binOut = false;
	
	//int emptyBuffersSent = 0;
	//const int MAX_EMPTY_BUFFERS_SENT = 4;

	ssize_t nbytes_rx, nbytes_tx;
	char *p_tx_dat, *p_rx_dat, *p_tx_end, *p_rx_end;
	ptrdiff_t p_tx_inc,p_rx_inc;

	float txf = 0.0; //assigned tX freq (if any)
	float txb = 0.0; //assigned tX bandwidth(if any)
	float txsr = 0.0; //assigned tX sample rate (if any)
	float txgain = 10.0; //assigned tX gain (if any)
	char cmd[40] = "";
	char cdcmd[40] = "";
	char chgain[40] = "";
	char gaintype1[40] = "";
	char gaintype2[40] = "";
	float rxf = 0.0; //assigned rX freq (if any)
	float rxb = 0.0; //assigned rX bandwidth (if any)
	float rxsr = 0.0; //assigned rX sample rate (if any)

	
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
			} else if (strcmp("-rxbandwidth",argv[j]) == 0) {
				pt = 4;
			} else if (strcmp("-rxsrate",argv[j]) == 0) {
				pt = 5;
			} else if (strcmp("-txbandwidth",argv[j]) == 0) {
				pt = 6;
			} else if (strcmp("-txsrate",argv[j]) == 0) {
				pt = 7;
			} else if (strcmp("-txgain",argv[j]) == 0) {
				pt = 8;
			} else if (strcmp("-multiSample",argv[j]) == 0) {
				pt = 9;
			} else if (strcmp("-messageRepeat",argv[j]) == 0) {
				pt = 10;
			} else if (strcmp("-timingInterval",argv[j]) == 0) {
				pt = 11;
			} else if (strcmp("-binI",argv[j]) == 0) {
				binIn = true;
				inNonBlock = true;
			} else if (strcmp("-binO",argv[j]) == 0) {
				binOut = true;
				verbose = false;
			} else if (strcmp("-block",argv[j]) == 0) {
				inNonBlock = false;
			} else if (strcmp("-useTiming",argv[j]) == 0) {
				byteTiming = true;
			} else if (strcmp("-minComms",argv[j]) == 0) {
				printf("m Starting in min verbosity mode\n");
				verbose = false;
			} else if (strcmp("-verbose",argv[j]) == 0) {
				verbose = true;
			} else if (strcmp("-noHeader",argv[j]) == 0) {
				screenForHeader = false;
			} else if (strcmp("-superVerbose",argv[j]) == 0) {
				printf("m Starting in super verbosity mode\n");
				superVerbose = true;
			} else if (strcmp("-listen",argv[j]) == 0) {
				printf("m Starting in listen only mode\n");
				listenOnlyMode = true;
			} else if (strcmp("-test",argv[j]) == 0) {
				printf("m Running test traffic. SqANDR will wait for 5 cycles then send test traffic.\n");
				diagnostics = true;
			} else if (strcmp("-test01",argv[j]) == 0) {
				printf("m Running test traffic with just a repeated binary pattern. SqANDR will wait for 5 cycles then send test traffic.\n");
				diagnostics = true;
				binaryTestPattern = true;
			} else if ((strcmp("help",argv[j]) == 0) || (strcmp("-help",argv[j])==0)) {
				printf("SqANDR - the Squad Area Network Defined Radio app. This is the PlutoSDR app that serves as a companion to the SqAN Android app (https://github.com/sofwerx/sqan) and is intended to provide a link from the PlutoSDR to the Android device. SqANDR is intended to be called from within the SqAN app on Android but there are some basic capabilities included for manual diagnostics.\n\n");
				printf("Help, valid commands are:\n");
				printf(" -rx [freq in MHz] = sets Rx freq\n");
				printf(" -tx [freq in MHz] = sets Rx freq\n");
				//TODO commented out as may be deprectated: printf(" -threshold [signal threshold] = sets the minimum \"a\" level to be considered a signal\n");
				printf(" -rxbandwidth [freq in MHz] = sets the rx bandwidth\n");
				printf(" -rxsrate [rate in MegaSamples/Sec] = sets the rx sample rate\n");
				printf(" -txbandwidth [freq in MHz] = sets the tx bandwidth\n");
				printf(" -txsrate [rate in MegaSamples/Sec] = sets the tx sample rate\n");
				printf(" -txgain [absolute value of gain in dB] = sets the tx gain. Note this shall be input as a positive number decibel and will then be converted to a negative decibel.\n");
				printf(" -multiSample [samples per bit as integer] = sets the number of samples sent per bit. Currently governed to 3 or 5. Must be an odd number between 1 and 15.\n");
				printf(" -minComms = sets least verbose mode\n");
				printf(" -test = runs in diagnostic mode\n");
				printf(" -test01 = runs in diagnostic mode with a simple repeated pattern\n");
				printf(" -listen = runs in a continous listen only mode\n");
				printf(" -superVerbose = povides detailed I & Q values\n");
				printf(" -noHeader = reports all data, not just samples that include the SqAN header\n");
				printf(" -useTiming = checks for headers on predefined intervals after SQaN header is found\n");
				printf(" -timingInterval = Designates the number of headers to skip when using -useTiming flag. Default: 20\n");
				printf(" -messageRepeat = Designates number of times to repeat packet. Default: 5\n");
				printf(" -binI [NOT YET SUPPORTED] = switches SqANDR to binary input across the USB connection; -nonBlock is also invoked automatically\n");
				printf(" -binO = switches SqANDR to binary output across the USB connection; -minComms is also invoked automatically\n");
				printf(" -block = switches input to blocking mode\n");
				printf(" -verbose = force verbose mode (usually used to check -binO messages)\n");
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
							
						case 4:
							rxb = val;
							break;
							
						case 5:
							rxsr = val;
							break;
							
						case 6:
							txb = val;
							break;
							
						case 7:
							txsr = val;
							break;
							
						case 8:
							txgain = val;
							break;
						case 10:
							TIMES_TO_SEND_MESSAGE = val;
							break;
						case 11:
							timingInterval = val;
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

	if (!binIn)
		signal(SIGINT, handle_sig); // Listen to ctrl+c and ASSERT when not in binary mode

	// RX stream config
	if (rxb > 0.1) {
		if (verbose)
			printf("m Assigning RX bandwidth = %f MHz\n", rxb);
		rxcfg.bw_hz = MHZ(rxb);
	} else
		rxcfg.bw_hz = MHZ(18);   // 6 MHz rf bandwidth
	if (rxsr > 0.1) {
		if (verbose)
			printf("m Assigning RX Sample Rate = %f MHz\n", rxsr);
		rxcfg.fs_hz = MHZ(rxsr);
	} else
		//rxcfg.fs_hz = MHZ(30);   // 7.5 MS/s rx sample rate
		rxcfg.fs_hz = MHZ(DEFAULT_SAMPLE_RATE);
	if (rxf > 0.1) {
		if (verbose)
			printf("m Assigning RX frequency = %f MHz\n", rxf);
		rxcfg.lo_hz = MHZ(rxf);
	} else
		rxcfg.lo_hz = MHZ(DEFAULT_FREQ); //Rx freq
	rxcfg.rfport = "A_BALANCED"; // port A (select for rf freq.)

	// TX stream config
	
	if (txb > 0.1) {
		if (verbose)
			printf("m Assigning TX bandwidth = %f MHz\n", txb);
		txcfg.bw_hz = MHZ(txb);
	} else
		txcfg.bw_hz = MHZ(18);   // 6 MHz rf bandwidth
	if (txsr > 0.1) {
		if (verbose)
			printf("m Assigning TX Sample Rate = %f MHz\n", txsr);
		txcfg.fs_hz = MHZ(txsr);
	} else
		//txcfg.fs_hz = MHZ(30);   // 7.5 MS/s rx sample rate
		txcfg.fs_hz = MHZ(DEFAULT_SAMPLE_RATE);
	if (txgain != 10) {
		strcat(cdcmd, "cd /sys/bus/iio/devices/iio:device1/");
		system(cdcmd);
		if (verbose)
			printf("m Assigning TX gain = -%.0f dB\n", txgain);
		txgain = abs(txgain);
		sprintf(chgain, "-%.0f", txgain);
		strcat(cmd, "echo ");
		strcat(cmd,	chgain);
		strcat(cmd, " >  out_voltage0_hardwaregain");
		system(cmd);
		//cmd[40] = "";
		//sprintf(chgain, "-%.0f", txgain);
		//strcat(cmd, "echo ");
		//strcat(cmd,	chgain);
		//strcat(cmd, " >  out_voltage1_hardwaregain");
		//system(cmd);
	} else {
		if (verbose)
			printf("m TX gain = -10 dB\n");
	}
	if (txf > 0.1) {
		if (verbose)
			printf("mAssigning TX frequency = %f MHz\n", txf);
		txcfg.lo_hz = MHZ(txf);
	} else
		txcfg.lo_hz = MHZ(DEFAULT_FREQ); // Tx freq
	txcfg.rfport = "A"; // port A (select for rf freq.)

		strcat(gaintype1, "echo hybrid > in_voltage0_gain_control_mode");
		system(gaintype1);
		strcat(gaintype2, "echo hybrid > in_voltage1_gain_control_mode");
		system(gaintype2);
		
	if (verbose)
		printf("d Acquiring IIO context\n");
	ASSERT((ctx = iio_create_default_context()) && "No context");
	ASSERT(iio_context_get_devices_count(ctx) > 0 && "No devices");
	//if (txgain != 0)
	//	set_gain(txgain);

	if (verbose)
		printf("d Acquiring AD9361 streaming devices\n");
	ASSERT(get_ad9361_stream_dev(ctx, TX, &tx) && "No tx dev found");
	ASSERT(get_ad9361_stream_dev(ctx, RX, &rx) && "No rx dev found");

	if (verbose)
		printf("d Configuring AD9361 for streaming\n");
	ASSERT(cfg_ad9361_streaming_ch(ctx, &rxcfg, RX, 0) && "RX port 0 not found");
	ASSERT(cfg_ad9361_streaming_ch(ctx, &txcfg, TX, 0) && "TX port 0 not found");

	if (verbose)
		printf("d Initializing AD9361 IIO streaming channels\n");
	ASSERT(get_ad9361_stream_ch(ctx, RX, rx, 0, &rx0_i) && "RX chan i not found");
	ASSERT(get_ad9361_stream_ch(ctx, RX, rx, 1, &rx0_q) && "RX chan q not found");
	ASSERT(get_ad9361_stream_ch(ctx, TX, tx, 0, &tx0_i) && "TX chan i not found");
	ASSERT(get_ad9361_stream_ch(ctx, TX, tx, 1, &tx0_q) && "TX chan q not found");

	if (verbose)
		printf("d Setting channel gain\n");

	//if (iio_channel_attr_write_longlong(iio_device_find_channel(tx, "voltage0", true), "hardwaregain", txgain) < 0)
	//	printf("d WARNING: Unable to set tx channel gain\n");
	//if (iio_channel_attr_write_longlong(iio_device_find_channel(rx, "voltage0", true), "hardwaregain", RX_GAIN) < 0)
	//	printf("d WARNING: Unable to set rx channel gain\n");

	if (verbose)
		printf("d Enabling IIO streaming channels\n");
	iio_channel_enable(rx0_i);
	iio_channel_enable(rx0_q);
	iio_channel_enable(tx0_i);
	iio_channel_enable(tx0_q);

	if (verbose)
		printf("d* Creating non-cyclic IIO buffers with 1 MiS\n");
	rxbuf = iio_device_create_buffer(rx, 20*1200, false);
	if (!rxbuf) {
		perror("m Could not create RX buffer");
		shutdown();
	}
	iio_buffer_set_blocking_mode(rxbuf,true);

	txbuf = iio_device_create_buffer(tx, 20*1200, false);
	if (!txbuf) {
		perror("m Could not create TX buffer");
		shutdown();
	}
	iio_buffer_set_blocking_mode(txbuf,true);

	p_tx_inc = iio_buffer_step(txbuf);
	p_tx_end = iio_buffer_end(txbuf);
	for (p_tx_dat = (char *)iio_buffer_first(txbuf, tx0_i); p_tx_dat < p_tx_end; p_tx_dat += p_tx_inc) { //load the output buffer with empty data
		((int16_t*)p_tx_dat)[0] = (0); // Real (I)
		((int16_t*)p_tx_dat)[1] = (0); // Imag (Q)
	}

	int16_t amplitude = 0; //amplitude

	int cnt = 0;
	int startrx = true;
	int bufferCycleCount = 0;
	bool emptyBuffer = true; //does the Tx buffer currently have empty data
	if (verbose)
		printf("m Starting IO streaming (press CTRL+C to cancel)\n");

	unsigned char tempByte = (unsigned char)0; //using char since C doesn't have a byte type

	int testCounter = 0;

	/**
	 * Clear out the receive buffer
	 */
	if (verbose && screenForHeader)
		printf("Waiting for SqAN header....\n");

	//moving all assignments outside the while to reduce allocation time in the loop
	//const int MAX_SAMPLES_IN_A_ROW = multisampleFactor * 17; //not used
	//const int MAX_SAMPLES_IN_A_ROW = 19; //not used
	//bool multiline = false;
	bool countReset = false;
	int bitsSearchedForHeader = 0;
	int samplesWithNoSignal = 0;
	int noiseBits = 0;
	//int totalSamples = 0;
	//int aNoisePosU = 0; //used to record the noise range
	//int aNoisePosL = 0;
	//int aNoiseNegU = 0;
	//int aNoiseNegL = 0;
	int16_t amplitudeLast = 0;
	//int amplitudeLastCorrected = 0;
	int tempCounter = 0;
	//int helperShowIQindex = 0; //only show the I and Q readings for the first 200 samples
	bool sqanHeaderFound = false;
	bool ignoreHeaders = false;
	int headerCounter = 0;
	int sqanHeaderMatchIndex = 0;
	bool waitingOnHeader = true;
	int samplesToShow = 0;
	int bytesInput = 0;
	int  bit = 0;
	bool activityThisCycle = false; //did something happen (Rx or Tx) this cycle

	int iLast = 0;
	int qLast = 0;

	int count = 0; // a counter just used in the binary test pattern
	int bestMatch = 0; //a counter used to keep track of the best match returned during a test pattern
	
	if (inNonBlock) {
		fcntl(0, F_SETFL, O_NONBLOCK); //switch stdin to non-blocking
		//setvbuf(stdin, NULL, _IONBF, 0); //sets stdin to no buffer mode
	}
	if (binIn) {
		freopen("", "rb", stdin); //open stdin in "read binary" mode
	}
	
	int countToClose = 2; //FIXME testing
	
	while (!stop) {
		startTime = clock();
		
		//multiline = false;
		countReset = false;
		bitsSearchedForHeader = 0;
		samplesWithNoSignal = 0;
		noiseBits = 0;
		tempCounter = 0;
		sqanHeaderFound = false;
		headerCounter = 0;
		ignoreHeaders = false;
		sqanHeaderMatchIndex = 0;
		waitingOnHeader = true;
		samplesToShow = 0;
		bytesInput = 0;
		//int iLast = 0;
		//int qLast = 0;
		
		bufferCycleCount = 0;
		//while (bufferCycleCount < 1) {
			// Refill RX buffer
			nbytes_rx = iio_buffer_refill(rxbuf);
			//stopTime = clock();
			//if (verbose)
			//	printf("Refill time: %.3f ms, size %d b\n",(double)((double)(stopTime-startTime) / CLOCKS_PER_SEC) * 1000,nbytes_rx);
			if (nbytes_rx < 0) { printf("m Error refilling buf %d\n",(int) nbytes_rx); shutdown(); }
			//if (verbose)
			//	printf("d Refill Rx buffer provided %db\n",(int)nbytes_rx);

			p_rx_inc = iio_buffer_step(rxbuf);
			p_rx_end = iio_buffer_end(rxbuf);

			/**
			 * READING the Rx buffer
			 */
			bytesSentThisLine = 0;
			dataoutIndex = 0;
			for (p_rx_dat = (char *)iio_buffer_first(rxbuf, rx0_i); p_rx_dat < p_rx_end; p_rx_dat += p_rx_inc) {
				//totalSamples++;
				const int16_t i = ((int16_t*)p_rx_dat)[0] << 4; // Real (I)
				const int16_t q = ((int16_t*)p_rx_dat)[1] << 4; // Imag (Q)
				if (AMPLITUDE_USE_I)
					amplitude = i;
				else
					amplitude = q;

				//if ((amplitude < SIGNAL_THRESHOLD) & (amplitude > -SIGNAL_THRESHOLD)) {
					//drop due to below threshold
				//} else {
					if (isReadingHeader) {
						bool headerComplete = false;
						bitsSearchedForHeader++;
						tempHeader = tempHeader << 1; //move bits over to make room for new bit
						
						if (amplitude >= amplitudeLast) {
							tempHeader = tempHeader | LEAST_SIG_BIT_HEADER;
							bit = 1;
						} else {
							bit = 0;
						}
						tempHeader = tempHeader & HEADER_MASK;

						if (tempHeader == HEADER/* || tempHeader == PART_HEADER || tempHeader == PART_HEADER_2 || tempHeader == PART_HEADER_3*/) {
							headerComplete = true;
							isSignalInverted = false;
						} else if (tempHeader == INVERSE_HEADER/* || tempHeader == INVERSE_PART_HEADER || tempHeader == INVERSE_PART_HEADER_2*/) {
							headerComplete = true;
							isSignalInverted = true;
						}

						if (headerComplete) {
							tempHeader = (unsigned char)0;
							isReadingHeader = false;
							bitIndex = 0;
							tempByte = (unsigned char)0;
							bitsSearchedForHeader = 0;
						}
					} else {
						bitIndex++;
						tempByte = tempByte << 1;
						if (isSignalInverted) {
							if (amplitude <= amplitudeLast){
								tempByte = tempByte | LEAST_SIG_BIT;
								bit = 1;
							} else
								bit = 0;
						} else {
							if (amplitude >= amplitudeLast){
								tempByte = tempByte | LEAST_SIG_BIT;
								bit = 1;
							} else
								bit = 0;
						}
					
						//if (sqanHeaderFound && !binOut && verbose)
						//	printf("\tBit: %d, I: %d, Q: %d, A: %d\n", bit, i, q, amplitude);

						if (bitIndex > 7) {
							if ((dataoutIndex < SIZE_OF_DATAOUT) && !ignoreHeaders) {
								dataout[dataoutIndex] = tempByte;
								dataoutIndex++;
								bitIndex = 0;
							}
							if (!sqanHeaderFound) {
								if (SQAN_HEADER[sqanHeaderMatchIndex] == tempByte) {
									sqanHeaderMatchIndex++;
									if (sqanHeaderMatchIndex >= SQAN_HEADER_LEN) {
										sqanHeaderFound = true;
										bitIndex = 0;
											//FIXME testing ------------------------------------
											//if (!binOut && sqanHeaderFound)
											//	printf("\nSQAN packet: 6699");
											//FIXME testing ------------------------------------
									}
								} else
									sqanHeaderMatchIndex = 0;
							}
						
							//FIXME testing --------------------------
							//if (!binOut && sqanHeaderFound) {
							//	char *a = "0123456789abcdef"[tempByte >> 4];
							//	char *b = "0123456789abcdef"[tempByte & 0x0F];
							//	printf("HEX: %c%c\n",a,b);
							//}
							//FIXME testing --------------------------
							if (sqanHeaderFound && byteTiming) {
								ignoreHeaders = true;
							}
							if (ignoreHeaders) {
								if (bitIndex == 20) {
									if ((dataoutIndex < SIZE_OF_DATAOUT)) {
										dataout[dataoutIndex] = tempByte;
										dataoutIndex++;
									}
									bitIndex = 0;
									headerCounter++;
								}
								if (headerCounter == timingInterval) {
									headerCounter = 0;
									isReadingHeader = true;
									ignoreHeaders = false;
								}
							} else {
							isReadingHeader = true; //go back to reading the header
							}
						}
					}
				//}
				amplitudeLast = amplitude*PERCENT_LAST/100;
			}
			
			/**
			 * Output the recovered data
			 **/
			
			if ((dataoutIndex > 0) && (!screenForHeader || sqanHeaderFound)) { //only output if there's something to send
				activityThisCycle = true;
				if (binOut) {
					/**
					 * Output as pure binary
					 **/
					fwrite(dataout,1,dataoutIndex,stdout);
					fflush(stdout);
				} else {
					/**
					 * Output as hex text
					 **/			
					printf("+");
					for (int i=0;i<dataoutIndex;i++) {
						tempByte = dataout[i];
						char *a = "0123456789abcdef"[tempByte >> 4];
						char *b = "0123456789abcdef"[tempByte & 0x0F];
						printf("%c%c",a,b);
					}
					printf("\n");
				}
			} else {
				if (binOut) {
					/**
					 * Send a heartbeat to let SqAN know the SqANDR app is active
					 */
					if (cyclesToHeartbeat < 1) {
						fwrite(NO_DATA_HEARTBEAT,1,NO_DATA_HEARTBEAT_LEN,stdout);
						fflush(stdout);
						cyclesToHeartbeat = HEARTBEAT_INTERVAL;
					} else
						cyclesToHeartbeat--;
				}
			}				
		//}

		//bufferCycleCount = 0;
		cnt = cnt + 1;
		startrx = false;
		
		/**
		 * READING serial input
		 */
		
		bytesInput = 0;
		if (binIn) {
			/**
			 * Non-blocking Binary stream input
			 */

			//int exitHeaderIndexMatched = 0;
			//char ch;
			
			//bytesInput = fread(bytein, 1, MAX_DATA_IN, stdin);
			bytesInput = read(STDIN_FILENO,bytein,MAX_DATA_IN);
			if (bytesInput < 0)
				bytesInput = 0;
			/*if (bytesInput == 0) {
				if (emptyBuffersSent != MAX_EMPTY_BUFFERS_SENT) {
					emptyBuffersSent++;
					bytein[0] = (char)0;
					bytesInput = 1;
				}
			} else
				emptyBuffersSent = 0;*/
			//while(read(0, &ch, 1) > 0)) { // 0 == STDIN_FILENO
			//while((read(0, &ch, 1) > 0) && (bytesInput < MAX_DATA_IN)) { // 0 == STDIN_FILENO
				/*bytein[bytesInput] = ch;
				if (bytesInput < EXIT_HEADER_LEN) { //check for exit command
					if (EXIT_HEADER[exitHeaderIndexMatched] == ch) {
						exitHeaderIndexMatched++;
						if (exitHeaderIndexMatched == EXIT_HEADER_LEN) {
							printf("m Shutdown command received\n");
							shutdown();
						}
					}
				}
				bytesInput++;
			}*/	
			
			/*if (bytesInput > 0) { //TODO temp for testing
				printf("m Received input (%db): ",bytesInput);
				char tempByte;
				for (int i=0;i<bytesInput;i++) {
					tempByte = bytein[i];
					char *a = "0123456789abcdef"[tempByte >> 4];
					char *b = "0123456789abcdef"[tempByte & 0x0F];
					printf("%c%c",a,b);
				}

				printf("\n");
			}*/
		} else {
			/**
			 * Blocking hex input
			 */
			/*if (diagnostics) {
				if (testCounter < 16) {
					if (testCounter == 3) {
						hexin[0] = '*';
						int i=1;
						int size = (int)(sizeof(TEST_CHARS)/sizeof(TEST_CHARS[0]));
						while (i<=size) {
							hexin[i]=TEST_CHARS[i-1];
							i++;
						}
						hexin[i] = '\n';
						i++;
						hexin[i] = '\0';
						printf("Diagnostic test is sending Test Message for transmission: %s\nd Enter key to continue\n",hexin);
						testDataSent = true;
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
			} else { */
				if (listenOnlyMode) {
					hexin[0] = '\n';
					hexin[1] = '\0';
				} else {
					if (verbose && !inNonBlock)
						printf("d Input:\n");
					if (fgets(hexin, 1024, stdin) == NULL)	
						hexin[0] = '\0';
				}
			//}
			int inputLength = strlen(hexin);
			if (inputLength > 0) {
				bytesInput = (inputLength-2)>>1;
			}
			//if (verbose && (bytesInput > 1))
			//	printf("d Input Length: %db\n",bytesInput);

			if (hexin[0] == 'e')
				break;
			if (bytesInput > 0) {
				activityThisCycle = true;
				int cl = 0;
				int hex = 1;
				unsigned char hexchar[2];
				if (hexin[0] == '*') {
					while ((cl<bytesInput) && (cl<MAX_DATA_IN)) {
						hexchar[0] = hexin[hex];
						hexchar[1] = hexin[hex + 1];
						bytein[cl] = hexToBin(hexchar);
						/*if (verbose && diagnostics && !binaryTestPattern) {
							printf("Current hex: %c%c = ",hexchar[0],hexchar[1]);

							unsigned char asBytes = hexToBin(hexchar);
							for (int i=0;i<8;i++) {
								if ((asBytes & MOST_SIG_BIT) == MOST_SIG_BIT)
									printf("1");
								else
									printf("0");
								asBytes = asBytes << 1;
							}

							printf(" = %d",(unsigned int)bytein[cl]);
							printf("\n");
						}*/
						cl++;
						hex += 2;
					}
				}
				hexin[0] = '\0';
			}
		}

		/**
		 * Sending the INPUT to the Tx Buffer
		 */
		//bytesAfterHeaderCounter = 0;
		p_tx_dat = (char *)iio_buffer_first(txbuf, tx0_i);
		p_tx_inc = iio_buffer_step(txbuf);
		p_tx_end = iio_buffer_end(txbuf);
		if (bytesInput > 0) { //ignore if there's nothing to send
			activityThisCycle = true;
			bufferCycleCount = 0;
			//if (verbose)
			//	printf("dAdding %ib to Tx buffer:\n",bytesInput);

			//Send leading "no signal" bytes
			//for (int i=0;i<510;i++) {
			for (int i=0;i<48;i++) { //current testing indicates that this needs to stay above 21
				if (p_tx_dat > p_tx_end) {
					if (verbose)
						printf("m Error - header was larger than remaining buffer size (this should not happen)");
					break;
				}
				//((int16_t*)p_tx_dat)[0] = 25000; // Real (I)
				//((int16_t*)p_tx_dat)[1] = 25000; // Imag (Q)
				((int16_t*)p_tx_dat)[0] = 0; // Real (I)
				((int16_t*)p_tx_dat)[1] = 0; // Imag (Q)
				p_tx_dat += p_tx_inc;
			}
			while (bufferCycleCount < TIMES_TO_SEND_MESSAGE) {
				for (int bytePayloadIndex=0;bytePayloadIndex<bytesInput;bytePayloadIndex++) { //send the data
					if (verbose)
						printf("d Sending byte data with 12 bit header to the buffer\n");

					for (int i=11;i>=0;i--) { //send header 12 bits
						if (p_tx_dat > p_tx_end) {
							if (verbose)
								printf("m Error - header was larger than remaining buffer size (this should not happen)");
							break;
						}
						if ((HEADER & SHORT_FLAG[i]) == SHORT_FLAG[i]) {
							((int16_t*)p_tx_dat)[0] = TRANSMIT_SIGNAL_POS_I; // Real (I)
							((int16_t*)p_tx_dat)[1] = TRANSMIT_SIGNAL_POS_Q; // Imag (Q)
							//if (verbose)
							//	printf("1");
						} else {
							((int16_t*)p_tx_dat)[0] = TRANSMIT_SIGNAL_NEG_I; // Real (I)
							((int16_t*)p_tx_dat)[1] = TRANSMIT_SIGNAL_NEG_Q; // Imag (Q)
							//if (verbose)
							//	printf("0");
						}
						//bytesAfterHeaderCounter++;
						p_tx_dat += p_tx_inc;
					}
					//} else {
						//if (verbose)
						//	printf(" ");

						//send actual byte
					for (int bitPlace=7;bitPlace>=0;bitPlace--) {
						if (p_tx_dat > p_tx_end) {
							if (verbose)
								printf("m Error - byte data was larger than remaining buffer size (this should not happen)");
							break;
						}

						if ((bytein[bytePayloadIndex] & BYTE_FLAG[bitPlace]) == BYTE_FLAG[bitPlace]) {
							((int16_t*)p_tx_dat)[0] = TRANSMIT_SIGNAL_POS_I; // Real (I)
							((int16_t*)p_tx_dat)[1] = TRANSMIT_SIGNAL_POS_Q; // Imag (Q)
							//if (verbose)
							//	printf("1");
						} else {
							((int16_t*)p_tx_dat)[0] = TRANSMIT_SIGNAL_NEG_I; // Real (I)
							((int16_t*)p_tx_dat)[1] = TRANSMIT_SIGNAL_NEG_Q; // Imag (Q)
							//if (verbose)
							//	printf("0");
						}
						p_tx_dat += p_tx_inc;
					}
				}
				
				if (binOut) {
					if (bytesInput < 255)
						SEND_NOTIFICATION[SEND_NOTIFICATION_LEN-1] = (char)bytesInput;
					else
						SEND_NOTIFICATION[SEND_NOTIFICATION_LEN-1] = (char)255;
					fwrite(SEND_NOTIFICATION,1,SEND_NOTIFICATION_LEN,stdout);
					fflush(stdout);
				}

				for (int i=0;i<48;i++) { //current testing indicates that this needs to stay above 21
					if (p_tx_dat > p_tx_end) {
						if (verbose)
							printf("m Error - header was larger than remaining buffer size (this should not happen)");
						break;
					}
					//((int16_t*)p_tx_dat)[0] = 25000; // Real (I)
					//((int16_t*)p_tx_dat)[1] = 25000; // Imag (Q)
					((int16_t*)p_tx_dat)[0] = 0; // Real (I)
					((int16_t*)p_tx_dat)[1] = 0; // Imag (Q)
					p_tx_dat += p_tx_inc;	
				}
				
				bufferCycleCount++;
			}

			while (p_tx_dat < p_tx_end) {
				//((int16_t*)p_tx_dat)[0] = -25000; // Real (I)
				//((int16_t*)p_tx_dat)[1] = -25000; // Imag (Q)
				((int16_t*)p_tx_dat)[0] = 0; // Real (I)
				((int16_t*)p_tx_dat)[1] = 0; // Imag (Q)
				p_tx_dat += p_tx_inc;
			}
			emptyBuffer = false;

			//FIXME testing
			//countToClose--;
			//FIXME testing
			// Schedule TX buffer
			nbytes_tx = iio_buffer_push(txbuf);
			if (nbytes_tx < 0) { printf("m Error pushing buf %d\n", (int) nbytes_tx); shutdown(); }
		} else {
			if (!emptyBuffer) { //only change the Tx buffer if it's not already filled with empty data
				//FIXME testing
				//countToClose--;
				//FIXME testing
				
				while (p_tx_dat < p_tx_end) {
					((int16_t*)p_tx_dat)[0] = 0; // Real (I)
					((int16_t*)p_tx_dat)[1] = 0; // Imag (Q)
					p_tx_dat += p_tx_inc;
				}
				emptyBuffer = true;
			}
		}
		

		
		//FIXME for texting
		//if (countToClose == 0) {
		//	shutdown();
		//}
		//FIXME for texting
		
		if (verbose && activityThisCycle) {
			stopTime = clock();
			printf("Cycle time: %.3f ms, rx pointer end %p, tx pointer end %p\n",(double)((double)(stopTime-startTime) / CLOCKS_PER_SEC) * 1000,p_rx_end,p_tx_end);
			activityThisCycle = false;
		}
	}
 	shutdown();
	return 0;
}