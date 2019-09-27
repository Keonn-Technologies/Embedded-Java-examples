package com.keonn.embedded;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import net.ihg.util.EnumUtil;
import net.ihg.util.HexStringX;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

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
public class MQTTPublish implements ReadListener, ReaderIOListener, ReadExceptionListener{
	
	private static final int DEF_DUTY_CYCLE = 100;
	private static final int DEF_READ_POWER = 3000;
	private static final int DEF_ON_TIME = 300;
	private static final String DEF_MQTT_TOPIC = "rfid-data";
	private static final String DEF_MQTT_ID = "keonn-reader";
	private static final int DEF_MQTT_QOS = 1;
	
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
	
	// Each element in the queue will use 4 (object ref) + 28 (String) + 8 (long) bytes = 40 bytes
	private static final int QUEUE_MAX_SIZE = 10000;
	public static final int MIN_SLEEP_TIME = 150;

	private static boolean enableGPI;
	private static boolean enableMux;
	private static boolean printADC;

	private static boolean debug;
	private static boolean verbose;

	private static Region region;

	private static int[] antennas;
	
	//MQTT variables
	private static String mqttBroker;
	private static String mqttTopic;
	private static String mqttClientId;
	private static int mqttQos;
	private MqttClient mqttClient;
	private MqttConnectOptions connOpts;
	private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
	
	private ADRD_150_Series_SerialReader reader;
	private GPIOManagement io;
	private Queue<Object[]> queue = new ConcurrentLinkedQueue();
	private EventDispatcher dispatcher;
	

	public static void main(String[] args){
		
		// PARSE COMMAND LINE OPTIONS
		
		CLAParser parser = new CLAParser();
		
		parser.addBooleanOption("d", "debug", "Debug information", false);
		parser.addBooleanOption("v", "verbose", "Verbose information", false);
		parser.addBooleanOption("y", "adc", "Print AD information", false);
		parser.addStringOption("t", "target", "EPCGen2 target", false, false);
		parser.addStringOption("s", "session", "EPCGen2 session", false, false);
		parser.addStringOption("r", "region", "EPCGent2 region", false, false);
		parser.addIntegerOption("a", "antennas", "Active antennas", false, true);
		parser.addIntegerOption("z", "power", "Power in cdBm", false, false);
		parser.addBooleanOption("g", "gpi", "Enable GPI to trigger RF", false);
		parser.addBooleanOption("m", "mux", "use multiplexers", false);
		parser.addBooleanOption("h", "help", "Prints help message", false);
		parser.addIntegerOption("c", "duty-cycle", "Duty cycle percentage (5% -100%)", false, false);
		parser.addIntegerOption("o", "on-time", "RF On time (50 -2000) ms", false, false);
		parser.addStringOption("b", "tpqq-broker", "MQTT broker (example tcp://localhost:1883)", false, false);
		parser.addStringOption("i", "tpqq-id", "MQTT broker client id (defaults to "+DEF_MQTT_ID+")", false, false);
		parser.addStringOption("u", "tpqq-topic", "MQTT broker topic (defaults to "+DEF_MQTT_TOPIC+")", false, false);
		parser.addIntegerOption("q", "tpqq-qos", "MQTT broker QoS (defaults to "+DEF_MQTT_QOS+")", false, false);
		
		
		String t=null;
		String s=null;
		String r=null;
		try {
			parser.parse(args);

			if(parser.getBooleanOptionValue("h")){
				parser.printUsage(System.out, true);
				System.exit(-1);
			}
			
			readPower = parser.getIntegerOptionValue("z", DEF_READ_POWER);
			enableGPI = parser.getBooleanOptionValue("g");
			enableMux = parser.getBooleanOptionValue("m");
			printADC = parser.getBooleanOptionValue("y");
			debug = parser.getBooleanOptionValue("d");
			verbose = parser.getBooleanOptionValue("v");
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
			
			mqttBroker = parser.getStringOptionValue("b", null);
			mqttClientId = parser.getStringOptionValue("i", DEF_MQTT_ID);
			mqttTopic = parser.getStringOptionValue("u", DEF_MQTT_TOPIC);
			mqttQos = parser.getIntegerOptionValue("q", DEF_MQTT_QOS);
			
			
		} catch (OptionException e) {
			e.printStackTrace();
			return;
		}
		
		try {
			
			/**
			 * Shutdown hook to allow resource cleaning!!
			 */
			final MQTTPublish app = new MQTTPublish();
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
	
	public MQTTPublish() {
		
		if(mqttBroker!=null && mqttBroker.trim().length()>0){
			try {
				mqttClient = new MqttClient(mqttBroker, mqttClientId, new MemoryPersistence());
		        connOpts = new MqttConnectOptions();
		        connOpts.setCleanSession(true);
		        mqttClient.connect(connOpts);
		        System.out.println("Connected mqtt@"+mqttBroker);
				
			} catch (MqttException e) {
				System.out.println("Unable to connect to mqtt@"+mqttBroker+": "+e.getMessage());
				System.out.println("We will try to connect later on...");
			}
			
			// avp@Jun 22, 2016
			// Start connection monitor
	        scheduler.scheduleAtFixedRate(new Runnable() {
				
				@Override
				public void run() {
					reconnectMQTT();
					
				}
			}, 10, 5, TimeUnit.SECONDS);
	        
	        dispatcher = new EventDispatcher();
			dispatcher.start();
		}
	}


	/**
	 * Release resources
	 */
	private void shutdown() {
		
		System.out.println("Shutting down application...");
		if(dispatcher!=null){
			dispatcher.shutdown();
		}
		
		if(reader!=null){
			try {
				System.out.println("Stopping reader...");
				reader.stopReading();
				reader.destroy();
				reader=null;
			} catch (Exception e) {
				System.out.println("Error shutting down reader.");
				e.printStackTrace();
			}
		}
		
		if(mqttClient!=null && mqttClient.isConnected()){
			try {
				System.out.println("Disconnecting from MQTT server...");
				mqttClient.disconnect(2000);
			} catch (MqttException e) {
				e.printStackTrace();
			}
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
		
		long now = System.currentTimeMillis();
		String epc=HexStringX.printHex(t.getTag().epcBytes());
		
		System.out.println("["+now+"] epc["+HexStringX.printHex(t.getTag().epcBytes())+"] antenna["+t.getAntenna()+"] rssi["+t.getRssi()+" dBm]");
		
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
		
		// VERY IMPORTANT!!!
		// enqueue epc to decouple the tag generation with the
		// sending of the tags
		// The tagRead method must finish as soon as possible. Doing large operations in the method
		// may result in uart errors.
		if(mqttClient!=null){
			if(queue.size()>QUEUE_MAX_SIZE){
				System.out.println("Queue is growing too much. Discarding oldest events...");
				queue.poll();
			}
			
			queue.add(new Object[]{epc,t.getTime()});
			if(mqttClient.isConnected()){
				synchronized (queue) {
					queue.notify();
				}
			}
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
					}
				}
				
			} catch (ReaderException e) {
				e.printStackTrace();
			}
		}
	}

	@Override
	public void tagReadException(Reader r, ReaderException re) {
		re.printStackTrace();
	}
	
	protected void reconnectMQTT() {
		if(mqttClient!=null){
			if(!mqttClient.isConnected()){
				try {
					mqttClient.connect(connOpts);
					System.out.println("Connected mqtt@"+mqttBroker);
					
					// just in case there are messages in the queue and we hav no active reds
					synchronized (queue) {
						queue.notify();
					}
				} catch (MqttException e) {
					System.out.println("Failed to reconnect MQTT client: "+e.getMessage());
				}
			}
		}
	}
	
	// EventDispatcher is in charge to send messages to MQTT broker.
	// It is using a mix of wait/notify and variable sleep strategy based on measured throughput
	// * Under heavy load, the sleep time will reduce contention and context switching between the two active threads. It is possible to read 300/400 tags per second, we cannot afford 300 context switching per second
	// * A pure sleep strategy would be inefficient when no reads happen
	// * After an inactivity period, the first reads will be sent straight away, without any sleep delay
	
	private Throughput t = Throughput.getThroughput(5000);
	private int maxThroughput=10;
	private int maxSleep=200;
	private int minSleep=15;
	private int sleepStep=5;
	private int dispatchSleepTime=(maxSleep-minSleep)/2;
	private MqttMessage message = new MqttMessage(new byte[]{});
	StringBuilder sb = new StringBuilder(1024*10);
	
	private class EventDispatcher extends Thread{

		// enable it to stop thread operation
		boolean shutdown=false;
		
		public EventDispatcher(){
			super("Reader.EventDispatcher");
		}
		
		public void shutdown() {
			shutdown=true;
			synchronized (queue) {
				queue.notify();
			}
		}
				
		public void run(){
			
			while(!shutdown){
				
				try {
					
					synchronized (queue) {
						while(queue.isEmpty()){
							try {
								queue.wait();
							} catch (InterruptedException e) {
								return;
							}
						}
					}
					
					if(shutdown){
						return;
					}
					if(!queue.isEmpty() && mqttClient!=null && mqttClient.isConnected()){
					
						sb.setLength(0);
						sb.append("{[");
						Object[] read;
						int counter=0;
						while((read=queue.poll())!=null){
							sb.append("{\"epc\":\""+read[0]+"\"");
							sb.append(",\"ts\":"+read[1]);
							sb.append("},");
							counter++;
						}
						
						sb.deleteCharAt(sb.length()-2);
						sb.append("]}");
						
						// send data
						message.setPayload(sb.toString().getBytes());
						message.setQos(mqttQos);
				        
						mqttClient.publish(mqttTopic, message);
				        System.out.println("["+System.currentTimeMillis()+"] Sent "+counter+" epcs");
				        if(debug){
				        	System.out.println("Message: "+sb.toString());
				        }
				        t.hit();
				        
				        double f = t.getThroughput();
						
						if(f>maxThroughput){
							if(dispatchSleepTime<maxSleep){
								dispatchSleepTime+=sleepStep;
							}
						} else {
							if(dispatchSleepTime>minSleep){
								dispatchSleepTime-=sleepStep;
							}
						}
						
						if(verbose)
							System.out.println("["+System.currentTimeMillis()+"] dispatchSleepTime "+dispatchSleepTime);
						
						if(dispatchSleepTime>0){
							try {
								Thread.sleep(dispatchSleepTime);
							} catch (InterruptedException e) {
								return;
							}
						}
					}
					
					if(shutdown){
						return;
					}
					
				} catch (Exception e) {
					e.printStackTrace();
				} finally {

				}
			}
		}
	}
	
	private static class Throughput {
		
		private long windowTime;
		private long resolution;
		private long counter=0;
		private long lastChecked=-1;
		private Queue<Long> q = new ConcurrentLinkedQueue();

		private Throughput(long windowTimeMs, long resolutionMs){
			if(windowTimeMs<1){
				throw new IllegalArgumentException("WRONGWINDOWTIME_"+windowTimeMs);
			}
			if(resolutionMs<1){
				throw new IllegalArgumentException("WRONGRESOLUTION_"+resolutionMs);
			}
			this.windowTime=windowTimeMs*1000000;
			this.resolution=resolutionMs*1000000;
		}
		
		public static Throughput getThroughput(long windowTimeMs){
			Throughput t = new Throughput(windowTimeMs,100);
			return t;
		}
		
		
		public void hit(){
			counter++;
			long now = System.nanoTime();
			q.add(now);
			
			if((now-lastChecked)>resolution){
				check(now);
			}
		}
		
		public double getThroughput(){
			long now = System.nanoTime();
			if((now-lastChecked)>resolution){
				check(now);
			}
			return (double) counter/(windowTime/1000000000);
		}

		/**
		 * This may be an expensive method as it handles possible ConcurrentModificationException
		 * @param now
		 */
		private void check(long now) {
			Long i=null;
			while((i=q.peek())!=null){
				if((now-i)>windowTime){
					q.poll();
					counter--;
				} else {
					break;
				}
			}
			lastChecked=System.nanoTime();
		}
	}

}