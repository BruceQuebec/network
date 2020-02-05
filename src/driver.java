//import java.io.IOException;
//
//public class driver {
//    public static void main(String args[]) throws Exception {
//    	String address = "http://httpbin.org/status/418";
//    	TestHttpClientLib httpClientConn = new TestHttpClientLib(address);
//    	
//    	System.out.println("1. The 'GET' demo:");
//    	System.out.println("----------------------------------------------------");
//    	try {
//    		httpClientConn.sendGet();
//    	} catch(IOException e){
//    		e.printStackTrace();  
//    	}
//    	
//    	address = "http://httpbin.org/get";
//    	TestHttpClientLib httpClientConn_2 = new TestHttpClientLib(address);
//    	httpClientConn_2.addParams("course", "networking");
//    	httpClientConn_2.addParams("assignment", "1");
//    	httpClientConn_2.appendParamsToContextPath();
//    	System.out.println("2. The 'GET' with query demo:");
//    	System.out.println("----------------------------------------------------");
//    	try {
//    		httpClientConn_2.sendGet();
//    	} catch(IOException e){
//    		e.printStackTrace();  
//    	}
//    	
//    	
//    	address = "http://httpbin.org/post";
//    	TestHttpClientLib httpClientConn_3 = new TestHttpClientLib(address);
//    	
//    	System.out.println("3. The 'POST' demo:");
//    	System.out.println("----------------------------------------------------");
//    	try {
//    		httpClientConn_3.sendPost();
//    	} catch(IOException e){
//    		e.printStackTrace();  
//    	}
//    }
//}
