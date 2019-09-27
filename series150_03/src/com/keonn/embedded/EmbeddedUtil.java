package com.keonn.embedded;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import net.ihg.framework.service.ServiceManager;
import net.ihg.framework.service.services.SchedulerService;
import net.ihg.framework.service.services.SchedulerServiceCallback;
import net.ihg.util.EnumUtil;
import net.ihg.util.HexStringX;
import net.ihg.util.StringUtil;
import snaq.util.jclap.CLAParser;
import snaq.util.jclap.OptionException;

import com.keonn.adrd_150.ADRD_150_Series_SerialReader;
import com.keonn.adrd_150.ReaderIOListener;
import com.keonn.adrd_m4_100.KeonnSerialReader;
import com.keonn.os.io.ADCHandler;
import com.keonn.os.io.GPIOHandler;
import com.keonn.os.io.GPIOManagement;
import com.keonn.os.io.IOADCEvent;
import com.keonn.os.io.IOEvent;
import com.keonn.os.io.IOGPIEvent;
import com.thingmagic.Gen2;
import com.thingmagic.Gen2.Session;
import com.thingmagic.Gen2.Target;
import com.thingmagic.ReadExceptionListener;
import com.thingmagic.ReadListener;
import com.thingmagic.Reader;
import com.thingmagic.Reader.Region;
import com.thingmagic.ReaderException;
import com.thingmagic.SerialReader;
import com.thingmagic.SimpleReadPlan;
import com.thingmagic.TMConstants;
import com.thingmagic.TagProtocol;
import com.thingmagic.TagReadData;

/**
 *
 * Copyright (c) 2016 Keonn Technologies S.L.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author avives
 *
 */
public class EmbeddedUtil implements ReadListener, ReaderIOListener, ReadExceptionListener{
	
	private static final int DEF_DUTY_CYCLE = 100;
	private static final int DEF_READ_POWER = 3000;
	private static final int DEF_ON_TIME = 300;
	
	/**
	 * Antenna structure must be completely defined.
	 * There is no self-discovery mechanism
	 * 
	 * Antennas are defined as follows
	 * {1,1}: antenna connected to reader port 1 and multiplexer out 1
	 * {1,2}: antenna connected to reader port 1 and multiplexer out 2
	 */
	private static final int[][] fast_mux_antennas = new int[][]{{1,0}};
	
	/** Do not change this */
	private static final int targetBaudrate = 921600;
	
	//Read power defined in cdBm
	private static int readPower = DEF_READ_POWER;
	
	// duty cycle percentage
	private static int dutyCycle = DEF_DUTY_CYCLE;
	
	// RF on time
	private static int asyncOnTime = DEF_ON_TIME;
	
	// RF off time
	private static int asyncOffTime = 0;
	
	/**
	 * EPCgen2 Session
	 */
	private static Session session = Session.S0;
	
	/**
	 * EPCgen2 Target
	 */
	private static Target target = Target.AB;
	
	private static final int CONNECT_TIMEOUT=5000;
	private static final String CONTENT_TYPE = "text/plain";

	private static final int MAX_SIZE = 10000;

	private static final int SENT_PERIOD = 20000;
	

	private static boolean enableGPI;
	private static boolean enableSend;
	private static boolean enableMux;
	private static boolean printADC;

	private static boolean debug;

	private static Region region;

	private static int[] antennas;

	public static void main(String[] args){
		
		// PARSE COMMAND LINE OPTIONS
		
		CLAParser parser = new CLAParser();
		
		parser.addBooleanOption("d", "debug", "Debugging information", false);
		parser.addBooleanOption("y", "adc", "Print AD information", false);
		parser.addStringOption("t", "target", "EPCGen2 target", false, false);
		parser.addStringOption("s", "session", "EPCGen2 session", false, false);
		parser.addStringOption("r", "region", "EPCGent2 region", false, false);
		parser.addIntegerOption("a", "antennas", "Active antennas", false, true);
		parser.addIntegerOption("z", "power", "Power in cdBm", false, false);
		parser.addBooleanOption("g", "gpi", "Enable GPI to trigger RF", false);
		parser.addBooleanOption("m", "mux", "use multiplexers", false);
		parser.addBooleanOption("x", "send", "Enable data send", false);
		parser.addBooleanOption("h", "help", "Prints help message", false);
		parser.addStringOption("b", "host", "Host IP or name where read data is sent (optional)", false, false);
		parser.addIntegerOption("p", "port", "Host port of the server where read data is sent (optional)", false, false);
		parser.addIntegerOption("c", "duty-cycle", "Duty cycle percentage (5% -100%)", false, false);
		parser.addIntegerOption("o", "on-time", "RF On time (50 -2000) ms", false, false);
		parser.addStringOption("u", "url", "URL where to send data (optional)", false, false);
		
		String host=null;
		String file=null;
		String t=null;
		String s=null;
		String r=null;
		int port;
		try {
			parser.parse(args);
			
			if(parser.getBooleanOptionValue("h")){
				parser.printUsage(System.out, true);
				System.exit(-1);
			}
			
			
			host = parser.getStringOptionValue("b", "localhost");
			file = parser.getStringOptionValue("u", null);
			if(file!=null && !file.startsWith("/")){
				file="/"+file;
			}
			
			port = parser.getIntegerOptionValue("p", 80);
			readPower = parser.getIntegerOptionValue("z", DEF_READ_POWER);
			enableGPI = parser.getBooleanOptionValue("g");
			enableSend = parser.getBooleanOptionValue("x");
			enableMux = parser.getBooleanOptionValue("m");
			printADC = parser.getBooleanOptionValue("y");
			debug = parser.getBooleanOptionValue("d");
			t = parser.getStringOptionValue("t","A");
			s = parser.getStringOptionValue("s","S1");
			r = parser.getStringOptionValue("r","ETSI");
			List<Integer> ants = parser.getIntegerOptionValues("a");
			if(ants==null || ants.size()==0){
				// Add at least antenna at port #1
				antennas = new int[1];
				antennas[0]=1;
				
			} else {
				antennas = new int[ants.size()];
				int i=0;
				for(int a: ants){
					antennas[i++]=a;
				}
			}
			
			target = EnumUtil.getEnumForString(Gen2.Target.class, t);
			if(target==null){
				target = Target.A;
			}
			
			session = EnumUtil.getEnumForString(Gen2.Session.class, s);
			if(session==null){
				session = Session.S1;
			}
			
			region = EnumUtil.getEnumForString(Reader.Region.class, r);
			if(region==null){
				region = Region.EU3;
			}
			
			if(readPower<0 || readPower>3150){
				readPower=readPower<0?0:3150;;
			}
			
			dutyCycle = parser.getIntegerOptionValue("c", DEF_DUTY_CYCLE);
			if(dutyCycle<5 || dutyCycle > 100){
				dutyCycle=dutyCycle<5?5:100;
			}
			
			asyncOnTime = parser.getIntegerOptionValue("o", DEF_ON_TIME);
			if(asyncOnTime<50 || asyncOnTime>2000){
				asyncOnTime=asyncOnTime<50?50:2000;
			}
			
			
		} catch (OptionException e) {
			e.printStackTrace();
			return;
		}
		
		try {
			
			URL url = null;
			if(file!=null){
				url = new URL("http", host, port, file);
			}
			
			/**
			 * Shutdown hook to allow resource cleaning!!
			 */
			final EmbeddedUtil app = new EmbeddedUtil(url);
			Runtime.getRuntime().addShutdownHook(new Thread(){
				public void run(){
					app.shutdown();
				}
			});
			
			app.run();
			
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private ADRD_150_Series_SerialReader reader;
	
	private GPIOManagement io;
	private URL url;
	private Semaphore sendSemaphore = new Semaphore(1);
	private Set<String> epcs = new HashSet();

	private long lastSent;
	
	public EmbeddedUtil(URL url) {
		this.url=url;
	}
	
	/**
	 * Release resources
	 */
	private void shutdown() {
		if(reader!=null){
			System.out.println("Stopping reader...");
			reader.stopReading();
			reader.destroy();
			reader=null;
		}
	}

	private void run() {
		try {
			
			Reader tmReader = Reader.create("eapi:///dev/ttyO1");
			
			if(!(tmReader instanceof KeonnSerialReader)){
				System.out.println("Reader class is not valid??? "+tmReader);
				System.exit(-1);
			}
			
			reader = (ADRD_150_Series_SerialReader) tmReader;
			
			if(reader instanceof ADRD_150_Series_SerialReader){
				((ADRD_150_Series_SerialReader)reader).registerIOListener(this);
				io = ((ADRD_150_Series_SerialReader)reader).getGPIO();
			} else {
				System.out.println("Wrong reader class: "+reader.getClass().getName());
			}
			
			System.out.println("Reader class: "+reader.getClass().getName());
			
			reader.connect();
			
			String fw = (String) reader.paramGet(TMConstants.TMR_PARAM_VERSION_SOFTWARE);
			String model = (String) reader.paramGet(TMConstants.TMR_PARAM_VERSION_MODEL);
			String serial= (String) reader.paramGet(TMConstants.TMR_PARAM_VERSION_SERIAL);
			System.out.println("Reader model: "+model);
			System.out.println("Reader serial: "+serial);
			System.out.println("Reader software version: "+fw);
			
			// change baudrate to get the maximum comm speed
			SerialReader adrd = (SerialReader) reader;
			int baudrate = adrd.getSerialTransport().getBaudRate();
			
			if(baudrate!=targetBaudrate){
				adrd.cmdSetBaudRate(targetBaudrate);
				adrd.getSerialTransport().setBaudRate(targetBaudrate);
			}

			// verification
			int ports = ((int[]) reader.paramGet(TMConstants.TMR_PARAM_ANTENNA_PORTLIST)).length;
			int maxPower = (int) reader.paramGet(TMConstants.TMR_PARAM_RADIO_POWERMAX);
			int minPower = (int) reader.paramGet(TMConstants.TMR_PARAM_RADIO_POWERMIN);
			
			verifyAntennas(antennas, ports);
			verifyPower(readPower, minPower, maxPower);
			
			asyncOffTime = (asyncOnTime*100)/dutyCycle - asyncOnTime;
			
			// reader configuration
			reader.paramSet(TMConstants.TMR_PARAM_REGION_ID, region);
			reader.paramSet(TMConstants.TMR_PARAM_READ_ASYNCONTIME, asyncOnTime);
			reader.paramSet(TMConstants.TMR_PARAM_READ_ASYNCOFFTIME, asyncOffTime);
			reader.paramSet(TMConstants.TMR_PARAM_TAGOP_PROTOCOL, TagProtocol.GEN2);
			reader.paramSet(TMConstants.TMR_PARAM_RADIO_READPOWER, readPower);
			reader.paramSet(TMConstants.TMR_PARAM_GEN2_SESSION, session);
			reader.paramSet(TMConstants.TMR_PARAM_GEN2_TARGET, target);
			reader.addReadListener(this);
			reader.addReadExceptionListener(this);
			
			
			System.out.println("region: "+reader.paramGet(TMConstants.TMR_PARAM_REGION_ID));
			System.out.println("session: "+reader.paramGet(TMConstants.TMR_PARAM_GEN2_SESSION));
			System.out.println("target: "+reader.paramGet(TMConstants.TMR_PARAM_GEN2_TARGET));
			System.out.println("Duty cycle: "+dutyCycle);
			System.out.println("asyncOnTime: "+reader.paramGet(TMConstants.TMR_PARAM_READ_ASYNCONTIME));
			System.out.println("asyncOffTime: "+reader.paramGet(TMConstants.TMR_PARAM_READ_ASYNCOFFTIME));
			System.out.println("Available ports: "+ports);
			System.out.println("Max conducted power (cdBm): "+reader.paramGet(TMConstants.TMR_PARAM_RADIO_POWERMAX));
			
			
			SimpleReadPlan srp = new SimpleReadPlan(antennas, TagProtocol.GEN2);	
			reader.paramSet(TMConstants.TMR_PARAM_READ_PLAN, srp);
			
			System.out.println("Please make sure configured antennas ("+Arrays.toString(antennas)+") are connected to 50 ohm antennas ort terminators [Yes/No]");
			BufferedReader clReader = new BufferedReader(new InputStreamReader(System.in));
			String confirm = clReader.readLine();
			if(!"yes".equalsIgnoreCase(confirm) && !"y".equalsIgnoreCase(confirm)){
				System.out.println("Please connect antennas or terminators and run again the command.");
				System.exit(0);
			}
			
			if(!enableGPI){
				lastSent = System.currentTimeMillis();
				if(enableMux && (reader instanceof ADRD_150_Series_SerialReader)){
					System.out.println("Starting reader in autonomous mode to use muxes...");
					((ADRD_150_Series_SerialReader)reader).startFastMuxReading(fast_mux_antennas);
				} else {
					System.out.println("Starting reader in autonomous mode...");
					reader.startReading();
				}
				
			} else {
				
			}

			
		} catch (Throwable e) {
			e.printStackTrace();
		}
	}

	private void verifyPower(int readPower, int minPower, int maxPower) {
		if(readPower<minPower || readPower>maxPower){
			throw new RuntimeException("Invalid read power configuration: "+readPower+". Min power: "+minPower+", max power: "+maxPower);
		}
	}

	private void verifyAntennas(int[] antennas, int ports) {
		for(int antenna: antennas){
			if(antenna>ports){
				throw new RuntimeException("Invalid antenna configuration: "+Arrays.toString(antennas)+". Available ports: "+ports);
			}
		}
	}

	boolean gpioEnabled=false;
	@Override
	public void tagRead(Reader r, TagReadData t) {
		final long now = System.currentTimeMillis();
		String epc=HexStringX.printHex(t.getTag().epcBytes());
		
		System.out.println("["+t.getTime()+"] epc["+HexStringX.printHex(t.getTag().epcBytes())+"] antenna["+t.getAntenna()+"] rssi["+t.getRssi()+" dBm]");
		
		// enable GPO1
		if(io!=null){
			try {
				io.setGPO(1, gpioEnabled);
				gpioEnabled=!gpioEnabled;
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
		if(!enableGPI){
			
			// GPI is not enabled
			if(enableSend && (now-lastSent)>SENT_PERIOD){
				epcs.add(epc);
				
				SchedulerService service = (SchedulerService) ServiceManager.getGlobal().getService("Scheduler");
				service.scheduleTask(new SchedulerServiceCallback() {

					public String getName() {
						return "GPIO task";
					}

					public boolean callback() {

						sendData();
						lastSent=now;
						return true;
					}
				}, 1, TimeUnit.MILLISECONDS);
				
			}
		} else {
			
			// GPI enabled
			// sending data is triggered by the GPI fall edge
			epcs.add(epc);
		}
	}

	@Override
	public void onIOEvent(Reader r, IOEvent event) {
		
		if(event instanceof IOADCEvent){
		
			if(printADC){
				IOADCEvent adc = (IOADCEvent) event;
				ADCHandler handler = (ADCHandler) event.getSource();
				String path="";
				if(reader instanceof KeonnSerialReader){
					path =((KeonnSerialReader)reader).getPath();
				}
				System.out.println("Reader["+path+"] IO event["+adc.getType()+"] measure[id:"+handler.getId()+" channel:"+handler.getHWLine()+"]: "+adc.getMeasure());
			}
			
		} else if (event instanceof IOGPIEvent){
			
			String path = (r instanceof KeonnSerialReader)?((KeonnSerialReader)r).getPath():"";
			IOGPIEvent io = (IOGPIEvent) event;
			GPIOHandler handler = (GPIOHandler) io.getSource();
			System.out.println("Reader["+path+"] IO event["+handler.getHWLine()+"::"+handler.getId()+"] lowToHigh: "+io.isLowToHigh());
			
			try {
				
				if(enableGPI){
					if(io.isLowToHigh()){
						
						// start RF
						if(enableMux && (reader instanceof ADRD_150_Series_SerialReader)){
							((ADRD_150_Series_SerialReader) reader).startFastMuxReading(fast_mux_antennas);
						} else {
							reader.startReading();
						}
						
						
					} else {
						// stop RF
						if(enableMux && (reader instanceof ADRD_150_Series_SerialReader)){
							((ADRD_150_Series_SerialReader) reader).stopFastMuxReading();
						} else {
							reader.stopReading();
						}
						// send data
						sendData();
					}
				} else if (!io.isLowToHigh()){
					// send data
					sendData();
				}
				
			} catch (ReaderException e) {
				e.printStackTrace();
			}
		}
	}
	
	private void sendData() {
		
		boolean acquired=false;
		try {
			
			if(!sendSemaphore.tryAcquire()){
				System.out.println("There is an ongoing sending process...");
				return;
			}
			
			acquired=true;
			sendDataReal();
			
			
		} finally{
			if(acquired){
				sendSemaphore.release();
			}
		}
	}

	private void sendDataReal() {
		if(url==null){
			return;
		}
		if(epcs.size()==0){
			System.out.println("No tags to send.");
			return;
		}
		HttpURLConnection conn=null;
		try {
			conn = (HttpURLConnection) url.openConnection();
			conn.setConnectTimeout(CONNECT_TIMEOUT);
			
			conn.setDoOutput(true);
			conn.setDoInput(true);
			conn.setRequestMethod("POST");
			
			String s = StringUtil.fromSet(epcs, ",");
			conn.setRequestProperty("Content-Type", CONTENT_TYPE);
			conn.setRequestProperty("Content-Length", ""+s.length());
			
			System.out.println("Connecting to server: "+url.getHost()+":"+url.getPort()+"/"+url.getFile());
			conn.connect();
			
			if(debug){
				System.out.println("Sending["+epcs.size()+" tags]: "+s);
			}
			
			BufferedOutputStream bos = new BufferedOutputStream(conn.getOutputStream());
			StringUtil.toOutputStream(s, bos);
			
			bos.flush();
			bos.close();
			
			if(debug){
				System.out.println("Headers received: "+conn.getHeaderFields());
			}
			
			String header = conn.getHeaderField(null);
			if(header==null){
				throw new IOException("Unable to detect header");
			}
			
			if(!header.contains("200")){
				
				System.out.println("Invalid header status: "+header);	
				throw new IOException("Invalid header status: "+header);
			}
			
			// if everything is fine clear epcs
			epcs.clear();
			
		} catch (IOException e) {
			System.out.println("IOError["+e.getMessage()+"] connecting to server: "+url.getHost()+":"+url.getPort()+"/"+url.getFile());
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			if(epcs.size()>MAX_SIZE){
				System.out.println("Unable to send data. EPC buffer is too large. Removing it");
				System.out.println("Removed epcs: "+epcs);
				epcs.clear();
			}
		}
	}

	@Override
	public void tagReadException(Reader r, ReaderException re) {
		re.printStackTrace();
	}
}