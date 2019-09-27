package com.keonn.embedded;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import snaq.util.jclap.CLAParser;
import snaq.util.jclap.OptionException;

import com.keonn.impl.farsens.FarsensUtil;
import com.keonn.util.ByteUtil;
import com.keonn.util.RegexpUtil;
import com.thingmagic.Gen2;
import com.thingmagic.Gen2.Bank;
import com.thingmagic.Gen2.ReadData;
import com.thingmagic.Gen2.Session;
import com.thingmagic.Gen2.Target;
import com.thingmagic.Gen2.WriteData;
import com.thingmagic.ReadListener;
import com.thingmagic.Reader;
import com.thingmagic.ReaderException;
import com.thingmagic.SimpleReadPlan;
import com.thingmagic.TMConstants;
import com.thingmagic.TagProtocol;
import com.thingmagic.TagReadData;

/**
 * 
 * Copyright (c) 2014 Keonn Technologies S.L.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * 
 * @author avives
 * @copyright 2014 Keonn Technologies S.L. {@link http://www.keonn.com}
 *
 */
public class FarsensSensorsTest implements ReadListener{

	static enum SensorType{VORTEX,VORTEX_TEMP,VORTEX_PRESS,KINNEO,PYROS}
	private SensorType embeddedOp;
	private static int readPower;
	private static String readerAddress;
	private static Pattern vortexPattern;
	private static Pattern vortexPatternTemp;
	private static Pattern vortexPatternPress;
	private static Pattern kinneoPattern;
	private static Pattern pyrosPattern;
	private static List<Integer> antennaParams;
	private static boolean useEmbedded;
	private Map<String,Long> lastInit = new HashMap<String,Long>();
	
	ReadData pyrosData = new Gen2.ReadData(Bank.USER, 0x06, (byte) 4);
	ReadData vortexTemp = new Gen2.ReadData(Bank.USER, 0x2b, (byte) 2);
	ReadData vortexPress = new Gen2.ReadData(Bank.USER, 0x28, (byte) 3);
	WriteData vortexInit1 = new Gen2.WriteData(Bank.USER, 0x10, new short[]{0x0017});
	WriteData vortexInit2 = new Gen2.WriteData(Bank.USER, 0x20, new short[]{0x0094});
	ReadData kinneo = new Gen2.ReadData(Bank.USER, 0x28, (byte) 6);
	WriteData kinneoInit1 = new Gen2.WriteData(Bank.USER, 0x23, new short[]{0x0000});
	WriteData kinneoInit2 = new Gen2.WriteData(Bank.USER, 0x20, new short[]{0x005F});
	
	
	ReadData epcRead = new Gen2.ReadData(Bank.EPC, 0, (byte) 4);
	
	public FarsensSensorsTest() {
		// TODO Auto-generated constructor stub
	}

	public static void main(String[] args){
		try 
		{
			CLAParser parser = new CLAParser();
			parser.addStringOption("d", "device", "Device uart/IP", true, false);
			parser.addIntegerOption("a", "antenna", "Antenna port", true, true);
			parser.addIntegerOption("r", "power", "Read power (cdBm)", false, false);
			parser.addIntegerOption("i", "init", "Init period (ms)", false, false);
			parser.addStringOption("v", "vortex", "Vortex EPC pattern", false, false);
			parser.addStringOption("w", "vortex-temp", "Vortex temperature EPC pattern", false, false);
			parser.addStringOption("z", "vortex-press", "Vortex pressure EPC pattern", false, false);
			parser.addStringOption("k", "kinneo", "Kinneo EPC pattern", false, false);
			parser.addStringOption("p", "pyros", "Pyros EPC pattern", false, false);
			parser.addBooleanOption("x", "embedd", "Use embedded operations", false);
			
			try {
				parser.parse(args);
				
				useEmbedded = parser.getBooleanOptionValue("x", true);
				
				readerAddress = parser.getStringOptionValue("d",null);
				readPower=parser.getIntegerOptionValue("r", 3000);
				
				initPeriod=parser.getIntegerOptionValue("i", 10000);
				
				antennaParams = parser.getIntegerOptionValues("a");
				
				String tmp=parser.getStringOptionValue("v",null);
				if(Objects.nonNull(tmp)){
					vortexPattern = Pattern.compile(tmp);
				}
				
				tmp=parser.getStringOptionValue("w",null);
				if(Objects.nonNull(tmp)){
					vortexPatternTemp = Pattern.compile(tmp);
				}
				
				tmp=parser.getStringOptionValue("z",null);
				if(Objects.nonNull(tmp)){
					vortexPatternPress = Pattern.compile(tmp);
				}
				
				tmp=parser.getStringOptionValue("k",null);
				if(Objects.nonNull(tmp)){
					kinneoPattern = Pattern.compile(tmp);
				}
				
				tmp=parser.getStringOptionValue("p",null);
				if(Objects.nonNull(tmp)){
					pyrosPattern = Pattern.compile(tmp);
				}

			} catch (OptionException e) {
				System.out.println(e.getMessage());
				parser.printUsage(System.out, true);
				return;
			}
			
			
			final FarsensSensorsTest app = new FarsensSensorsTest();
			Runtime.getRuntime().addShutdownHook(new Thread(){
			public void run(){app.shutdown();}});
			app.run();
			
		} 
		catch (Exception e) 
		{
			e.printStackTrace();
		}
	}
	
	private Reader reader;
	private static long initPeriod=10000;
	private void shutdown() 
	{
		if(reader!=null){
			try {
				reader.stopReading();
				reader.destroy();
				reader=null;
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}
	
	private void run() 
	{
		try {
			
			int asyncOnTime = 150;
			int asyncOffTime = 1;
			int[] antennas = new int[antennaParams.size()];
			for(int i=0;i<antennas.length;i++){
				antennas[i]=antennaParams.get(i);
			}
			System.out.println("Antennas: "+antennaParams);
			System.out.println("Antennas: "+Arrays.toString(antennas));
			Session session = Session.S0;
			Target target = Target.AB;
			
			reader = Reader.create("eapi://"+readerAddress);
			//reader = new ADRD_M4_100_Serial(readerAddress,new ADRD_LantronixConfig(Devices.MATCHPORT_AR,null,true,12000));
			
			System.out.println("Reader class: "+reader.getClass().getName());
			reader.connect();
			String fw = (String) reader.paramGet("/reader/version/software");
			System.out.println("Reader software version: "+fw);
			
			reader.paramSet("/reader/region/id", Reader.Region.EU3);
			reader.paramSet("/reader/read/asyncOnTime", asyncOnTime);
			reader.paramSet("/reader/read/asyncOffTime", asyncOffTime);
			reader.paramSet("/reader/tagop/protocol", TagProtocol.GEN2);
			reader.paramSet("/reader/radio/readPower", readPower);
			reader.paramSet("/reader/radio/writePower", 3000);
			reader.paramSet("/reader/gen2/session", session);
			reader.paramSet("/reader/gen2/target", target);
			reader.paramSet(TMConstants.TMR_PARAM_COMMANDTIMEOUT, 200);
									
			System.out.println("Setting read plan");		
			SimpleReadPlan srp = new SimpleReadPlan (antennas, TagProtocol.GEN2);
			
			if(useEmbedded){
				if(vortexPatternTemp!=null){
					srp.Op=vortexTemp;
					embeddedOp=SensorType.VORTEX_TEMP;
				} else if (vortexPatternPress!=null) {
					srp.Op=vortexPress;
					embeddedOp=SensorType.VORTEX_PRESS;
				} else if (vortexPattern!=null) {
					srp.Op=vortexTemp;
					embeddedOp=SensorType.VORTEX;
				} else if (kinneoPattern!=null) {
					srp.Op=kinneo;
					embeddedOp=SensorType.KINNEO;
				} else if (pyrosPattern!=null) {
					srp.Op=pyrosData;
					embeddedOp=SensorType.PYROS;
				}
			}
			
			reader.paramSet("/reader/read/plan", srp);
			reader.addReadListener(this);
			
			reader.startReading();
			System.out.println("Inventory started");
			synchronized (this) {
				wait();
				
			}
			
		}
		catch (Exception e) 
		{
			e.printStackTrace();
		}
	}
	
	@Override
	public void tagRead(Reader r, TagReadData t) {
		
		long now = System.currentTimeMillis();
		String epc = t.getTag().epcString();
		
		// check tag type
		SensorType type = getType(epc);
		Object sensorData=null;
		
		if(type!=null){
			Long last = lastInit.get(epc);
			if(last==null || (now-last)>initPeriod){
				
				try {
					
					if(SensorType.VORTEX.equals(type) || SensorType.VORTEX_TEMP.equals(type) || SensorType.VORTEX_PRESS.equals(type)) {
						r.paramSet("/reader/tagop/antenna", t.getAntenna());
						r.executeTagOp(vortexInit1, t.getTag());
						r.executeTagOp(vortexInit2, t.getTag());
						System.out.println("Vortex tag["+epc+"] inited.");
						
					} else if(SensorType.KINNEO.equals(type)) {
						r.paramSet("/reader/tagop/antenna", t.getAntenna());
						r.executeTagOp(kinneoInit1, t.getTag());
						r.executeTagOp(kinneoInit2, t.getTag());
						System.out.println("Kinneo tag["+epc+"] inited.");
					}
					
					lastInit.put(epc,now);
					
				} catch (Exception e) {
					System.out.println("Error on init ops: "+e.getMessage());
				}
			}
		}
		
		// tag not inited skip
		if((SensorType.KINNEO.equals(type) || SensorType.VORTEX.equals(type)) && lastInit.get(epc)==null){
			return;
		}
		
		
		if(type!=null){
			if(SensorType.PYROS.equals(type)){
				
				try {
					byte[] data = getRawData(r,t,type);
					if(data!=null && data.length==8){
						sensorData = FarsensUtil.getPyros0373(data);
						System.out.println(now+" PYROS epc["+epc+"] temperature: "+sensorData);
					} else {
						System.out.println(now+" PYROS epc["+epc+"]");
					}
					
				} catch (Exception e) {
					System.out.println("Error reading Pyros tag["+epc+"]: "+e.getMessage());
					e.printStackTrace();
				}
			}
			if(SensorType.VORTEX.equals(type)) {
				try {
					byte[] data = getRawData(r,t,type);
					if(data!=null && data.length==4){
						sensorData = FarsensUtil.getVortexTemperature(data);
						System.out.println(now+" VORTEX temp. epc["+epc+"] temperature: "+sensorData);
					} else {
						System.out.println(now+" VORTEX temp. epc["+epc+"]");
					}
					
					if(data!=null && data.length==6){
						sensorData = FarsensUtil.getVortexPressure(data);
						Double pressure = (Double) sensorData;
						if(pressure > 700 && pressure < 1500){
							System.out.println(now+" VORTEX pressure epc["+epc+"] pressure: "+pressure);
						} else {
							System.out.println(now+" VORTEX pressure epc["+epc+"]");
						}
						
					} else {
						System.out.println(now+" VORTEX epc["+epc+"]");
					}
					
				} catch (Exception e) {
					System.out.println("Error reading Vortex tag["+epc+"]: "+e.getMessage());
					e.printStackTrace();
				}
			}
			
			if(SensorType.VORTEX_TEMP.equals(type)) {
				try {
					byte[] data = getRawData(r,t,type);
					if(data!=null && data.length==4){
						sensorData = FarsensUtil.getVortexTemperature(data);
						System.out.println(now+" VORTEX temp. epc["+epc+"] temperature: "+sensorData);
					} else {
						System.out.println(now+" VORTEX temp. epc["+epc+"]");
					}
					
					if(data!=null && data.length==6){
						sensorData = FarsensUtil.getVortexPressure(data);
						Double pressure = (Double) sensorData;
						if(pressure > 700 && pressure < 1500){
							System.out.println(now+" VORTEX pressure epc["+epc+"] pressure: "+pressure);
						} else {
							System.out.println(now+" VORTEX pressure epc["+epc+"]");
						}
						
					} else {
						System.out.println(now+" VORTEX epc["+epc+"]");
					}
					
				} catch (Exception e) {
					System.out.println("Error reading Vortex tag["+epc+"]: "+e.getMessage());
					e.printStackTrace();
				}
			}
			
			if(SensorType.VORTEX_PRESS.equals(type)) {
				try {
					byte[] data = getRawData(r,t,type);
					if(data!=null && data.length==4){
						sensorData = FarsensUtil.getVortexTemperature(data);
						System.out.println(now+" VORTEX temp. epc["+epc+"] temperature: "+sensorData);
					} else {
						System.out.println(now+" VORTEX temp. epc["+epc+"]");
					}
					
					if(data!=null && data.length==6){
						sensorData = FarsensUtil.getVortexPressure(data);
						Double pressure = (Double) sensorData;
						if(pressure > 700 && pressure < 1500){
							System.out.println(now+" VORTEX pressure epc["+epc+"] pressure: "+pressure);
						} else {
							System.out.println(now+" VORTEX pressure epc["+epc+"]");
						}
						
					} else {
						System.out.println(now+" VORTEX epc["+epc+"]");
					}
					
				} catch (Exception e) {
					System.out.println("Error reading Vortex tag["+epc+"]: "+e.getMessage());
					e.printStackTrace();
				}
			}
			
			if(SensorType.KINNEO.equals(type)) {
				try {
					byte[] data = getRawData(r,t,type);
					if(data!=null && data.length==12){
						sensorData = FarsensUtil.getKinneo(data);
						float[] ff = (float[]) sensorData;
						System.out.println(now+" KINNEO epc["+epc+"] acceleration(X,Y,Z): "+ff[0]+","+ff[1]+","+ff[2]);
					} else {
						System.out.println(now+" KINNEO epc["+epc+"]");
					}
					
				} catch (Exception e) {
					System.out.println("Error reading Kinneo tag["+epc+"]: "+e.getMessage());
					e.printStackTrace();
				}
			}
		} else {
			System.out.println(now+" epc["+epc+"]");
		}
	}
	
	private byte[] getRawData(Reader r, TagReadData t, SensorType type) throws ReaderException {
		
		short[] raw = null;
		
		if(useEmbedded){
			
			if(SensorType.PYROS.equals(type) && SensorType.PYROS.equals(embeddedOp)){
				return t.getData(); 
			} else if(SensorType.VORTEX.equals(type)  && SensorType.VORTEX.equals(embeddedOp)) {
				return t.getData(); 
			} else if(SensorType.VORTEX_TEMP.equals(type)  && SensorType.VORTEX_TEMP.equals(embeddedOp)) {
				return t.getData(); 
			} else if(SensorType.VORTEX_PRESS.equals(type) && SensorType.VORTEX_PRESS.equals(embeddedOp)) {
				return t.getData(); 
			} else if(SensorType.KINNEO.equals(type) && SensorType.KINNEO.equals(embeddedOp)) {
				return t.getData(); 
			}
			
			return null;
		}
		
		if(SensorType.PYROS.equals(type)){
			r.paramSet("/reader/tagop/antenna", t.getAntenna());
			raw = (short[]) r.executeTagOp(pyrosData, t.getTag());
		} else if(SensorType.VORTEX.equals(type) || SensorType.VORTEX_TEMP.equals(type)) {
			r.paramSet("/reader/tagop/antenna", t.getAntenna());
			raw = (short[]) r.executeTagOp(vortexTemp, t.getTag());
		} else if(SensorType.VORTEX_PRESS.equals(type)) {
			r.paramSet("/reader/tagop/antenna", t.getAntenna());
			raw = (short[]) r.executeTagOp(vortexPress, t.getTag());
		} else if(SensorType.KINNEO.equals(type)) {
			r.paramSet("/reader/tagop/antenna", t.getAntenna());
			raw = (short[]) r.executeTagOp(kinneo, t.getTag());
		}
		
		return ByteUtil.fromShortArray(raw);
	}

	private SensorType getType(String epc) {
		
		if(pyrosPattern!=null && RegexpUtil.matches(epc, pyrosPattern)){
			return SensorType.PYROS;
		} else if(vortexPattern!=null && RegexpUtil.matches(epc, vortexPattern)){
			return SensorType.VORTEX;
		} else if(vortexPatternTemp!=null && RegexpUtil.matches(epc, vortexPatternTemp)){
			return SensorType.VORTEX_TEMP;
		} else if(vortexPatternPress!=null && RegexpUtil.matches(epc, vortexPatternPress)){
			return SensorType.VORTEX_PRESS;
		} else if(kinneoPattern!=null && RegexpUtil.matches(epc, kinneoPattern)){
			return SensorType.KINNEO;
		}
		
		return null;
	}
}