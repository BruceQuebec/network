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
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
		data = String.join("&", postBodyParams);
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
