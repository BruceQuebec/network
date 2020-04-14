import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static java.nio.channels.SelectionKey.OP_READ;

public class TestHttpClientLib {
	private final String _SERVER_RESPONSE_HEADER_REGEX = "((^(HTTP/)|^(Date:))|^(Content-Type:)|^(Content-Length:)|^(Connection:)|^(Server:)|^(Access-Control-Allow-Origin:)|^(Access-Control-Allow-Credentials:)|^(x-more-info:)|^(Last-Modified:)|^(ETag:)|^(Accept-Ranges:)|^(Keep-Alive:)|^(Location:))";
	private final String _VERBOSE_MODE_IDENTIFIER = "verbose";
	private final String _SERVER_RESPONSE_REDIRECT_CODE = "^(HTTP/)1(.)[0-1](\\s)(301)(\\s)(MOVED\\sPERMANENTLY)$";
	private final String _SERVER_RESPONSE_REDIRECT_LOCATION_PREFIX = "^(Location:\\s)";
	private final String _CLIENT_REQUEST_VERSION = "1.0";
	private Map<String,Object> context;
	private Map<String, Object> requestHeaders;
	private Map<String, Object> requestBody;
	private Socket socket;
	private BufferedReader bufferedReader;
	private BufferedWriter bufferedWriter;

	/**
	 * constructor
	 * */
	public TestHttpClientLib() throws MalformedURLException {
		this.context = new HashMap<>();
		this.requestBody = new HashMap<>();
		this.requestHeaders = new HashMap<>();
	}
	
	/*
	 * START POINT implementation for COMP445 assignment 3, method for implementing UDP protocol in reliable ARQ pattern
	 * */
	public static void sendUDP(SocketAddress routerAddr, InetSocketAddress peerAddr) throws IOException {
		/*
		 * initialize test payload and determine the packet number to prepare transmit
		 * */
		String payload = TestHttpClientLib.createDataSize(20);
		byte[] payloadBytes = payload.getBytes();
		int numberOfPackets = (int) Math.ceil (payloadBytes.length / 1013);
		
		/*
		 * setup UDP transmit context
		 * */
		DatagramChannel channel = DatagramChannel.open();
		channel.configureBlocking(false);
		Selector selector = Selector.open();
		channel.register(selector, OP_READ);
		ByteBuffer responseBuf = ByteBuffer.allocate(Packet.MAX_LEN);
		
		/*
		 * make a handshake through handshakeUDP() to send handshake request and packet number to target server
		 * */
		if (!TestHttpClientLib.handshakeUDP(routerAddr, peerAddr, channel, selector, numberOfPackets, responseBuf)) {
			System.err.println("Handshake failed!");
		}
		else {
			/*
			 * if handshake succeeds, start packets transmitting process
			 * initialize the local variables
			 * */
			final int WINDOW_SIZE = 3;
			final int PAYLOAD_SIZE_PER_PACKET = 1013;
			byte[] payloadSegment = new byte[PAYLOAD_SIZE_PER_PACKET];
			Packet[] packets = new Packet[numberOfPackets];
			Map<Integer, Integer> segementACKMarker = new HashMap<>();
			Map<Integer, Long> segementTimer = new HashMap<>();
			
			/*
			 * create packets to transmit and store and initialize the ACKMarker and segementTimer for each packet
			 * */
			System.out.println("Be patient, the client is now communicating with server.");
			for (int i=0; i<numberOfPackets; i++) {
				Arrays.fill(payloadSegment, (byte)0);
				int startPointer = i*PAYLOAD_SIZE_PER_PACKET;
				System.arraycopy(payloadBytes, startPointer, payloadSegment, 0, PAYLOAD_SIZE_PER_PACKET);
				segementACKMarker.put(i, 0);
				segementTimer.put(i, 0L);
				
				packets[i] = new Packet.Builder()
						.setType(1)
						.setSequenceNumber(startPointer)
						.setPeerAddress(peerAddr.getAddress())
						.setPortNumber(peerAddr.getPort())
						.setPayload(payloadSegment)
						.create();
			}

			/*
			 * start transmitting packet 
			 * */
			int sendBase = 0;
			int countACK = 0;
			while(countACK < numberOfPackets) {
				/*
				 * determine the send window according to the current sendBase (the lower bound of the window)
				 * */
				int sendEnd = sendBase + WINDOW_SIZE < numberOfPackets ? (sendBase + WINDOW_SIZE) : numberOfPackets;
				for (int i=sendBase ; i<sendEnd ; i++) {
					Long currentTime = System.currentTimeMillis();
					/*
					 * if the current packet didn't receive ACK before (segementACKMarker is marked 0) and the timer for this packet
					 * has exceeded the limit (timeout limit : 5000 ms), send the packet and restart timer for the packet.
					 * */
					if((segementACKMarker.get(i) == 0 && segementTimer.get(i)==0L) || (segementACKMarker.get(i) == 0 && (currentTime - segementTimer.get(i))>=5000)) {
						System.out.println("The packet No." + (i+1) + " is now being transmitted.");
						channel.send(packets[i].toBuffer(), routerAddr);
						segementTimer.put(i, currentTime);
					}
				}
				/*
				 * wait for 2000 ms before trying to retrive datagram from channel 
				 * */
				selector.select(2000);
				Set<SelectionKey> resKeys = selector.selectedKeys();
				if(!resKeys.isEmpty()) {
					/*
					 * if the channel is not empty, obtain the data packet from channel, clear buffer, store data in the buffer
					 * and transform the buffer into packet.
					 * */
					responseBuf.clear();
					channel.receive(responseBuf);
					responseBuf.flip();
					if(responseBuf.limit()>responseBuf.position()) {
						Packet responsePacket = Packet.fromBuffer(responseBuf);
						responseBuf.flip();
						
						if(responsePacket.getType()==2) {
							/* determine the packet number from received sequence number and if the packet is 10 which means all
							 * packets have been received by server, so break the while loop and terminate client.*/
							int packetNumberACK = (int) (responsePacket.getSequenceNumber() / 1013);
							if(packetNumberACK >= 10) {
								System.out.println("The last packet has been recieved by the server.");
								break;
							}
							/*
							 * if packet number is greater than current send base, which means all packets before the currently 
							 * ACK packet have been received, so increment send base and update segmentACKMarker map so that those
							 * packets will not be send again.
							 * */
							if (packetNumberACK > sendBase) {
								for(int i=0; i< (packetNumberACK-sendBase); i++) {
									countACK++;
									sendBase++;
									segementACKMarker.put(sendBase , 1);
								}
							}
						}
					}
				}
				System.out.println((countACK + 1) + " packets have been recieved by server, now the packet No. " + (countACK + 2) + " is waiting to be transmitted.");
			}
			System.out.println("All packets have been successfully transmitted!");
			selector.close();
			channel.close();
		}
	}
	
	/*
	 * implementation for COMP445 assignment 3, method to make a handshake request
	 * */
	private static boolean handshakeUDP(SocketAddress routerAddr, InetSocketAddress peerAddr,DatagramChannel channel, Selector selector, int numberOfPackets, ByteBuffer responseBuf) throws IOException {
		/*setup UDP handshake*/
		final String HANDSHAKE_REQUEST = String.valueOf(numberOfPackets);
		final String HANDSHAKE_CONFIRM = "Talk Confirmed";
		final String HANDSHAKE_EXPECTED_RESPONSE = "OK";
		
		/*
		 * build a packet containing packet number to be transmitted and send it to server.
		 * */
		Packet packet = new Packet.Builder()
				.setType(0)
				.setSequenceNumber(0L)
				.setPeerAddress(peerAddr.getAddress())
				.setPortNumber(peerAddr.getPort())
				.setPayload(HANDSHAKE_REQUEST.getBytes())
				.create();
		channel.send(packet.toBuffer(), routerAddr);
		
		/*
		 * wait 5 seconds and try to get datagram from channel.
		 * */
		selector.select(5000);
		Set<SelectionKey> keys = selector.selectedKeys();
		if(keys.isEmpty()) {
			return false;
		}
		else {
			responseBuf.clear();
			channel.receive(responseBuf);
			responseBuf.flip();
			Packet responsePacket = Packet.fromBuffer(responseBuf);
			responseBuf.flip();
			
			/*
			 * if expected response - 'OK' is received, which means hand shake succeeds, build second packet
			 * to confirm the hand shake and send it to server and return true otherwise return false.
			 * */
			String response = new String(responsePacket.getPayload(), StandardCharsets.UTF_8);
			if (responsePacket.getType() == 0 && HANDSHAKE_EXPECTED_RESPONSE.equals(response)) {
				Packet packet_1 = new Packet.Builder()
						.setType(0)
						.setSequenceNumber(0L)
						.setPeerAddress(peerAddr.getAddress())
						.setPortNumber(peerAddr.getPort())
						.setPayload(HANDSHAKE_CONFIRM.getBytes())
						.create();
				channel.send(packet_1.toBuffer(), routerAddr);
				return true;
			}
			return false;
		}
	}
	
	/*
	 * implementation for COMP445 assignment 3, method for creating a dummy payload to send to the server
	 * */
	public static String createDataSize(int msgSize) {
		// Java chars are 2 bytes
		msgSize = msgSize/2;
		msgSize = msgSize * 1024;
		StringBuilder sb = new StringBuilder(msgSize);
		for (int i=0; i<msgSize; i++) {
			if(i % 100 !=0) {
				sb.append('a');
			}
			else {
				sb.append('\n');
			}
		}
		return sb.toString();
	}
	
	/*
	 * implementation for COMP445 assignment 3, main method to start client side process
	 * */
	public static void main(String[] args) {
		SocketAddress routerAddr = new InetSocketAddress("localhost", 3000);
        InetSocketAddress peerAddr = new InetSocketAddress("localhost", 8007);
        
        try {
			TestHttpClientLib.sendUDP(routerAddr, peerAddr);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	/*
	 * END POINT implementation for COMP445 assignment 3, method for implementing UDP protocol in reliable ARQ pattern
	 * */
	
	
	/**
	 * mutators
	 * */
	public Map<String, Object> getContext() {
		return context;
	}


	public void setContext(Map<String, Object> context) {
		this.context = context;
	}


	public Map<String, Object> getRequestHeaders() {
		return requestHeaders;
	}


	public void setRequestHeaders(Map<String, Object> requestHeaders) {
		this.requestHeaders = requestHeaders;
	}


	public Map<String, Object> getRequestBody() {
		return requestBody;
	}


	public void setRequestBody(Map<String, Object> requestBody) {
		this.requestBody = requestBody;
	}


	public Socket getSocket() {
		return socket;
	}


	public void setSocket(Socket socket) {
		this.socket = socket;
	}

	/**
	 * main function methods
	 * */
	public void get() throws IOException {
		this.buildConnection();
		String requestContent = this.wrapRequest(true, 0);
		this.writeToServer(requestContent, "");
		String serverResponse = this.readFromServer();
		String[] serverResponseArr = serverResponse.split(System.getProperty("line.separator"));
		if(Pattern.matches(this._SERVER_RESPONSE_REDIRECT_CODE, serverResponseArr[0])) {
			String redirectedLocation = serverResponseArr[1].replaceAll(this._SERVER_RESPONSE_REDIRECT_LOCATION_PREFIX, "");
			this.socket.close();
			redirect(redirectedLocation);
		}
		else {
			System.out.println(serverResponse);
			if(this.context.containsKey("fileSaveDir")) {
				this.saveResponseBody(serverResponse , (String) this.context.get("fileSaveDir"));
			}
			this.bufferedWriter.close();
			this.bufferedReader.close();
			this.socket.close();
		}
	}
	
	public void post() throws IOException {
		String data = this.wrapPostData();
		this.buildConnection();
		String requestContent = this.wrapRequest(false, data.length());
		this.writeToServer(requestContent, data);
		String serverResponse = this.readFromServer();
		System.out.println(serverResponse);
		if(this.context.containsKey("fileSaveDir")) {
			this.saveResponseBody(serverResponse , (String) this.context.get("fileSaveDir"));
		}
		this.bufferedWriter.close();
		this.bufferedReader.close();
		this.socket.close();
	}
	
	public void redirect(String directedLocation) throws IOException {
		System.out.println("The request is now being redirected to the following URL: " + directedLocation);
		URL url = new URL(directedLocation);
		this.context.put("host" , url.getHost());
		if(url.getPort()==-1) {
			this.context.put("port", url.getDefaultPort());
		}
		else {
			this.context.put("port", url.getPort());
		}
		this.context.put("port" , url.getDefaultPort());
		this.context.put("path" , url.getPath());
		this.requestHeaders.put("Host",url.getHost());
		Socket newSocket = new Socket();
		this.setSocket(newSocket);
		this.buildConnection();
		String requestContent = this.wrapRequest(true, 0);
		this.writeToServer(requestContent, "");
		String serverResponse = this.readFromServer();
		System.out.println(serverResponse);
		if(this.context.containsKey("fileSaveDir")) {
			this.saveResponseBody(serverResponse , (String) this.context.get("fileSaveDir"));
		}
		this.bufferedWriter.close();
		this.bufferedReader.close();
		this.socket.close();
	}
	
	public void saveResponseBody(String serverResponse, String filePath) throws IOException {
		Matcher matcher;
		BufferedWriter bufferedWriter = new BufferedWriter(new FileWriter(filePath,true));

		String[] serverResponseArray = serverResponse.split(System.getProperty("line.separator"));
		bufferedWriter.write(System.getProperty("line.separator"));
		for(String line : serverResponseArray) {
			matcher = Pattern.compile(this._SERVER_RESPONSE_HEADER_REGEX).matcher(line);
			if(matcher.lookingAt() && this._VERBOSE_MODE_IDENTIFIER.equals((String) this.context.get("output"))) {
				bufferedWriter.write(line + System.getProperty("line.separator"));
			}
			if(!matcher.lookingAt()) {
				bufferedWriter.write(line + System.getProperty("line.separator"));
			}
		}
		bufferedWriter.flush();
		bufferedWriter.close();
		System.out.println("The response of server has been saved in " + filePath);
	}
	
	/**
	 * helpers
	 *
	 * */
	private void buildConnection() throws IOException {
		this.socket = new Socket();
		SocketAddress destination = new InetSocketAddress((String) this.context.get("host"), (int) this.context.get("port"));
		this.socket.connect(destination);
		System.out.println("post is: " + this.context.get("port"));
		OutputStreamWriter streamWriter = new OutputStreamWriter(this.socket.getOutputStream());
		this.bufferedWriter = new BufferedWriter(streamWriter);
	}
	
	private String wrapPostData() throws UnsupportedEncodingException {
		String data = "";
		String postBodyParam = "";
		int count = 0;
		String[] postBodyParams = new String[this.requestBody.size()];
		for(String key : this.requestBody.keySet()) {
			postBodyParam =  "{\"" + URLEncoder.encode(key, "utf-8") + "\": " + URLEncoder.encode(((String) this.requestBody.get(key)),"utf-8") + "}";
			postBodyParams[count] = postBodyParam;
			count++;
		}
		data = String.join("&", postBodyParams)+System.getProperty("line.separator");
		return data;
	}
	
	private String wrapRequest(boolean isGetRequest, int postDataLength) {
		String requestPath = ((String) this.context.get("path")).trim().replace("\\s", "");
		
		if(requestPath!="") {
			if(isGetRequest && this.context.get("query")!=null) {
				requestPath = requestPath + "?" + (String) this.context.get("query");
			}
		}
		else {
			requestPath = "/";
		}
		
		String requestMethodStr = isGetRequest ? "GET " : "POST ";
		String requestContent = requestMethodStr + requestPath + " HTTP/" + this._CLIENT_REQUEST_VERSION + System.getProperty("line.separator");
		if(!isGetRequest) {
			requestContent += "Content-Length:" + postDataLength + System.getProperty("line.separator");
		}
		for(String key: this.requestHeaders.keySet()) {
			requestContent += key + ":" + (String) this.requestHeaders.get(key) + System.getProperty("line.separator");
		}
		requestContent = requestContent + "\r\n";
		return requestContent;
	}
	
	private void writeToServer(String requestContent, String data) throws IOException {
		this.bufferedWriter.write(requestContent);
		if(data.length()>0) {
			bufferedWriter.write(data);
		}
		bufferedWriter.flush();
	}
	
	private String readFromServer() throws IOException {
		BufferedInputStream streamReader = new BufferedInputStream(this.socket.getInputStream());
		this.bufferedReader = new BufferedReader(new InputStreamReader(streamReader, "utf-8"));
		String line = null;
		String serverResponse = "";
		String redirectResponse = "";
		Matcher matcher, redirectedLocationMatcher;
		while((line = bufferedReader.readLine())!=null) {
			redirectedLocationMatcher = Pattern.compile(this._SERVER_RESPONSE_REDIRECT_LOCATION_PREFIX).matcher(line);
			if("GET".equals(this.context.get("method")) && (Pattern.matches(this._SERVER_RESPONSE_REDIRECT_CODE, line) || redirectedLocationMatcher.lookingAt())) {
				redirectResponse = redirectResponse + line + System.getProperty("line.separator");
				
			}
			matcher = Pattern.compile(this._SERVER_RESPONSE_HEADER_REGEX).matcher(line);
			if(matcher.lookingAt() && this._VERBOSE_MODE_IDENTIFIER.equals((String) this.context.get("output"))) {
				serverResponse = serverResponse + line + System.getProperty("line.separator");
			}
			if(!matcher.lookingAt()) {
				serverResponse = serverResponse + line + System.getProperty("line.separator");
			}
		}
		
		if(redirectResponse.length()>0) {
			return redirectResponse;
		}
		else {
			return serverResponse;
		}
	}
}
