/*
 * Adapted in part from libiio - AD9361 IIO streaming example by Michael Feilen <feilen_at_iabg.de>
 *
 **/

#include <stdbool.h>
#include <stdint.h>
#include <string.h>
#include <getopt.h>
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <math.h>
#include <time.h>
#include <unistd.h>
#include <fcntl.h>
#include <ad9361.h>

#ifdef _WIN32
#include <windows.h>
#else
#include <unistd.h>
#endif

#ifdef _MSC_BUILD
#define snprintf sprintf_s
#endif

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
#define FIR_BUF_SIZE 8192
	
static int16_t fir_128_4[] = {
	-15,-27,-23,-6,17,33,31,9,-23,-47,-45,-13,34,69,67,21,-49,-102,-99,-32,69,146,143,48,-96,-204,-200,-69,129,278,275,97,-170,
	-372,-371,-135,222,494,497,187,-288,-654,-665,-258,376,875,902,363,-500,-1201,-1265,-530,699,1748,1906,845,-1089,-2922,-3424,
	-1697,2326,7714,12821,15921,15921,12821,7714,2326,-1697,-3424,-2922,-1089,845,1906,1748,699,-530,-1265,-1201,-500,363,902,875,
	376,-258,-665,-654,-288,187,497,494,222,-135,-371,-372,-170,97,275,278,129,-69,-200,-204,-96,48,143,146,69,-32,-99,-102,-49,21,
	67,69,34,-13,-45,-47,-23,9,31,33,17,-6,-23,-27,-15};

static int16_t fir_128_2[] = {
	-0,0,1,-0,-2,0,3,-0,-5,0,8,-0,-11,0,17,-0,-24,0,33,-0,-45,0,61,-0,-80,0,104,-0,-134,0,169,-0,
	-213,0,264,-0,-327,0,401,-0,-489,0,595,-0,-724,0,880,-0,-1075,0,1323,-0,-1652,0,2114,-0,-2819,0,4056,-0,-6883,0,20837,32767,
	20837,0,-6883,-0,4056,0,-2819,-0,2114,0,-1652,-0,1323,0,-1075,-0,880,0,-724,-0,595,0,-489,-0,401,0,-327,-0,264,0,-213,-0,
	169,0,-134,-0,104,0,-80,-0,61,0,-45,-0,33,0,-24,-0,17,0,-11,-0,8,0,-5,-0,3,0,-2,-0,1,0,-0, 0 };

static int16_t fir_96_2[] = {
	-4,0,8,-0,-14,0,23,-0,-36,0,52,-0,-75,0,104,-0,-140,0,186,-0,-243,0,314,-0,-400,0,505,-0,-634,0,793,-0,
	-993,0,1247,-0,-1585,0,2056,-0,-2773,0,4022,-0,-6862,0,20830,32767,20830,0,-6862,-0,4022,0,-2773,-0,2056,0,-1585,-0,1247,0,-993,-0,
	793,0,-634,-0,505,0,-400,-0,314,0,-243,-0,186,0,-140,-0,104,0,-75,-0,52,0,-36,-0,23,0,-14,-0,8,0,-4,0};

static int16_t fir_64_2[] = {
	-58,0,83,-0,-127,0,185,-0,-262,0,361,-0,-488,0,648,-0,-853,0,1117,-0,-1466,0,1954,-0,-2689,0,3960,-0,-6825,0,20818,32767,
	20818,0,-6825,-0,3960,0,-2689,-0,1954,0,-1466,-0,1117,0,-853,-0,648,0,-488,-0,361,0,-262,-0,185,0,-127,-0,83,0,-58,0};


	
	
/* RX is input, TX is output */
enum iodev { RX, TX };

/* common RX and TX streaming params */
struct stream_cfg {
	long long bw_hz; // Analog banwidth in Hz
	long long fs_hz; // Baseband sample rate in Hz
	long long lo_hz; // Local oscillator frequency in Hz
	const char* rfport; // Port name
};

bool firFilter = false;
	
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
static struct iio_buffer  *membuf = NULL;

static bool stop;

/* cleanup and exit */
static void shutdown()
{
	printf("d* Destroying buffers\n");
	if (rxbuf) { iio_buffer_destroy(rxbuf); }
	if (txbuf) { iio_buffer_destroy(txbuf); }
	if (membuf) { iio_buffer_destroy(membuf); }

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

int ad9361_set_trx_fir_enable(struct iio_device *dev, int enable)
{
	int ret = iio_device_attr_write_bool(dev,
					 "in_out_voltage_filter_fir_en", !!enable);
	if (ret < 0)
		ret = iio_channel_attr_write_bool(iio_device_find_channel(dev, "out", false),
					    "voltage_filter_fir_en", !!enable);
	return ret;
}

int ad9361_get_trx_fir_enable(struct iio_device *dev, int *enable)
{
	bool value;

	int ret = iio_device_attr_read_bool(dev, "in_out_voltage_filter_fir_en", &value);

	if (ret < 0)
		ret = iio_channel_attr_read_bool(iio_device_find_channel(dev, "out", false),
						 "voltage_filter_fir_en", &value);

	if (!ret)
		*enable	= value;

	return ret;
}

int ad9361_set_bb_rate(struct iio_device *dev, unsigned long rate)
{
	struct iio_channel *chan;
	long long current_rate;
	int dec;
	int taps;
	int ret;
	int i;
	int enable;
	int len = 0;
	int16_t *fir;
	char *buf;

	if (rate <= 20000000UL) {
		dec = 4;
		taps = 128;
		fir = fir_128_4;
	} else if (rate <= 40000000UL) {
		dec = 2;
		fir = fir_128_2;
		taps = 128;
	} else if (rate <= 53333333UL) {
		dec = 2;
		fir = fir_96_2;
		taps = 96;
	} else {
		dec = 2;
		fir = fir_64_2;
		taps = 64;
	}

	chan = iio_device_find_channel(dev, "voltage0", true);
	if (chan == NULL)
		return -ENODEV;
	
	ret = iio_channel_attr_read_longlong(chan, "sampling_frequency", &current_rate);
	if (ret < 0)
		return ret;
	
	ad9361_get_trx_fir_enable(dev, &enable);
	if (ret < 0)
		return ret;
	
	if (enable) {
		if (current_rate <= (25000000 / 12))
			iio_channel_attr_write_longlong(chan, "sampling_frequency", 3000000);

		ret = ad9361_set_trx_fir_enable(dev, false);
		if (ret < 0)
			return ret;
	}

	buf = malloc(FIR_BUF_SIZE);
	if (!buf)
		return -ENOMEM;

	len += snprintf(buf + len, FIR_BUF_SIZE - len, "RX 3 GAIN -6 DEC %d\n", dec);
	len += snprintf(buf + len, FIR_BUF_SIZE - len, "TX 3 GAIN 0 INT %d\n", dec);

	for (i = 0; i < taps; i++)
		len += snprintf(buf + len, FIR_BUF_SIZE - len, "%d,%d\n", fir[i], fir[i]);

	len += snprintf(buf + len, FIR_BUF_SIZE - len, "\n");

	ret = iio_device_attr_write_raw(dev, "filter_fir_config", buf, len);
	free (buf);

	if (ret < 0)
		return ret;

	if (rate <= (25000000 / 12))  {
		int dacrate, txrate, max;
		char readbuf[100];

		ret = iio_device_attr_read(dev, "tx_path_rates", readbuf, sizeof(readbuf));
		if (ret < 0)
			return ret;
		ret = sscanf(readbuf, "BBPLL:%*d DAC:%d T2:%*d T1:%*d TF:%*d TXSAMP:%d", &dacrate, &txrate);
		if (ret != 2)
			return -EFAULT;

		if (txrate == 0)
			return -EINVAL;

		max = (dacrate / txrate) * 16;
		if (max < taps)
			iio_channel_attr_write_longlong(chan, "sampling_frequency", 3000000);
		
		ret = ad9361_set_trx_fir_enable(dev, true);
		if (ret < 0)
			return ret;
		ret = iio_channel_attr_write_longlong(chan, "sampling_frequency", rate);
		if (ret < 0)
			return ret;
	} else {
		ret = iio_channel_attr_write_longlong(chan, "sampling_frequency", rate);
		if (ret < 0)
			return ret;
		ret = ad9361_set_trx_fir_enable(dev, true);
		if (ret < 0)
			return ret;
	}
	return 0;
}

/* simple configuration and streaming */
int main (int argc, char **argv){
	bool lean = false; //lean mode uses the fastest possible configuration flags to have the lowest overhead for data structures and processing
	bool verbose = true;
	bool listenOnlyMode = false; //continously listens and displays received traffic
	
	clock_t startTime, stopTime;

	const char BYTE_FLAG[] = {0b00000001,0b00000010,0b00000100,0b00001000,0b00010000,0b00100000,0b01000000,0b10000000};
	const unsigned short HEADER                = 0b0000101101010011; //this is the 12 bit header that signals coming data
	const unsigned short SHORT_HEADER          = 0b0000000101010011; //this is the 9 bit header that signals coming data
	const unsigned short HEADER_MASK           = 0b0000111111111111;
	const unsigned short SHORT_HEADER_MASK     = 0b0000000111111111;
	const unsigned short LEAST_SIG_BIT_HEADER  = 0b0000000000000001;
	const unsigned short INVERSE_HEADER        = 0b0000010010101100;
	const unsigned short INVERSE_SHORT_HEADER  = 0b0000000010101100;
	const unsigned short SHORT_FLAG[] =         {0b0000000000000001,0b0000000000000010,0b0000000000000100,0b0000000000001000,0b0000000000010000,0b0000000000100000,0b0000000001000000,0b0000000010000000,0b0000000100000000,
	0b0000001000000000,0b0000010000000000,0b0000100000000000,0b0001000000000000,0b0010000000000000,0b0100000000000000,0b1000000000000000};
	const char LESS_THAN_32  = 0b00011111; //used to mask to only the values below 32 as part of the invalid char escape mechanism
	const char BYTE_64 = 0b01000000; //used to promote/demote a byte by 64 as part of the invalid char escape mechanism
	const char BYTE_128 = 0b10000000; //used to promote/demote a byte by 64 as part of the invalid char escape mechanism
	const char CHAR_127 = 0b01111111; //used as part of thw invalid char escape mechanism
	const char CHAR_255 = 0b11111111; //used as part of thw invalid char escape mechanism
	
	const int TX_PADDING = 48;
	
	const int HEARTBEAT_INTERVAL = 2; //how many cycles between heartbeats;
	
	const char COMMAND_EXIT = 0b00010000;
	
	const char SQAN_HEADER[] = {0b01100110,0b10011001};
	const int SQAN_HEADER_LEN = (int)(sizeof(SQAN_HEADER)/sizeof(SQAN_HEADER[0])) ;
	
	const char NO_DATA_HEARTBEAT[] = {0b00000001,0b00000010,0b00000011,0b00000100};
	const int NO_DATA_HEARTBEAT_LEN = (int)(sizeof(NO_DATA_HEARTBEAT)/sizeof(NO_DATA_HEARTBEAT[0])) ;	
	
	char SEND_NOTIFICATION[] = {0b00000001,0b00000010,0b00000011,0b00000100,0b00000000};
	const int SEND_NOTIFICATION_LEN = (int)(sizeof(SEND_NOTIFICATION)/sizeof(SEND_NOTIFICATION[0])) ;	
	
	//const unsigned char LOWER_BITS_MASK = 0b00001111;
	int16_t SIGNAL_THRESHOLD = 100;
	//const int16_t TRANSMIT_SIGNAL_VALUE = 2000; //TODO AD9361 bus-width is 12-bit so maybe shift left by 4?
	const bool AMPLITUDE_USE_I = true; //true == amplitude is I, false == amplitude is Q
	int16_t PERCENT_LAST = 5; //percent of last amplitude to consider as threshold; default is 5
	const int16_t TRANSMIT_SIGNAL_POS_I = 30000;
	const int16_t TRANSMIT_SIGNAL_NEG_I = -30000;
	const int16_t TRANSMIT_SIGNAL_POS_Q = 30000;
	const int16_t TRANSMIT_SIGNAL_NEG_Q = -30000;
	const float DEFAULT_FREQ = 800; //in MHz
	const float DEFAULT_SAMPLE_RATE = 3; //FIXME switch back to 4.5
	const float DEFAULT_BANDWIDTH = 5; //in MHz
	const unsigned char LEAST_SIG_BIT = 0b00000001;
	unsigned char hexin[1024];
	int TIMES_TO_COPY_MESSAGE = 4;
	int rxSize = 100000;
	int txSize = 108000;
	char dataout[512]; //the received bytes to report back to the Android
	int dataoutIndex = 0;
	const int SIZE_OF_DATAOUT = (int)(sizeof(dataout)/sizeof(dataout[0]))-1;
	bool shortHeader = false;
	int bitIndex = 0;
	bool byteTiming = false;
	int cyclesToHeartbeat = 0;
	int timingInterval = 20;
	int headerLength = 11;
	int bufferSendCount = 0;
	//const int BYTES_AFTER_HEADER = 1; //not tested - for sending multiple bytes per header
	//int bytesAfterHeaderCounter = 0;
	unsigned short tempHeader = (unsigned short)0;
	bool isReadingHeader = true;
	bool isSignalInverted = false;
	int gainTypeVal = 3;
	bool superVerbose = false;
	bool screenForHeader = true; //true == only dat sequences that start with the SqAN header will be reported
	bool binIn = false;
	bool inNonBlock = true;
	bool binOut = false;
	bool firstCycle = true;
	bool onboardProcessing = true;
	bool bufLoopback = false;

	ssize_t nbytes_rx, nbytes_tx;
	char *p_tx_dat, *p_rx_dat, *p_tx_end, *p_rx_end;
	ptrdiff_t p_tx_inc,p_rx_inc;

	float txf = 0.0; //assigned tX freq (if any)
	float txb = 0.0; //assigned tX bandwidth(if any)
	float txsr = 0.0; //assigned tX sample rate (if any)
	float txgain = 0.0; //assigned tX gain (if any)
	char cmd1[40] = "";
	char cmd2[40] = "";
	char cdcmd[40] = "";
	char chgain[40] = "";
	char gaintype1[60] = "";
	char gaintype2[60] = "";
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
			} else if (strcmp("-rxtype",argv[j]) == 0) {
				pt = 9;
			} else if (strcmp("-messageRepeat",argv[j]) == 0) {
				pt = 10;
			} else if (strcmp("-timingInterval",argv[j]) == 0) {
				pt = 12;
			} else if (strcmp("-rxSize",argv[j]) == 0) {
				pt = 13;
			} else if (strcmp("-txSize",argv[j]) == 0) {
				pt = 14;
			} else if (strcmp("-perLast",argv[j]) == 0) {
				pt = 15;
			} else if (strcmp("-binI",argv[j]) == 0) {
				binIn = true;
				inNonBlock = true;
			} else if (strcmp("-lean",argv[j]) == 0) {
				binIn = true;
				binOut = true;
				inNonBlock = true;
				lean = true;
			} else if (strcmp("-binO",argv[j]) == 0) {
				binOut = true;
				verbose = false;
			} else if (strcmp("-block",argv[j]) == 0) {
				inNonBlock = false;
			} else if (strcmp("-bufferLoopback",argv[j]) == 0) {
				bufLoopback = true;
			} else if (strcmp("-fir",argv[j]) == 0) {
				firFilter = true;
			} else if (strcmp("-rawOut",argv[j]) == 0) {
				onboardProcessing = false;
			} else if (strcmp("-shortHeader",argv[j]) == 0) {
				shortHeader = true;
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
			} else if ((strcmp("help",argv[j]) == 0) || (strcmp("-help",argv[j])==0)) {
				printf("SqANDR - the Squad Area Network Defined Radio app. This is the PlutoSDR app that serves as a companion to the SqAN Android app (https://github.com/sofwerx/sqan) and is intended to provide a link from the PlutoSDR to the Android device. SqANDR is intended to be called from within the SqAN app on Android but there are some basic capabilities included for manual diagnostics.\n\n");
				printf("Help, valid commands are:\n");
				printf(" -rx [freq in MHz] = sets Rx freq\n");
				printf(" -tx [freq in MHz] = sets Rx freq\n");
				printf(" -rxbandwidth [freq in MHz] = sets the rx bandwidth\n");
				printf(" -rxsrate [rate in MegaSamples/Sec] = sets the rx sample rate\n");
				printf(" -txbandwidth [freq in MHz] = sets the tx bandwidth\n");
				printf(" -txsrate [rate in MegaSamples/Sec] = sets the tx sample rate\n");
				printf(" -txgain [absolute value of gain in dB] = sets the tx gain. Note this shall be input as a positive number decibel and will then be converted to a negative decibel.\n");
				printf(" -lean = uses the configuration that provides the leanest data overhead and highest data speed (switches to binary input and output, off-board processing, no byte headers, adjusts tx and rx buffer sizes\n");
				printf(" -multiSample (samples per bit as integer) [DEPRECATED] = sets the number of samples sent per bit. Currently governed to 3 or 5. Must be an odd number between 1 and 15.\n");
				printf(" -minComms = sets least verbose mode\n");
				printf(" -listen = runs in a continous listen only mode\n");
				printf(" -superVerbose = povides detailed I & Q values\n");
				printf(" -fir = runs with FIR filtering and possible lower sample rates\n");
				printf(" -noHeader = reports all data, not just samples that include the SqAN header\n");
				printf(" -useTiming = checks for headers on predefined intervals after SQaN header is found\n");
				printf(" -rawOut = Skips all onboard sample processing and sends buffer directly to master device. Must be used with -binO.\n");
				printf(" -rxtype = Sets rx gain type. 1 = Slow Attack, 2 = Hybrid, 3 = Fast Attack. Default: 2\n");
				printf(" -timingInterval [DEPRECATED] = Designates the number of headers to skip when using -useTiming flag. Default: 20\n");
				printf(" -messageRepeat = Designates number of times to repeat packet (if this makes the total to be transmitted larger than the buffer size, then the packet will only be repeated as often as fits in the buffer).\n");
				printf(" -binI = switches SqANDR to modified binary input across the USB connection; -nonBlock is also invoked automatically\n");
				printf(" -binO = switches SqANDR to modified binary output across the USB connection; -minComms is also invoked automatically\n");
				printf(" -block = switches input to blocking mode\n");
				printf(" -shortHeader = Use short byte header\n");
				printf(" -perLast = sets the percent of last amplitude (0 to 100) to consider when looking at a change in bit value\n");
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
						case 9:
							gainTypeVal = val;
							break;
						case 10:
							TIMES_TO_COPY_MESSAGE = val;
							break;
						case 12:
							timingInterval = val;
							break;
						case 13:
							rxSize = val;
							break;
						case 14:
							txSize = val;
							break;
						case 15:
							PERCENT_LAST = val;
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
	
	if (verbose)
		printf("d Acquiring IIO context\n");
	ASSERT((ctx = iio_create_default_context()) && "No context");
	ASSERT(iio_context_get_devices_count(ctx) > 0 && "No devices");
	
	if (!binIn)
		signal(SIGINT, handle_sig); // Listen to ctrl+c and ASSERT when not in binary mode

	// RX stream config
	if (rxb > 0.1) {
		if (verbose)
			printf("m Assigning RX bandwidth = %f MHz\n", rxb);
		rxcfg.bw_hz = MHZ(rxb);
	} else
		rxcfg.bw_hz = MHZ(DEFAULT_BANDWIDTH);   // 6 MHz rf bandwidth
	if (rxsr > 0.1) {
		if (verbose)
			printf("m Attempting to assigning RX Sample Rate = %f MHz\n", rxsr);
		if (firFilter) {
			unsigned long rate = MHZ(rxsr);
			ad9361_set_bb_rate(get_ad9361_phy(ctx), rate);
			rxcfg.fs_hz = MHZ(rxsr);
		} else {
		ad9361_set_trx_fir_enable(get_ad9361_phy(ctx), false);
		rxcfg.fs_hz = MHZ(rxsr);
		}
	} else {
		ad9361_set_trx_fir_enable(get_ad9361_phy(ctx), false);
		rxcfg.fs_hz = MHZ(DEFAULT_SAMPLE_RATE);
	}
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
			printf("m Attempting to assigning TX bandwidth = %f MHz\n", txb);
		txcfg.bw_hz = MHZ(txb);
	} else
		txcfg.bw_hz = MHZ(DEFAULT_BANDWIDTH);   // 6 MHz rf bandwidth
	if (txsr > 0.1) {
		if (verbose)
			printf("m Assigning TX Sample Rate = %f MHz\n", txsr);
		txcfg.fs_hz = MHZ(txsr);
	} else {   
		txcfg.fs_hz = MHZ(DEFAULT_SAMPLE_RATE);
	}
	strcat(cdcmd, "cd /sys/bus/iio/devices/iio:device1/"); //moved out of txgain conditional check as its needed for gain control modes as well
	system(cdcmd);
	if (txgain != 0) {
		txgain = abs(txgain);
		sprintf(chgain, "-%.0f", txgain);
		strcat(cmd1, "echo ");
		strcat(cmd1,	chgain);
		strcat(cmd1, " >  out_voltage0_hardwaregain");
		system(cmd1);
		sprintf(chgain, "-%.0f", txgain);
		strcat(cmd2, "echo ");
		strcat(cmd2,	chgain);
		strcat(cmd2, " >  out_voltage1_hardwaregain");
		system(cmd2);
	}
	if (verbose)
		printf("m Assigning TX gain = -%.0f dB\n", txgain); //moved out of condition as TX gain was being improperly reported when default tx gain value was changed but no user entered value was assigned

	if (txf > 0.1) {
		if (verbose)
			printf("mAssigning TX frequency = %f MHz\n", txf);
		txcfg.lo_hz = MHZ(txf);
	} else
		txcfg.lo_hz = MHZ(DEFAULT_FREQ); // Tx freq
	txcfg.rfport = "A"; // port A (select for rf freq.)
	
	if (gainTypeVal == 1) {
		strcat(gaintype1, "echo slow_attack > in_voltage0_gain_control_mode");
		strcat(gaintype2, "echo slow_attack > in_voltage1_gain_control_mode");
		system(gaintype1);
		system(gaintype2);
	} else if (gainTypeVal == 2) {
		strcat(gaintype1, "echo hybrid > in_voltage0_gain_control_mode");
		strcat(gaintype2, "echo hybrid > in_voltage1_gain_control_mode");
		system(gaintype1);
		system(gaintype2);
	} else if (gainTypeVal == 3) {
		strcat(gaintype1, "echo fast_attack > in_voltage0_gain_control_mode");
		strcat(gaintype2, "echo fast_attack > in_voltage1_gain_control_mode");
		system(gaintype1);
		system(gaintype2);
	}
		
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
		printf("d Enabling IIO streaming channels\n");
	iio_channel_enable(rx0_i);
	iio_channel_enable(rx0_q);
	iio_channel_enable(tx0_i);
	iio_channel_enable(tx0_q);

	//calculate the max data size based on the tx buffer and the format being sent
	int MAX_DATA_IN = 1024;
	if (lean) {
		txSize = (1024 << 3) + TX_PADDING;
		rxSize = 1024 << 4;
		MAX_DATA_IN = txSize >> 3; //bytes that fix in the tx buffer
		if (MAX_DATA_IN > 1024)
			MAX_DATA_IN = 1024;
	}
	unsigned char bytein[MAX_DATA_IN]; //the data received from serial connecton to be sent OTA
	unsigned char tempbytes[MAX_DATA_IN]; //used to temporarilty hold data while handling escaped characters

	if (verbose)
		printf("d* Creating non-cyclic IIO buffers with 1 MiS\n");
	rxbuf = iio_device_create_buffer(rx, rxSize, false);
	if (!rxbuf) {
		perror("m Could not create RX buffer");
		shutdown();
	}
	iio_buffer_set_blocking_mode(rxbuf,true);
	
	txbuf = iio_device_create_buffer(tx, txSize, false);
	if (!txbuf) {
		perror("m Could not create TX buffer");
		shutdown();
	}
	
	char* membuf[txSize];
	
	iio_buffer_set_blocking_mode(txbuf,true);
	iio_buffer_set_blocking_mode(rxbuf,true);

	p_tx_inc = iio_buffer_step(txbuf);
	p_tx_end = iio_buffer_end(txbuf);
	for (p_tx_dat = (char *)iio_buffer_first(txbuf, tx0_i); p_tx_dat < p_tx_end; p_tx_dat += p_tx_inc) { //load the output buffer with empty data
		((int16_t*)p_tx_dat)[0] = (0); // Real (I)
		((int16_t*)p_tx_dat)[1] = (0); // Imag (Q)
	}

	int16_t amplitude = 0; //amplitude
	int bufferCycleCount = 0;
	bool emptyBuffer = true; //does the Tx buffer currently have empty data
	
	
	if (verbose)
		printf("m Starting IO streaming (press CTRL+C to cancel)\n");

	unsigned char tempByte = (unsigned char)0; //using char since C doesn't have a byte type

	if (verbose && screenForHeader && onboardProcessing && !lean)
		printf("Waiting for SqAN header....\n");
	
	//moving all assignments outside the while to reduce allocation time in the loop
	int16_t amplitudeLast = 0;
	bool sqanHeaderFound = false;
	int sqanHeaderMatchIndex = 0;
	bool ignoreHeaders = false;
	int headerCounter = 0;
	int bytesInput = 0;
	int bit = 0;
	int a = 0;
	int index = 0;
	bool activityThisCycle = false; //did something happen (Rx or Tx) this cycle
	bool escNextChar = false; //used to show binIn if the next byte needs to be handled as an escaped value (used to bypass stdin problems with specific byte values
	int bytesSent = 0;
	char *p_tx_dat_safety;
	char byteToSend;

	if (shortHeader)
		headerLength = 7;
	
	if (inNonBlock) {
		fcntl(0, F_SETFL, O_NONBLOCK); //switch stdin to non-blocking
	} else {
		fcntl(0, F_SETFL, !O_NONBLOCK); //switch stdin to blocking
	}
	
	//if (!onboardProcessing)
	//	setvbuf(stdout, NULL, _IONBF, 0); //switch stdout to non-buffer mode
	
	while (!stop) {
		startTime = clock();
		sqanHeaderFound = false;
		sqanHeaderMatchIndex = 0;
		headerCounter = 0;
		ignoreHeaders = false;
		bytesInput = 0;
		escNextChar = false;
		bytesSent = 0;
		index = 0;
		
		activityThisCycle = false;
		fflush(stdout);
				
		// Refill RX buffer
		nbytes_rx = iio_buffer_refill(rxbuf);
		if (nbytes_rx < 0) { printf("m Error refilling buf %d\n",(int) nbytes_rx); shutdown(); }
		p_rx_inc = iio_buffer_step(rxbuf);
		p_rx_end = iio_buffer_end(rxbuf);
		
		/**
		 * READING the Rx buffer
		 */
		dataoutIndex = 0;
		if (onboardProcessing) {
			for (p_rx_dat = (char *)iio_buffer_first(rxbuf, rx0_i); p_rx_dat < p_rx_end; p_rx_dat += p_rx_inc) {
				if (AMPLITUDE_USE_I) {
					amplitude = ((int16_t*)p_rx_dat)[0] << 4; // Real (I)
				} else {
					amplitude = ((int16_t*)p_rx_dat)[1] << 4; // Imag (Q)
				}
				//fflush(stdout);

				if (isReadingHeader) {
					bool headerComplete = false;
					tempHeader = tempHeader << 1; //move bits over to make room for new bit
						
					if (amplitude >= amplitudeLast) {
						tempHeader = tempHeader | LEAST_SIG_BIT_HEADER;
						bit = 1;
					} else {
						bit = 0;
					}
		
					if (shortHeader)
						tempHeader = tempHeader & SHORT_HEADER_MASK;
					else
						tempHeader = tempHeader & HEADER_MASK;
	
					if ((tempHeader == HEADER) || ((tempHeader == SHORT_HEADER) && shortHeader)) {
						headerComplete = true;
						isSignalInverted = false;
					} else if ((tempHeader == INVERSE_HEADER) || ((tempHeader == INVERSE_SHORT_HEADER) && shortHeader)) {
						headerComplete = true;
						isSignalInverted = true;
					}
	
					if (headerComplete) {
						tempHeader = 0;
						isReadingHeader = false;
						bitIndex = 0;
						tempByte = 0;
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
	
					if (((bitIndex == 8) && !ignoreHeaders) || ((bitIndex == 20) && ignoreHeaders) || ((bitIndex == 17) && shortHeader && ignoreHeaders)) {
						if (dataoutIndex < SIZE_OF_DATAOUT) {
							//below 32 and the value 127 are problematic over binOut when Pluto fails to switch stdout to binary mode
							//the work around is to use 64 as a special byte. When 64 is read, then 64 is dropped and the 
							//next byte read is read as the XOR of the value
							
							if ((tempByte == CHAR_127) || (tempByte == BYTE_64) || ((tempByte & LESS_THAN_32) == tempByte)) {
								dataout[dataoutIndex] = BYTE_64;
								dataoutIndex++;
								tempByte = tempByte ^ CHAR_255;
							}
									
							dataout[dataoutIndex] = tempByte;
							dataoutIndex++;
							bitIndex = 0;
						}
						if (!sqanHeaderFound && screenForHeader) {
							if (SQAN_HEADER[sqanHeaderMatchIndex] == tempByte) {
								sqanHeaderMatchIndex++;
								if (sqanHeaderMatchIndex >= SQAN_HEADER_LEN) {
									sqanHeaderFound = true;
								}
							} else
								sqanHeaderMatchIndex = 0;
						}
							
						if (!binOut && sqanHeaderFound) {
							if (superVerbose) {
								char *a = "0123456789abcdef"[tempByte >> 4];
								char *b = "0123456789abcdef"[tempByte & 0x0F];
								printf("HEX: %c%c\n",a,b);
							}
						}
						if (sqanHeaderFound && byteTiming) {
							ignoreHeaders = true;
						}
						if (ignoreHeaders) {
							headerCounter++;
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
				
				if (superVerbose)
					printf("\tBit: %d, A: %d\n", bit, amplitude);
				amplitudeLast = amplitude*PERCENT_LAST/100;
			}
		} else if (!bufLoopback) {
			for (p_rx_dat = (char *)iio_buffer_first(rxbuf, rx0_i); p_rx_dat < p_rx_end; p_rx_dat += p_rx_inc) {
				for (int a=0;a<4;a++) {
					if (((char*)p_rx_dat)[a] == 0b00001010) //promote all 10s to 11s as Pluto corrects 10s to 13,10 to adjust for line break format
						((char*)p_rx_dat)[a] = 0b00001011;
					else if (((char*)p_rx_dat)[a] == 0b00001000) //demote all 8s to 7s
						((char*)p_rx_dat)[a] = 0b00000111;
					else if (((char*)p_rx_dat)[a] == 0b00001001) //promote all 9s to 11s
						((char*)p_rx_dat)[a] = 0b00001011;
					else if (((char*)p_rx_dat)[a] == CHAR_127) //demote all 127s to 126s
						((char*)p_rx_dat)[a] = 0b01111110;
				}
				
				//index++;
				//if (index == 256) {
				//	fwrite(p_rx_dat,1,4,stdout);
				//	fflush(stdout);
				//	index = 0;
				//}
			}
			fwrite(p_rx_dat,1,txSize,stdout);
			fflush(stdout);
		}
			
		/**
		 * Output the recovered data
		 **/
		if (((dataoutIndex > 0) && (!screenForHeader || sqanHeaderFound)) && onboardProcessing) { //only output if there's something to send
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
		}			
		
		
		/**
		 * READING serial input
		 */
		
		bytesInput = 0;
		if (binIn) {
			/**
			 * Non-blocking Binary stream input
			 */
			//if (fgets(bytein, 1024, stdin) == NULL)
			if (fgets(tempbytes, 1024, stdin) == NULL)
				bytesInput = 0;
			else
				bytesInput = strlen(tempbytes)-1; //drop the end byte that was sent to mark the end of the transmission			
			
			if (bytesInput < 0) {
				//printf("m Error reading bytes from stdin\n");
				bytesInput = 0;
			} else {
				if (bytesInput > 1) {
					activityThisCycle = true;
					if ((tempbytes[0] == COMMAND_EXIT) && (tempbytes[1] == COMMAND_EXIT)) {
						printf("m Shutdown commnd received...\n");
						stop = true;
						break;
					} else {
						for (int i=0;i<bytesInput;i++) {
							if (tempbytes[i] == BYTE_64) {// this signals that the next character is a special character so this character should be ignored
								escNextChar = true;
								continue;
							}
							if (escNextChar) {
								bytein[index] = tempbytes[i] ^ CHAR_255;
								escNextChar = false;
							} else
								bytein[index] = tempbytes[i];
							index++;
						}
						bytesInput = index;
					}
				}				
			}
		} else {
			/**
			 * Hex input
			 */
			if (listenOnlyMode) {
				hexin[0] = '\n';
				hexin[1] = '\0';
			} else {
				if (verbose && !inNonBlock)
					printf("d Input:\n");
				if (fgets(hexin, 1024, stdin) == NULL)	
					hexin[0] = '\0';
			}
			
			int inputLength = strlen(hexin);
			if (inputLength > 0) {
				bytesInput = (inputLength-2)>>1;
			}

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
		if (bytesInput > 0) { //only send if we have data to send
			p_tx_dat = (char *)iio_buffer_first(txbuf, tx0_i);
			p_tx_inc = iio_buffer_step(txbuf);
			p_tx_end = iio_buffer_end(txbuf);
			p_tx_dat_safety = p_tx_end - p_tx_inc; //this provides a safety margin before the end of the buffer so that the buffer isnt overwritter
			activityThisCycle = true;
			bufferCycleCount = 0;
			bufferSendCount = 0;
			for (int i=0;i<21;i++) { //current testing indicates that this needs to stay above 21
				((int16_t*)p_tx_dat)[0] = 0; // Real (I)
				((int16_t*)p_tx_dat)[1] = 0; // Imag (Q)
				p_tx_dat += p_tx_inc;
			}
			
			for (int times=0;times<TIMES_TO_COPY_MESSAGE;times++) {
				for (int i=0;i<bytesInput;i++) {	
					if (!lean) {
						//send the byte header
						for (int headIndex=headerLength;headIndex>=0;headIndex--) {
							if (p_tx_dat > p_tx_dat_safety)
								break;
							if (((HEADER & SHORT_FLAG[headIndex]) == SHORT_FLAG[headIndex]) && !shortHeader) {
								((int16_t*)p_tx_dat)[0] = TRANSMIT_SIGNAL_POS_I; // Real (I)
								((int16_t*)p_tx_dat)[1] = TRANSMIT_SIGNAL_POS_Q; // Imag (Q)
							} else {
								if (((SHORT_HEADER & SHORT_FLAG[headIndex]) == SHORT_FLAG[headIndex]) && shortHeader) {
									((int16_t*)p_tx_dat)[0] = TRANSMIT_SIGNAL_POS_I; // Real (I)
									((int16_t*)p_tx_dat)[1] = TRANSMIT_SIGNAL_POS_Q; // Imag (Q)
								} else {
									((int16_t*)p_tx_dat)[0] = TRANSMIT_SIGNAL_NEG_I; // Real (I)
									((int16_t*)p_tx_dat)[1] = TRANSMIT_SIGNAL_NEG_Q; // Imag (Q)
								}
							}
							p_tx_dat += p_tx_inc;
						}
						if (p_tx_dat > p_tx_dat_safety)
							break;
					}
					
					for (int bitPlace=7;bitPlace>=0;bitPlace--) {
						if ((bytein[i] & BYTE_FLAG[bitPlace]) == BYTE_FLAG[bitPlace]) {
							((int16_t*)p_tx_dat)[0] = TRANSMIT_SIGNAL_POS_I; // Real (I)
							((int16_t*)p_tx_dat)[1] = TRANSMIT_SIGNAL_POS_Q; // Imag (Q)
						} else {
							((int16_t*)p_tx_dat)[0] = TRANSMIT_SIGNAL_NEG_I; // Real (I)
							((int16_t*)p_tx_dat)[1] = TRANSMIT_SIGNAL_NEG_Q; // Imag (Q)
						}
						p_tx_dat += p_tx_inc;
						if (p_tx_dat > p_tx_dat_safety)
							break;
					}
					bytesSent++;
					if (p_tx_dat > p_tx_dat_safety)
						break;
				}
				if (p_tx_dat > p_tx_dat_safety)
					break;
			}

			while (p_tx_dat < p_tx_end) {
				((int16_t*)p_tx_dat)[0] = 0; // Real (I)
				((int16_t*)p_tx_dat)[1] = 0; // Imag (Q)
				p_tx_dat += p_tx_inc;
			}
			emptyBuffer = false;

		} else {
			//if (!emptyBuffer) { //only change the Tx buffer if it's not already filled with empty data
				p_tx_dat = (char *)iio_buffer_first(txbuf, tx0_i);
				p_tx_inc = iio_buffer_step(txbuf);
				p_tx_end = iio_buffer_end(txbuf);
				while (p_tx_dat < p_tx_end) {
					((int16_t*)p_tx_dat)[0] = 0; // Real (I)
					((int16_t*)p_tx_dat)[1] = 0; // Imag (Q)
					p_tx_dat += p_tx_inc;
				}
				emptyBuffer = true;
			//}
		}

		// Schedule TX buffer
		nbytes_tx = iio_buffer_push(txbuf);
		
		if (binOut) {
			fflush(stdout);
			if (activityThisCycle) {
				//if (bytesSent < 255)
				if (bytesInput < 255)
					SEND_NOTIFICATION[SEND_NOTIFICATION_LEN-1] = (char)bytesInput;
				else
					SEND_NOTIFICATION[SEND_NOTIFICATION_LEN-1] = (char)255;
				fwrite(SEND_NOTIFICATION,1,SEND_NOTIFICATION_LEN,stdout);
			} else {
				fwrite(NO_DATA_HEARTBEAT,1,NO_DATA_HEARTBEAT_LEN,stdout);
			}
			fflush(stdout);
		}


		if (verbose) {
			if (activityThisCycle) {
				stopTime = clock();
				printf("d Cycle time: %.3f msn",(double)((double)(stopTime-startTime) / CLOCKS_PER_SEC) * 1000);
				activityThisCycle = false;
			}
		}
	}
 	shutdown();
	return 0;
}