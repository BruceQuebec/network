import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Httpfs {
	private final String _USR_INPUT_MATCH_REGEX = "(\\s)*(httpfs)(\\s+-v)*(\\s+-p\\s+[0-9]+)*(\\s+-d(\\s)+/\\S*)*";
	TestHttpServerLib testHttpServerLib;
	
	public TestHttpServerLib getTestHttpServerLib() {
		return this.testHttpServerLib;
	}
	public void setTestHttpServerLib(TestHttpServerLib testHttpServerLib) {
		this.testHttpServerLib = testHttpServerLib;
	}
	public Httpfs() throws IOException {
		this.testHttpServerLib = new TestHttpServerLib();
	}
	public static void main(String[] args) throws IOException {
		// TODO Auto-generated method stub
		Httpfs httpfs = new Httpfs();
		//testHttpServerLib.startService();
		
		boolean flag = false;
		Scanner input = new Scanner(System.in);
		
		do {
			System.out.println("please input httpfs command here (enter 'q' to exit):");
			System.out.print(">>> ");
			String usrIput = input.nextLine();
			if("q".equals(usrIput)) {
				flag = true;
				System.out.println("Httpfs command line has been terminated.");
				break;
			}
			else {
				flag = httpfs.parseUsrInput(usrIput);
				if(flag) {
					System.out.println("The server is now working on listening on port: " + httpfs.getTestHttpServerLib().getResponseContext().get("port"));
					new Thread(httpfs.getTestHttpServerLib()).start();
					try {
						Thread.sleep(20*100000);
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					System.out.println("The server has now stopped listening on port: " + httpfs.getTestHttpServerLib().getResponseContext().get("port"));
					System.out.println("The working directory is: " + httpfs.getTestHttpServerLib().getResponseContext().get("dir"));
					httpfs.getTestHttpServerLib().stopService();
				}
				else {
					System.err.println("Command line is invalide, try input command line again.");
				}
			}
		}
		while(!flag);
		input.close();
	}
	
	public boolean parseUsrInput(String usrInput) {
		if(!Pattern.matches(this._USR_INPUT_MATCH_REGEX, usrInput)) {
			return false;
		}
		else {
			Pattern p = Pattern.compile(this._USR_INPUT_MATCH_REGEX);
			Matcher m = p.matcher(usrInput);
			while(m.find()) {
				if(m.group(3)!=null) {
					this.testHttpServerLib.getResponseContext().put("debug mode", "open");
				}
				else {
					this.testHttpServerLib.getResponseContext().put("debug mode", "close");
				}
				if(m.group(4)!=null) {
					String[] tempArr = m.group(4).trim().replaceAll("\\s{2,}", " ").split("\\s");
					this.testHttpServerLib.getResponseContext().put("port", tempArr[1]);
				}
				else {
					this.testHttpServerLib.getResponseContext().put("port", "8080");
				}
				if(m.group(5)!=null) {
					String[] tempArr = m.group(5).trim().replaceAll("\\s{2,}", " ").split("\\s");
					if("".equals(tempArr[1])) {
						this.testHttpServerLib.getResponseContext().put("dir", System.getProperty("user.dir"));
					}
					else {
						tempArr[1] = tempArr[1].replace("/", "\\");
						this.testHttpServerLib.getResponseContext().put("dir", System.getProperty("user.dir") + tempArr[1]);
					}
				}
				else {
					this.testHttpServerLib.getResponseContext().put("dir", System.getProperty("user.dir"));
				}
			}
			return true;
		}
	}
}
