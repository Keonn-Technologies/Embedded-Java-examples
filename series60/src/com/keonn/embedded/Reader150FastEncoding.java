package com.keonn.embedded;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.Semaphore;

import net.ihg.util.EnumUtil;
import net.ihg.util.HexStringX;
import snaq.util.jclap.CLAParser;
import snaq.util.jclap.OptionException;

import com.keonn.adrd_150.ADRD_150_Series_SerialReader;
import com.keonn.util.ByteUtil;
import com.thingmagic.Gen2;
import com.thingmagic.Gen2.Bank;
import com.thingmagic.Gen2.Session;
import com.thingmagic.Gen2.Target;
import com.thingmagic.Gen2.WriteData;
import com.thingmagic.Gen2.WriteTag;
import com.thingmagic.ReadListener;
import com.thingmagic.Reader;
import com.thingmagic.Reader.Region;
import com.thingmagic.ReaderException;
import com.thingmagic.SerialReader;
import com.thingmagic.SimpleReadPlan;
import com.thingmagic.StopOnTagCount;
import com.thingmagic.StopTriggerReadPlan;
import com.thingmagic.TMConstants;
import com.thingmagic.TagFilter;
import com.thingmagic.TagOp;
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
public class Reader150FastEncoding implements ReadListener{
	
	/** Do not change this */
	private static final int asyncOnTime = 600;
	private static final int asyncOffTime = 0;
	private static final int targetBaudrate = 921600;
	
	/**
	 * Read power defined in cdBm
	 */
	private static final int readPower = 3000;
	
	/**
	 * EPCgen2 Session
	 */
	private static Session session = Session.S0;
	
	/**
	 * EPCgen2 Target
	 */
	private static Target target = Target.AB;


	private static boolean debug;
	private static Region region;
	private static int[] antennas;
	
	private static int warmUp;
	private static int iterations;

	public static void main(String[] args){
		
		CLAParser parser = new CLAParser();
		parser.addBooleanOption("d", "debug", "Debugging information", false);
		parser.addStringOption("t", "target", "EPCGen 2 target", false, false);
		parser.addStringOption("s", "session", "EPCGen2 session", false, false);
		parser.addStringOption("r", "region", "EPCGent2 region", false, false);
		parser.addIntegerOption("a", "antennas", "Active antennas", false, true);
		parser.addIntegerOption("w", "warm", "Warm up iterations", false, false);
		parser.addIntegerOption("i", "iterations", "Iterations", false, false);
		
		String t=null;
		String s=null;
		String r=null;
		try {
			parser.parse(args);
			
			warmUp = parser.getIntegerOptionValue("w", 5);
			iterations = parser.getIntegerOptionValue("i", 100);
			
			debug = parser.getBooleanOptionValue("d");
			t = parser.getStringOptionValue("t","AB");
			s = parser.getStringOptionValue("s","S0");
			r = parser.getStringOptionValue("r","ETSI");
			List<Integer> ants = new ArrayList(parser.getIntegerOptionValues("a"));
			if(ants==null || ants.size()==0){
				ants.add(1);
			}
			
			antennas = new int[ants.size()];
			int i=0;
			for(int a: ants){
				antennas[i++]=a;
			}
			
			target = EnumUtil.getEnumForString(Gen2.Target.class, t);
			if(target==null){
				target = Target.AB;
			}
			
			session = EnumUtil.getEnumForString(Gen2.Session.class, s);
			if(session==null){
				session = Session.S0;
			}
			
			region = EnumUtil.getEnumForString(Reader.Region.class, r);
			if(region==null){
				region = Region.EU3;
			}
			
		} catch (OptionException e) {
			e.printStackTrace();
			parser.printUsage(System.out, true);
			return;
		}
		
		try {
			
			final Reader150FastEncoding app = new Reader150FastEncoding();
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
	private Semaphore sendSemaphore = new Semaphore(1);
	private Semaphore rfSemaphore = new Semaphore(1);
	private Set<String> epcs = new HashSet();

	private long lastSent;
	
	public Reader150FastEncoding() {
	}
	
	private void shutdown() {
		if(reader!=null){
			reader.stopReading();
			reader.destroy();
			reader=null;
		}
	}

	private void run() {
		try {
			
			Reader tmReader = Reader.create("eapi:///dev/ttyO1");
			
			if(!(tmReader instanceof ADRD_150_Series_SerialReader)){
				System.out.println("Reader class is not valid??? "+tmReader);
				System.exit(-1);
			}
			
			reader = (ADRD_150_Series_SerialReader)tmReader;
			System.out.println("Reader class: "+reader.getClass().getName());
			
			reader.connect();
			
			String fw = (String) reader.paramGet("/reader/version/software");
			System.out.println("Reader software version: "+fw);
			
			// change baudrate to get the maximum comm speed
			SerialReader adrd = (SerialReader) reader;
			int baudrate = adrd.getSerialTransport().getBaudRate();
			
			if(baudrate!=targetBaudrate){
				adrd.cmdSetBaudRate(targetBaudrate);
				adrd.getSerialTransport().setBaudRate(targetBaudrate);
			}
			
			reader.paramSet("/reader/region/id", region);
			reader.paramSet("/reader/read/asyncOnTime", asyncOnTime);
			reader.paramSet("/reader/read/asyncOffTime", asyncOffTime);
			reader.paramSet("/reader/tagop/protocol", TagProtocol.GEN2);
			reader.paramSet("/reader/radio/readPower", readPower);
			reader.paramSet("/reader/gen2/session", session);
			reader.paramSet("/reader/gen2/target", target);
			reader.paramSet(TMConstants.TMR_PARAM_COMMANDTIMEOUT, 1000);
			reader.addReadListener(this);
			
			System.out.println("region: "+region);
			System.out.println("Session: "+session);
			System.out.println("Target: "+target);
			
			
			int tidOffsetBit=0;
			int tidlengthBit=64;
			TagOp readTID = new Gen2.ReadData(Bank.TID, tidOffsetBit/16, (byte)(tidlengthBit/16));
			SimpleReadPlan srp = new SimpleReadPlan(antennas, TagProtocol.GEN2, true);
			srp.Op=readTID;
			reader.paramSet("/reader/read/plan", srp);
			
			TagReadData[] tags = reader.read(1000);
			if(tags==null || tags.length==0){
				System.out.println("No tags found to test");
				System.exit(0);
			}
			
			Set<String> set = new HashSet();
			
			System.out.println("Select one tag to be tested: ");
			for(int i=0;i<tags.length;i++){
				String epc = tags[i].getTag().epcString();
				if(tags[i].getData()!=null && tags[i].getData().length>0 && !set.contains(epc)){
					System.out.println("  ["+(i+1)+"] EPC: "+epc+ " TID: "+HexStringX.printHex(tags[i].getData()));
					set.add(epc);
				}
			}
			
			if(set.size()==0){
				System.out.println("No tags found to test");
				System.exit(0);
			}
			
			
			System.out.println("Enter number?");
			Scanner scanner = new Scanner(System.in);
			String line = scanner.nextLine();
			
			int selection=-1;
			try {
				selection = Integer.parseInt(line.trim());
			} catch (NumberFormatException e) {
				System.out.println("Invalid selection: "+line);
				System.exit(1);
			}
			
			if(selection<1 || selection>tags.length){
				System.out.println("Invalid selection: "+selection);
				System.exit(1);
			}
			
			reader.paramSet(TMConstants.TMR_PARAM_TAGOP_ANTENNA, tags[selection-1].getAntenna());
			TagFilter filter = new Gen2.Select(false, Bank.TID, tidOffsetBit, tidlengthBit, tags[selection-1].getData());
			System.out.println("Select filter. TID offset "+tidOffsetBit+" length: "+tidlengthBit+" mask: "+HexStringX.printHex(tags[selection-1].getData()));
			
			Gen2.WriteTag writeTag;
			Gen2.WriteData writeData;
			
			
			// Do warmup
			System.out.println("Type any key and Return to proceed to warmup phase");
			scanner.nextLine();
			
			StopOnTagCount sotc = new StopOnTagCount();
			sotc.N=1;
			StopTriggerReadPlan strp = new StopTriggerReadPlan(sotc, antennas, TagProtocol.GEN2, true);	
			strp.filter=filter;
			reader.paramSet("/reader/read/plan", strp);
			
			long total=System.nanoTime();
			
			for(int i=0;i<warmUp;i++){
				
				try{
					tags = reader.read(1000);
					if(tags.length>0){
						
						byte[] epc = tags[0].getTag().epcBytes();
						
						byte[] newepc = ByteUtil.copy(epc);
						ByteUtil.incrementTag(newepc,newepc.length-1);
						writeTag = new Gen2.WriteTag(new Gen2.TagData(newepc));
						reader.executeTagOp(writeTag, filter);
						System.out.println("EPC["+HexStringX.printHex(epc)+"] new EPC: "+HexStringX.printHex(newepc));
						
					} else {
						System.out.println("NO TAGS FOUND!!");
					}
				} catch (ReaderException e) {
					e.printStackTrace();
				}
			}
			
			System.out.println("Warmup rounds["+warmUp+"] in "+(System.nanoTime()-total)/(1000*warmUp)+" us/round");
			
			System.out.println("Type any key and Return to proceed to test phase");
			scanner.nextLine();
			
			testWriteEPC(reader, "Write incremental EPC with TID filter", filter, false);
			testWriteEPC(reader, "Write incremental EPC without TID filter", null, false);
			testWriteEPC(reader, "Write random EPC with TID filter", filter, true);
			testWriteEPC(reader, "Write random EPC without TID filter", null, true);
						
			testWriteWord(reader, "Write 1 word to EPC bank with TID filter",Bank.EPC, 2, new short[]{0x1000}, filter);
			testWriteWord(reader, "Write 1 word to EPC bank without TID filter",Bank.EPC, 2, new short[]{0x1000}, null);
			testWriteWord(reader, "Write 2 words to EPC bank with TID filter",Bank.EPC, 2, new short[]{0x1000,0x2000}, filter);
			testWriteWord(reader, "Write 2 words to EPC bank without TID filter",Bank.EPC, 2, new short[]{0x1000,0x2000}, null);
			testWriteWord(reader, "Write 3 words to EPC bank with TID filter",Bank.EPC, 2, new short[]{0x1000,0x2000,0x3000}, filter);
			testWriteWord(reader, "Write 3 words to EPC bank without TID filter",Bank.EPC, 2, new short[]{0x1000,0x2000,0x3000}, null);
			testWriteWord(reader, "Write 4 words to EPC bank with TID filter",Bank.EPC, 2, new short[]{0x1000,0x2000,0x3000,0x4000}, filter);
			testWriteWord(reader, "Write 4 words to EPC bank without TID filter",Bank.EPC, 2, new short[]{0x1000,0x2000,0x3000,0x4000}, null);
			
			testEmbeddedOp(reader, strp, "Write incremenal EPC as embedded app", null, false);
			testEmbeddedOp(reader, strp, "Write incremenal EPC as embedded app", filter, false);
			testEmbeddedOp(reader, strp, "Write random EPC as embedded app", null, true);
			testEmbeddedOp(reader, strp, "Write random EPC as embedded app", filter, true);
			
			
			reader.paramSet("/reader/read/plan", srp);
			
			tags = reader.read(1000);
			if(tags==null || tags.length==0){
				System.out.println("No tags found to test");
				System.exit(0);
			}
			
			set.clear();
			
			System.out.println("Tags in the field.");
			for(int i=0;i<tags.length;i++){
				String epc = tags[i].getTag().epcString();
				if(!set.contains(epc)){
					System.out.println("  ["+(i+1)+"] EPC: "+epc+ " TID: "+HexStringX.printHex(tags[i].getData()));
					set.add(epc);
				}
			}
			
			System.exit(1);
			
			
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	
	private void testWriteEPC(ADRD_150_Series_SerialReader reader,
			String title, TagFilter filter, boolean random) {
		Random r = new Random();
		long total=System.nanoTime();
		long invTime=0;
		long writeTime=0;
		long tmp;
		TagReadData[] tags;
		WriteTag writeTag = new Gen2.WriteTag(new Gen2.TagData(new byte[]{0,0,0,0,0,0,0,0,0,0,0,0}));
		for(int i=0;i<iterations;i++){
			try{
				tmp=System.nanoTime();
				tags = reader.read(1000);
				invTime+=(System.nanoTime()-tmp);
				if(tags.length>0){
					
					byte[] epc = tags[0].getTag().epcBytes();
					
					byte[] newepc;
					
					if(random){
						newepc = new byte[epc.length];
						r.nextBytes(newepc);
					} else {
						newepc = ByteUtil.copy(epc);
						ByteUtil.incrementTag(newepc,newepc.length-1);
					}
					
					tmp=System.nanoTime();
					writeTag.Epc=new Gen2.TagData(newepc);
					reader.executeTagOp(writeTag, filter);
					writeTime+=(System.nanoTime()-tmp);
					if(debug)
						System.out.println("EPC["+HexStringX.printHex(epc)+"] new EPC: "+HexStringX.printHex(newepc));
					
				} else {
					System.out.println("NO TAGS FOUND!!");
				}
			} catch (ReaderException e) {
				e.printStackTrace();
			}
		}
		
		System.out.println();
		System.out.println(title);
		System.out.println("Test rounds["+iterations+"] inventory time "+invTime/(1000*iterations)+" us/round");
		System.out.println("Test rounds["+iterations+"] write time "+writeTime/(1000*iterations)+" us/round");
		System.out.println("Test rounds["+iterations+"] in "+(System.nanoTime()-total)/(1000*iterations)+" us/round");
		
	}
	
	private void testEmbeddedOp(ADRD_150_Series_SerialReader reader, StopTriggerReadPlan rp,
			String title, TagFilter filter, boolean random) {
		Random r = new Random();
		long total=System.nanoTime();
		long invTime=0;
		long tmp;
		TagReadData[] tags;
		WriteTag writeTag = new Gen2.WriteTag(new Gen2.TagData(new byte[]{0,0,0,0,0,0,0,0,0,0,0,0}));
		for(int i=0;i<iterations;i++){
			try{
				tmp=System.nanoTime();
				
				rp.Op=writeTag;
				tags = reader.read(1000);
				invTime+=(System.nanoTime()-tmp);
				if(tags.length>0){
					
					byte[] epc = tags[0].getTag().epcBytes();
					byte[] newepc;
					if(random){
						newepc = new byte[epc.length];
						r.nextBytes(newepc);
					} else {
						newepc = ByteUtil.copy(epc);
						ByteUtil.incrementTag(newepc,newepc.length-1);
					}
					writeTag.Epc=new Gen2.TagData(newepc);
					
					if(debug)
						System.out.println("EPC["+HexStringX.printHex(epc)+"] new EPC: "+HexStringX.printHex(newepc));
					
				} else {
					System.out.println("NO TAGS FOUND!!");
				}
			} catch (ReaderException e) {
				e.printStackTrace();
			}
		}
		
		System.out.println();
		System.out.println(title);
		System.out.println("Test rounds["+iterations+"] inventory time "+invTime/(1000*iterations)+" us/round");
		System.out.println("Test rounds["+iterations+"] in "+(System.nanoTime()-total)/(1000*iterations)+" us/round");
		
	}

	private void testWriteWord(ADRD_150_Series_SerialReader reader,
			String title, Bank bank, int wordOffset, short[] data, TagFilter filter) {
		
		long total=System.nanoTime();
		long invTime=0;
		long writeTime=0;
		long tmp;
		TagReadData[] tags;
		WriteData writeData = new Gen2.WriteData(bank, wordOffset, data);
		for(int i=0;i<iterations;i++){
			try{
				tmp=System.nanoTime();
				tags = reader.read(1000);
				invTime+=(System.nanoTime()-tmp);
				if(tags.length>0){
					
					byte[] epc = tags[0].getTag().epcBytes();
					byte[] newepc = ByteUtil.copy(epc);
					
					ByteUtil.incrementTag(newepc,newepc.length-1);
					tmp=System.nanoTime();
					for(int j=0;j<data.length;j++){
						data[j]++;
					}
		
					reader.executeTagOp(writeData, filter);
					writeTime+=(System.nanoTime()-tmp);
					if(debug)
						System.out.println("EPC["+HexStringX.printHex(epc)+"] new EPC: "+HexStringX.printHex(newepc));
					
				} else {
					System.out.println("NO TAGS FOUND!!");
				}
			} catch (ReaderException e) {
				e.printStackTrace();
			}
		}
		
		System.out.println();
		System.out.println(title);
		System.out.println("Test rounds["+iterations+"] inventory time "+invTime/(1000*iterations)+" us/round");
		System.out.println("Test rounds["+iterations+"] write time "+writeTime/(1000*iterations)+" us/round");
		System.out.println("Test rounds["+iterations+"] in "+(System.nanoTime()-total)/(1000*iterations)+" us/round");
		
	}

	@Override
	public void tagRead(Reader r, TagReadData t) {
		final long now = System.currentTimeMillis();
		String epc=HexStringX.printHex(t.getTag().epcBytes());
		
		if(debug){
			System.out.println("epc["+HexStringX.printHex(t.getTag().epcBytes())+"] antenna["+t.getAntenna()+"] rssi["+t.getRssi()+" dBm]");
		}
	}
}
