import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public class TestHttpServerLib implements Runnable {
	
	
	private ServerSocket requestListener;
	private Map<String, Object> responseContext;
	private boolean isStop;
	private Thread runningThread;
	private ExecutorService threadPool = Executors.newFixedThreadPool(10);
	private Map<String, Integer> threadLockCheckMap;
	
	/**
	 * constructor
	 * */
	public TestHttpServerLib() throws IOException {
		this.responseContext = new HashMap<>();
		this.threadLockCheckMap = new HashMap<>();
		this.isStop = false;
		this.threadLockCheckMap.put("readCounter", 0);
		this.threadLockCheckMap.put("writeCounter", 0);
		this.threadLockCheckMap.put("writeRequestCounter", 0);
	}
	
	
	/*
	 * START POINT implementation for COMP445 assignment 3, implementing UDP protocol in reliable ARQ pattern
	 * */
	public static void receiveUDP(int listenPort) throws IOException {
		/*
		 * define local variables
		 * */
		final int WINDOW_SIZE = 3;
		final String RESPONSE_PAYLOAD = "Received!";
		int numberOfPackets = Integer.MAX_VALUE;
		byte[] payloadBytes = new byte[10240];
		byte[] responsePayloadByte = RESPONSE_PAYLOAD.getBytes();
		Map<Integer, Packet> packetBufferMap = new HashMap<>();
		Map<Integer, Integer> segementRCVMarker = new HashMap<>();
		
		/*
		 * setup nio server listening context and define nio byte buffer 
		 * */
		DatagramChannel channel = DatagramChannel.open();
		channel.bind(new InetSocketAddress(listenPort));
		ByteBuffer buf = ByteBuffer.allocate(Packet.MAX_LEN).order(ByteOrder.BIG_ENDIAN);
		
		/*
		 * define continuous packets receiving (loop) control variables
		 * */
		Long expectedSequenceNumber = 0L;
		int expectBase = 0;
		int countDelivered = 0;
		while(countDelivered < numberOfPackets) {
			buf.clear();
			SocketAddress routerAddr = channel.receive(buf);
			buf.flip();
            Packet packet = Packet.fromBuffer(buf);
            buf.flip();
            
            /*
             * Description of main logic:
             * if incoming packet is for handshaking (getType return value is 0) and 
             * is the first handshaking packet from client (the second handshaking packet 
             * only contains a useless payload - 'Talk Confirmed'), invoke handshakeHandler()
             * method to handle hand shake issue with requesting client host and update
             * the value of numberOfPackets by retriving actual packet number from handshaking
             * packet coming from client.
             *  
             * */
            if(packet.getType()==0) {
            	if(!"Talk Confirmed".equals(new String(packet.getPayload(), StandardCharsets.UTF_8))) {
            		TestHttpServerLib.handshakeHandler(packet, channel, routerAddr);
                	numberOfPackets = (int) Integer.valueOf(new String(packet.getPayload(), StandardCharsets.UTF_8));
            	}
            }
            else {
            	/*
            	 * if incoming packet is data packet (type of packet is '2'), determine the current packet number by dividing
            	 * the sequenceNumber of current packet by 1013 the maximum bytes of payload.
            	 * */
            	int packetNumber = ((int) packet.getSequenceNumber()) / 1013;
            	/*
            	 * if the current packet hasn't been handled before (no matching record in the segementRCVMarker map),
            	 * start to handle the packet.
            	 * */
                if(segementRCVMarker.get(packetNumber)==null) {
                	/*
                	 * if received packet number equal to expected packet number
                	 * */
                	if(packetNumber==expectBase) {
                		/*
                		 * copy the payload from received packet to the deliverable bytes array from the retrived sequence
                		 * number by 1013 bytes.build ACK packet containing new excepted sequence number and send it back 
                		 * to the client. and increment while loop pointer - 'countDelivered', receiving window lower bound pointer -
                		 * 'expectBase', ACK sequence number - 'expectedSequenceNumber' and mark current packet by updating 
                		 * segementRCVMarker map
                		 * */
                		System.arraycopy(packet.getPayload(), 0, payloadBytes, (int) packet.getSequenceNumber(), 1013);
                    	/*send ack to sender*/
                		
                		
                    	expectBase++;
                    	expectedSequenceNumber = packet.getSequenceNumber() + 1013;
                    	countDelivered++;
                    	segementRCVMarker.put(packetNumber, 1);
                    	
                    	/*
                    	 * traverse receiving buffer to lookup sequential packets that have been previously stored in buffer - 'packetBufferMap',
                    	 * if sequential packets found, copy the payload bytes from those packets from the map to the deliverable bytes array
                    	 * ,increment loop control pointer and receiving window start pointer, update next expected sequence number 
                    	 * */
                    	int n = 0;
                    	while(n<(WINDOW_SIZE - 1)) {
                    		if(packetBufferMap.get(expectBase)!=null) {
                    			Packet packetBuffered = packetBufferMap.get(expectBase);
                    			System.arraycopy(packetBuffered.getPayload(), 0, payloadBytes, (int) packetBuffered.getSequenceNumber(), 1013);
                    			countDelivered++;
                    			expectBase++;
                    			expectedSequenceNumber = packetBuffered.getSequenceNumber() + 1013;
                    			n++;
                			}
                			else {
                				/*
                				 * if no previously stored sequential packet, stop looking up buffer map by breaking the while loop. 
                				 * */
                				break;
                			}
                    	}
                    	
                    	/*
                    	 * after traversing/breaking buffer map, build a packet containing the latest expected sequence number and send it back to the client.
                    	 * */
                    	Packet ackBufferedPacket = new Packet.Builder()
    							.setType(2)
    							.setSequenceNumber(expectedSequenceNumber)
    							.setPeerAddress(packet.getPeerAddress())
    							.setPortNumber(packet.getPeerPort())
    							.setPayload(responsePayloadByte)
    							.create();
                    	channel.send(ackBufferedPacket.toBuffer(), routerAddr);
                    }
                    else if(packetNumber > expectBase && packetNumber < expectBase + WINDOW_SIZE) {
                    	/*
                    	 * if the current packet is not expected one but in the windows range of the receiver, insert the packet
                    	 * into the buffer map, and build a packet containing expected sequence and send it back to the client
                    	 * keeping asking for the expected but not yet received packet.
                    	 * */
                    	packetBufferMap.put(packetNumber, packet);
                    	segementRCVMarker.put(packetNumber, 1);
                    	Packet ackPacket = new Packet.Builder()
    							.setType(2)
    							.setSequenceNumber(expectBase * 1013)
    							.setPeerAddress(packet.getPeerAddress())
    							.setPortNumber(packet.getPeerPort())
    							.setPayload(responsePayloadByte)
    							.create();
                		channel.send(ackPacket.toBuffer(), routerAddr);
                    }
                    else {
                    	/*
                    	 * if the current packet is neither the expected packet nor in the window range of receiver, build a packet containing
                    	 * the expected but not yet received packet and send it back to the client keeping asking the expected packet.
                    	 * */
                    	Packet ackPacket = new Packet.Builder()
    							.setType(2)
    							.setSequenceNumber(expectBase * 1013)
    							.setPeerAddress(packet.getPeerAddress())
    							.setPortNumber(packet.getPeerPort())
    							.setPayload(responsePayloadByte)
    							.create();
                    	
                		channel.send(ackPacket.toBuffer(), routerAddr);
                    }
                }
                else {
                	/*
                	 * if the packet has already been handled before, send a packet back to client asking
                	 * for the packet containing expected sequence number.
                	 * */
                	Packet ackPacket = new Packet.Builder()
    						.setType(2)
    						.setSequenceNumber(expectedSequenceNumber)
    						.setPeerAddress(packet.getPeerAddress())
    						.setPortNumber(packet.getPeerPort())
    						.setPayload(responsePayloadByte)
    						.create();
            		channel.send(ackPacket.toBuffer(), routerAddr);
                }
            }
		}
		/*
		 * after receiving all packets from client, retrive all delivered packet bytes from byte array to a string, and print out
		 * the string as the final output of the payload sent by client. 
		 * */
		String payload = new String(payloadBytes, StandardCharsets.UTF_8);
		File file = new File(System.getProperty("user.dir") + "/UDPtest.txt");
		BufferedWriter bfw = new BufferedWriter(new FileWriter(file,true));
		bfw.write(payload);
		bfw.close();
		System.out.println("The final payload sent by client is displayed as follow:" );
		System.out.println(payload);
		channel.close();
	}
	
	/*
	 * implementation for COMP445 assignment 3, method for handling handshake request from client
	 * */
	public static void handshakeHandler(Packet requestPacket, DatagramChannel channel, SocketAddress routerAddr) throws IOException {
		/*
		 * create a packet containing expected response payload info - 'OK' and send it back to the source client as a notice of an 
		 * available server for establishing the connection.
		 * */
		String responsePayload = "OK";
		Packet packet = new Packet.Builder()
				.setType(0)
				.setSequenceNumber(0L)
				.setPeerAddress(requestPacket.getPeerAddress())
				.setPortNumber(requestPacket.getPeerPort())
				.setPayload(responsePayload.getBytes())
				.create();
		channel.send(packet.toBuffer(), routerAddr);
	}
	
	/*
	 * implementation for COMP445 assignment 3, main method for starting server side process
	 * */
	public static void main(String[] args) {
		int port = 8007;
		try {
			System.out.println("The server is now listening to the port " + port);
			TestHttpServerLib.receiveUDP(port);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	/*
	 * END POINT implementation for COMP445 assignment 3, implementing UDP protocol in reliable ARQ pattern
	 * */


	public void run() {
		this.runningThread = Thread.currentThread();
		startService();
		this.responseContext.put("currentThread", this.runningThread);
		this.responseContext.put("currentConnector", this);
		while(!this.isStopped()) {
			Socket linkedSocket = null;
			try {
				linkedSocket = this.requestListener.accept();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				if(this.isStopped()) {
					System.err.println("Server side has been terminated!");
				}
				else {
					throw new RuntimeException("Error accepting client connection", e);
				}
			}
			this.threadPool.execute(new WorkerRunnable(linkedSocket ,this.responseContext));
		}
	}
	
	/**
	 * Mutators
	 * */
	
	public Map<String, Object> getResponseContext() {
		return responseContext;
	}

	public void setResponseContext(Map<String, Object> responseContext) {
		this.responseContext = responseContext;
	}
	
	public Map<String, Integer> getThreadLockCheckMap() {
		return threadLockCheckMap;
	}


	public void setThreadLockCheckMap(Map<String, Integer> threadLockCheckMap) {
		this.threadLockCheckMap = threadLockCheckMap;
	}
	
	/**
	 * Helpers
	 * */
	public void startService(){
		int port = Integer.parseInt((String) this.responseContext.get("port"));
		try {
			this.requestListener = new ServerSocket(port);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	public synchronized void stopService(){
		this.isStop = true;
		try {
			this.requestListener.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
	}
	
	private boolean isStopped() {
		return this.isStop;
	}
	
	/**
	 * Inner class as request handler
	 * */
	private class WorkerRunnable implements Runnable {
		private final String _CLIENT_REQUEST_TYPE_REGEX = "^(GET|POST)\\s(/)[\\S]*\\s(HTTP/)([0-9](.)[0-9])$";
		private final String _SERVER_RESPONSE_CHECK_FILE_LIST_REGEX = "^(/){1}$";
		private final String _SERVER_RESPONSE_CHECK_FILE_HANDLE_REGEX = "^(/)[\\S]+";
		private final String _SERVER_RESPONSE_CHECK_POST_DATA_REGEX = "^(\\{\")(\\S)+(\":)(\\s)*(\\S)+(\\})$";
		private final String _SERVER_RESPONSE_CHECK_POST_FILE_REGEX = "(\\S)+(.txt)$";
		private Map<String, Object> responseContext;
		private Socket runningSocket;
		
		
		/**
		 * Constructor
		 * */
		public WorkerRunnable(Socket runningSocket,Map<String, Object> responseContext) {
			this.runningSocket = runningSocket;
			this.responseContext = responseContext;
		}
		
		@Override
		public void run() {
			try {
				this.clientRequestHandler();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
		/**
		 * Main function
		 * */
		public void clientRequestHandler() throws IOException, InterruptedException {
			BufferedReader br = new BufferedReader(new InputStreamReader(this.runningSocket.getInputStream()));
			BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(this.runningSocket.getOutputStream()));
			String line = "";
			String responseHeader = "";
			String responseBody = "";
			boolean postDataFlag = false;
			boolean postLineCheckFlag = false;
			
			while((line = br.readLine())!=null){
				if(this.parseClientRequest(line) || postLineCheckFlag) {
					String filePath = ((String) this.responseContext.get("PATH")).replace("/","\\");
					File file = new File(this.responseContext.get("dir") + filePath);
					if("GET".equals(this.responseContext.get("TYPE"))){
						if(Pattern.matches(this._SERVER_RESPONSE_CHECK_FILE_LIST_REGEX, ((String) this.responseContext.get("PATH")))) {
							if(!file.exists()) {
								if("open".equals(this.responseContext.get("debug mode"))) {
									responseHeader = "HTTP/1.1 404 Not Found\r\n"
											+ "Content-Type: text/plain\r\n\r\n";
									bw.write(responseHeader);
								}
								responseBody = "404 Not Found - The Requested Working directory doesn't exist." + System.getProperty("line.separator");
								bw.write(responseBody);
								break;
							}
							else {
								File[] files = file.listFiles();
								String fileList = "";
								
								if("open".equals(this.responseContext.get("debug mode"))) {
									responseHeader = "HTTP/1.1 200 OK\r\n"
											+ "Content-Type: text/plain\r\n\r\n";
									bw.write(responseHeader);
								}
								
								
								for(File fileItem : files) {
									fileList += fileItem.getPath() + System.getProperty("line.separator");
								}
								responseBody = "The Working directory has the following files:" + fileList + System.getProperty("line.separator");
								bw.write(responseBody);
								break;
							}
						}
						else if(Pattern.matches(this._SERVER_RESPONSE_CHECK_FILE_HANDLE_REGEX, ((String) this.responseContext.get("PATH")))) {
							if(!(file.exists() && file.isFile())) {
								if("open".equals(this.responseContext.get("debug mode"))) {
									responseHeader = "HTTP/1.1 404 Not Found\r\n"
											+ "Content-Type: text/plain\r\n\r\n";
									bw.write(responseHeader);
								}
								responseBody = "404 Not Found - the requested file doesn't exist." + System.getProperty("line.separator");
								bw.write(responseBody);
								break;
							}
							else{
								/*
								 * read lock for multi thread reading
								 * */
								this.lockRead();
								if("open".equals(this.responseContext.get("debug mode"))) {
									responseHeader = "HTTP/1.1 200 OK\r\n"
											+ "Content-Type: text/plain\r\n"
											+ "Content-Disposition: attachment; filename=" + file.getName() + "\r\n\r\n";
									bw.write(responseHeader);
								}
								
								BufferedReader bfr = new BufferedReader(new FileReader(file));
								String lineInFile = null;
								String dataInFile = "";
								while((lineInFile=bfr.readLine())!=null) {
									dataInFile += lineInFile + System.getProperty("line.separator");
								}
								responseBody = "The data of " + file.getName() + " are listed below:" + dataInFile + System.getProperty("line.separator");
								bw.write(responseBody);
								bfr.close();
								/*
								 * read unlock for multi thread reading
								 * */
								this.unlockRead();
								break;
							}
						}
					}
					else if("POST".equals(this.responseContext.get("TYPE"))){
						if(Pattern.matches(this._SERVER_RESPONSE_CHECK_FILE_HANDLE_REGEX, ((String) this.responseContext.get("PATH")))) {
							File parentFolder = new File(file.getParent());
							if(!Pattern.matches(this._SERVER_RESPONSE_CHECK_POST_FILE_REGEX, file.getName()) || !parentFolder.exists()) {
								if("open".equals(this.responseContext.get("debug mode"))) {
									responseHeader = "HTTP/1¡£1 400 Bad Request\r\n"
											+ "Content-Type: text/plain\r\n\r\n";
									bw.write(responseHeader);
								}
								
								responseBody = "400 Bad Request - The requested file " + file.getName() + " is not a valid file name." + System.getProperty("line.separator");
								bw.write(responseBody);
								break;
							}
							else {
								postLineCheckFlag = true;
								if(Pattern.matches(this._SERVER_RESPONSE_CHECK_POST_DATA_REGEX, line)) {
									/*
									 * write lock for multi thread writing
									 * */
									this.lockWrite();
									if("open".equals(this.responseContext.get("debug mode"))) {
										responseHeader = "HTTP/1.1 200 OK\r\n"
												+ "Content-Type: text/plain\r\n\r\n";
										bw.write(responseHeader);
									}
									BufferedWriter bfw = new BufferedWriter(new FileWriter(file,false));
									bfw.write(line);
									bfw.close();
									postDataFlag = true;
									responseBody = "The data in post request body has been written into " + file.getAbsolutePath() + System.getProperty("line.separator");
									bw.write(responseBody);
									/*
									 * write unlock for multi thread writing
									 * */
									this.unlockWrite();
									break;
								}
							}
						}
						else {
							if("open".equals(this.responseContext.get("debug mode"))) {
								responseHeader = "HTTP/1.1 400 Bad Request\r\n"
										+ "Content-Type: text/plain\r\n\r\n";
								bw.write(responseHeader);
							}
							responseBody = "400 Bad Request -The requested Path is not valid." + System.getProperty("line.separator");
							bw.write(responseBody);
							break;
						}
					}
					else {
						if("open".equals(this.responseContext.get("debug mode"))) {
							responseHeader = "HTTP/1.1 400 Bad Request\r\n"
									+ "Content-Type: text/plain\r\n\r\n";
							bw.write(responseHeader);
						}
						responseBody = "400 Bad Request -The requested Path is not valid." + System.getProperty("line.separator");
						bw.write(responseBody);
						break;
					}
				}
			}
			
			if(!postDataFlag && "POST".equals(this.responseContext.get("TYPE"))){
				if("open".equals(this.responseContext.get("debug mode"))) {
					responseHeader = "HTTP/1¡£1 400 Bad Request\r\n"
							+ "Content-Type: text/plain\r\n\r\n";
					bw.write(responseHeader);
				}
				
				responseBody = "400 Bad Request -The requested data are not found" + System.getProperty("line.separator");
				responseHeader = "Connection: Closed";
				bw.write(responseHeader);
		        bw.write(responseBody);
			}
			if("open".equals(this.responseContext.get("debug mode"))) {
				responseHeader = "Connection: Closed";
				bw.write(responseHeader);
			}
	        bw.flush();
			bw.close();
			br.close();
		}
		
		/**
		 * Helpers
		 * */
		private boolean parseClientRequest(String line) {
			if(Pattern.matches(this._CLIENT_REQUEST_TYPE_REGEX, line)) {
				String[] clientRequestHeaderArr = line.split("\\s");
				this.responseContext.put("TYPE", clientRequestHeaderArr[0]);
				this.responseContext.put("PATH", clientRequestHeaderArr[1]);
				return true;
			}
			return false;
		}
		
		private synchronized void lockRead() throws InterruptedException {
			while(((TestHttpServerLib) this.responseContext.get("currentConnector")).getThreadLockCheckMap().get("writeCounter")>0 || ((TestHttpServerLib) this.responseContext.get("currentConnector")).getThreadLockCheckMap().get("writeRequestCounter")>0) {
				this.wait();
			}
			int tempCounter = ((TestHttpServerLib) this.responseContext.get("currentConnector")).getThreadLockCheckMap().get("readCounter") + 1;
			((TestHttpServerLib) this.responseContext.get("currentConnector")).getThreadLockCheckMap().put("readCounter", tempCounter);
		}
		
		private synchronized void unlockRead() throws InterruptedException {
			int tempCounter = ((TestHttpServerLib) this.responseContext.get("currentConnector")).getThreadLockCheckMap().get("readCounter") - 1;
			((TestHttpServerLib) this.responseContext.get("currentConnector")).getThreadLockCheckMap().put("readCounter", tempCounter);
			this.notifyAll();
		}
		
		private synchronized void lockWrite() throws InterruptedException {
			int tempCounter = ((TestHttpServerLib) this.responseContext.get("currentConnector")).getThreadLockCheckMap().get("writeRequestCounter") + 1;
			((TestHttpServerLib) this.responseContext.get("currentConnector")).getThreadLockCheckMap().put("writeRequestCounter", tempCounter);
			while(((TestHttpServerLib) this.responseContext.get("currentConnector")).getThreadLockCheckMap().get("readCounter")>0 || ((TestHttpServerLib) this.responseContext.get("currentConnector")).getThreadLockCheckMap().get("writeCounter")>0){
				this.wait();
			}
			tempCounter = ((TestHttpServerLib) this.responseContext.get("currentConnector")).getThreadLockCheckMap().get("writeRequestCounter") - 1;
			((TestHttpServerLib) this.responseContext.get("currentConnector")).getThreadLockCheckMap().put("writeRequestCounter", tempCounter);
			tempCounter = ((TestHttpServerLib) this.responseContext.get("currentConnector")).getThreadLockCheckMap().get("writeCounter") + 1;
			((TestHttpServerLib) this.responseContext.get("currentConnector")).getThreadLockCheckMap().put("writeCounter", tempCounter);
		}
		
		private synchronized void unlockWrite() throws InterruptedException {
			int tempCounter = ((TestHttpServerLib) this.responseContext.get("currentConnector")).getThreadLockCheckMap().get("writeCounter") - 1;
			((TestHttpServerLib) this.responseContext.get("currentConnector")).getThreadLockCheckMap().put("writeCounter", tempCounter);
			this.notifyAll();
		}
	}
}
