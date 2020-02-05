import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.regex.Pattern;

public class Httpc {
	private final String _URL_MATCH_REGEX = "^('{0,1})(https?|ftp|file)://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]('{0,1})";
	private final String _HEADER_MATCH_REGEX = "([\\w-]+):(.*)";
	private final String _POST_BODY_MATCH_REGEX = "('\\{\\\")([\\w-]+\\\"):(.*)(\\}\\')";
	private final String _REQUEST_METHOD_USR_INPUT_REGEX = "(Get|get|GET|Post|post|POST)";
	private final String _POST_METHOD_USR_INPUT_REGEX = "(Post|post|POST)";
	private final String _GET_METHOD_USR_INPUT_REGEX = "(Get|get|GET)";
	TestHttpClientLib httpContext;
	Map<String, String> commandParams;
	
	/**
	 * constructor
	 * */
	public Httpc(TestHttpClientLib httpContext) {
		this.httpContext = httpContext;
		this.commandParams = new HashMap<String, String>();
	}
	
	/**
	 * mutators
	 * */
	public TestHttpClientLib getHttpContext() {
		return httpContext;
	}

	public void setHttpContext(TestHttpClientLib httpContext) {
		this.httpContext = httpContext;
	}

	public Map<String, String> getCommandParams() {
		return commandParams;
	}

	public void setCommandParams(Map<String, String> commandParams) {
		this.commandParams = commandParams;
	}
	
	/**
	 * functional methods
	 * @throws Exception 
	 * */
	public boolean parseUsrInput(String usrInput) throws Exception {
		boolean parseFlag = true;
		usrInput = usrInput.trim();
		usrInput = usrInput.replaceAll("\\s{2,}", " ");
		String[] usrInputArr = usrInput.split("\\s");
		
		if((usrInputArr==null) || !("httpc".equals(usrInputArr[0]))) {
			System.err.println("Wrong command, use 'httpc' as the command header!\n");
			parseFlag = false;
		}
		else if((usrInputArr.length<2) || (!Pattern.matches(this._REQUEST_METHOD_USR_INPUT_REGEX, usrInputArr[1]))) {
			System.err.print("'post' or 'get' must be used as a necessary argument in the command line!\n");
			parseFlag = false;
		}
		else {
			if(this.validateUsrInput(usrInputArr)) {
				for(int i=0; i<usrInputArr.length; i++) {
					this.populateHttpContext(usrInputArr[i], usrInputArr, i);
				}
			}
			else {
				parseFlag = false;
			}
		}
		return parseFlag;
	}
	
	private boolean validateUsrInput(String[] usrInputArr) throws IOException {
		int validPostFlag = 0;
		int validGetFlag = 0;
		boolean urlFlag = false;
		boolean checkRes = true;
		
		if(Pattern.matches(this._GET_METHOD_USR_INPUT_REGEX, usrInputArr[1])) {
			validGetFlag++;
		}
		else {
			validPostFlag++;
		}
		
		for(int i=0; i<usrInputArr.length; i++) {
			switch(usrInputArr[i]) {
				case "-d":
					if(validGetFlag>0) {
						System.err.println("-d parameter is not supported with GET request!\n");
						checkRes = false;
					}
					else {
						if(validPostFlag<2) {
							 validPostFlag++;
						}
						 else {
							System.err.println("parameter '-f' and '-d' can not be used together with POST request!\n");
							checkRes = false;
						 }
					}
					break;
				case "-f":
					if(validGetFlag>0) {
						System.err.println("-f parameter is not supported with GET request!\n");
						checkRes = false;
					}
					else {
						 if(validPostFlag<2) {
							validPostFlag++;
							String fileDir = usrInputArr[i+1];
							File file = new File(fileDir);
							
							if(file.isFile() && file.exists()) {
								String postFileData = getPostDataFromLocalFile(fileDir);
								if(!Pattern.matches(this._POST_BODY_MATCH_REGEX, postFileData)) {
									System.err.println("The content of the file is not in a valid post data format!\n");
									checkRes = false;
								}
							}
							else {
								System.err.println("file doesn't exist or file path is invalid!\n");
								checkRes = false;
							}
						}
						 else {
							System.err.println("parameter '-f' and '-d' can not be used together with POST request!\n");
							checkRes = false;
						 }
					}
					break;
				case "-h":
					String headerDefinition = usrInputArr[i+1];
					if(!(Pattern.matches(this._HEADER_MATCH_REGEX, headerDefinition))) {
						System.err.println("incorrect header format!\n");
						checkRes = false;
					}
					break;
				case "-o":
					String fileDir = usrInputArr[i+1];
					File file = new File(fileDir);
					if(!file.createNewFile() && !file.exists()) {
						System.err.println("file creation failed or incorrect file path!\n");
						checkRes = false;
					}
					break;
				default:
					if(Pattern.matches(this._URL_MATCH_REGEX, usrInputArr[i]) && !urlFlag) {
						urlFlag = true;
					}
					break;
			}
		}
		if(!urlFlag) {
			System.err.println("valid URL not found, please check again!\n");
			checkRes = false;
		}
		if(validPostFlag==1 && Pattern.matches(this._POST_METHOD_USR_INPUT_REGEX, usrInputArr[1])) {
			System.err.println("insufficient argument, POST method should have either -d or -f as its argument!\n");
			checkRes = false;
		}
		return checkRes;
	}
	
	private void populateHttpContext(String params, String[] usrInputArr, int idxOfParams) throws IOException {
		switch(params) {
			case "httpc":
				this.httpContext.getRequestHeaders().put("Content-Type","application/json");
				this.httpContext.getRequestHeaders().put("User-Agent","Concordia-HTTP/1.0");
				break;
			case "get":case "Get":case "GET":
				this.httpContext.getContext().put("method", "GET");
				break;
			case "post":case "Post":case "POST":
				this.httpContext.getContext().put("method", "POST");
				break;
			case "-v":
				this.httpContext.getContext().put("output", "verbose");
				break;
			case "-h":
				usrInputArr[idxOfParams+1] = usrInputArr[idxOfParams+1].replace("\"", "");
				String[] headerInfos = usrInputArr[idxOfParams+1].split(":");
				if((headerInfos[0]!=null) && (headerInfos[1]!=null)) {
					this.httpContext.getRequestHeaders().put(headerInfos[0], headerInfos[1]);
				}
				break;
			case "-d":
				usrInputArr[idxOfParams+1] = usrInputArr[idxOfParams+1].replace("\"", "");
				usrInputArr[idxOfParams+1] = usrInputArr[idxOfParams+1].replace("'", "");
				usrInputArr[idxOfParams+1] = usrInputArr[idxOfParams+1].replace("{", "");
				usrInputArr[idxOfParams+1] = usrInputArr[idxOfParams+1].replace("}", "");
				String[] bodyInfos = usrInputArr[idxOfParams+1].split(":");
				if((bodyInfos[0]!=null) && (bodyInfos[1]!=null)) {
					this.httpContext.getRequestBody().put(bodyInfos[0], bodyInfos[1]);
				}
				this.httpContext.getRequestHeaders().put("Content-Type", "application/data");
				break;
			case "-f":
				String fileDir = usrInputArr[idxOfParams+1];
				String postData = getPostDataFromLocalFile(fileDir);
				postData = postData.replace("\"", "");
				postData = postData.replace("'", "");
				postData = postData.replace("{", "");
				postData = postData.replace("}", "");
				String[] postDataInfos = postData.split(":");
				if((postDataInfos[0]!=null) && (postDataInfos[1]!=null)) {
					this.httpContext.getRequestBody().put(postDataInfos[0], postDataInfos[1]);
				}
				this.httpContext.getRequestHeaders().put("Content-Type", "application/files");
				break;
			case "-o":
				this.httpContext.getContext().put("fileSaveDir", usrInputArr[idxOfParams+1]);
				break;
			default:
				if(Pattern.matches(this._URL_MATCH_REGEX, params)) {
					params = params.replace("'", "");
					URL url = new URL(params);
					this.httpContext.getContext().put("url", params);
					this.httpContext.getContext().put("host", url.getHost());
					this.httpContext.getContext().put("port", url.getDefaultPort());
					this.httpContext.getContext().put("path", url.getPath());
					this.httpContext.getContext().put("file", url.getFile());
					this.httpContext.getContext().put("query", url.getQuery());
					this.httpContext.getRequestHeaders().put("Host",url.getHost());
				}
				break;
		}
	}
	
	private String getPostDataFromLocalFile(String fileDir) throws IOException {
		File file = new File(fileDir);
		String postData = null;
		if(file.isFile() && file.exists()) {
			InputStreamReader isr = new InputStreamReader(new FileInputStream(file));
			BufferedReader bfr = new BufferedReader(isr);
			String line = null;
			while((line=bfr.readLine())!=null) {
				postData = line;
			}
			bfr.close();
		}
		return postData;
	}
	public static void main(String[] args) throws Exception {
		// TODO Auto-generated method stub
		boolean flag = false;
		TestHttpClientLib testHttpClientLib = new TestHttpClientLib();
		Httpc httpc = new Httpc(testHttpClientLib);
		Scanner input = new Scanner(System.in);
		do {
			System.out.println("please input httpc command here:");
			System.out.print(">>> ");
			String usrIput = input.nextLine();
			flag = httpc.parseUsrInput(usrIput);
		}
		while(!flag);
		
		input.close();
		switch((String) httpc.getHttpContext().getContext().get("method")) {
			case "GET":
				httpc.getHttpContext().get();
				break;
			case "POST":
				httpc.getHttpContext().post();
				break;
		}
	}
}